package net.fstab.tachiai.platform.media

internal enum class NativeMixedSide { A, B }

// A foreground comparison of unrelated sources. This records user adjustments,
// never an offset obtained by subtracting the providers' unrelated clocks.
internal class NativeMixedPair(
    val a: NativePairMember,
    val b: NativePairMember,
    private val acquireFocus: () -> Boolean,
    private val releaseFocus: () -> Unit,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val requireCurrentLiveWindowOnPlay: Boolean = false,
    private val admission: (NativePairMember) -> Boolean = { true },
    initialAdjustmentValid: Boolean = true,
) : AutoCloseable {
    var status = "Prepared paused; press Play."
        private set
    // Positive = A requested later relative to B, not measured synchronization.
    var requestedAdjustmentMs = 0L
        private set
    var requestedAdjustmentValid = initialAdjustmentValid
        private set
    var busy = false
        private set
    var cleanupFailed = false
        private set
    private var closed = false
    private var polling = false
    var lastTimingSettlement: NativeTimingSettlement? = null
        private set
    // Identity changes only at an SDK dispatch boundary, never for a preflight
    // refusal. No provider identity or reusable authority is stored here.
    var lastTimingAttempt: Any? = null
        private set
    private var generation = 0L
    // Authority to continue only the untouched peer's existing buffered point.
    // It is never a seek target or permission for an explicit Play operation.
    private data class BufferedContinuation(val side: NativeMixedSide, val member: NativePairMember,
        val heldClockMs: Long)
    private data class MarkedTargetProof(val target: NativeAlignmentTarget, val member: NativePairMember)
    private data class MarkedResumeProof(val targets: List<MarkedTargetProof>, val startedMs: Long)
    private data class AlignmentPending(val targets: List<NativeAlignmentTarget>, val resume: Boolean,
        val startedMs: Long, val generation: Long, val continuation: BufferedContinuation?, val deltaMs: Long)
    private var alignmentPending: AlignmentPending? = null
    private data class RecoveryPending(val side: NativeMixedSide, val generation: Long,
        val resume: Boolean, val continuation: BufferedContinuation?)
    private var recoveryPending: RecoveryPending? = null
    private data class Pending(
        val side: NativeMixedSide,
        val targetClockMs: Long,
        val liveClock: Boolean,
        val resume: Boolean,
        val startedMs: Long,
        val settlementToleranceMs: Long,
        val generation: Long,
        val continuation: BufferedContinuation?,
        val deltaMs: Long,
    )
    private var pending: Pending? = null
    private val recovery = NativeLiveRecovery(listOf(a, b), clockMs, { !closed && admission(it) }) {
        lastTimingAttempt = Any()
        requestedAdjustmentValid = false
        lastRelativePlan = null
    }
    var lastRelativePlan: NativeRelativeNudgePlan? = null
        private set
    var lastAlignmentPlan: NativeAlignmentPlan? = null
        private set

    private fun member(side: NativeMixedSide) = if (side == NativeMixedSide.A) a else b
    private fun snapshot(member: NativePairMember): NativeTimingSnapshot? =
        try { member.timingSnapshot() } catch (_: Exception) { null }
    private fun playing(member: NativePairMember, value: Boolean): Boolean =
        try { member.setTimingPlaying(value) } catch (_: Exception) { false }
    private fun timingAdmitted(expected: Long): Boolean = !closed && generation == expected && listOf(a, b).all {
        try { admission(it) } catch (_: Exception) { false }
    } && !closed && generation == expected

    private fun heldReady(value: NativeTimingSnapshot?) = value != null && value.state == 3 &&
        !value.playing && !value.playWhenReady && !value.playingAd

    private fun bufferedContinuation(side: NativeMixedSide, original: NativeTimingSnapshot?,
        held: NativeTimingSnapshot?): BufferedContinuation? {
        if (original == null || original.state != 3 || !original.playing || !original.playWhenReady ||
            original.playingAd || !(original.live || original.dynamic) ||
            !heldReady(held) || held == null || !(held.live || held.dynamic)) return null
        val originalClock = original.contentTimeMs ?: return null
        val position = held.positionMs ?: return null
        val buffered = held.bufferedMs ?: return null
        val clock = held.contentTimeMs ?: return null
        if (buffered <= position) return null
        val difference = try { Math.subtractExact(clock, originalClock) }
            catch (_: ArithmeticException) { return null }
        if (difference !in -100L..100L) return null
        return BufferedContinuation(side, member(side), clock)
    }

    private fun canContinueBuffered(proof: BufferedContinuation?, side: NativeMixedSide,
        value: NativeTimingSnapshot): Boolean {
        if (proof == null || proof.side != side || proof.member !== member(side) ||
            !(value.live || value.dynamic) || !heldReady(value)) return false
        val position = value.positionMs ?: return false
        val buffered = value.bufferedMs ?: return false
        val clock = value.contentTimeMs ?: return false
        if (position >= 0 || buffered <= position) return false
        val difference = try { Math.subtractExact(clock, proof.heldClockMs) }
            catch (_: ArithmeticException) { return false }
        return difference in -100L..100L
    }

    fun pause(): Boolean {
        if (closed) return false
        generation++
        val began = generation
        if (alignmentPending != null) requestedAdjustmentValid = false
        alignmentPending = null
        recoveryPending = null
        pending = null
        recovery.cancel()
        busy = false
        val pa = playing(a, false)
        if (closed || generation != began) return false
        val pb = playing(b, false)
        if (closed || generation != began) return false
        status = if (pa && pb) "Paused." else "Pause refused; Stop required."
        return pa && pb
    }

    fun focusLost() {
        if (closed) return
        val paused = pause()
        status = if (paused) "Audio focus lost; explicit Play required."
            else "Audio focus lost; pause refused; Stop required."
    }

    fun play(playAdmission: () -> Boolean = { true }, onStaleLive: ((Set<NativeMixedSide>) -> Boolean)? = null): Boolean {
        if (closed) return false
        if (busy && !pause()) return false
        val sa = snapshot(a)
        val sb = snapshot(b)
        if (sa == null || sb == null || sa.state != 3 || sb.state != 3 || sa.playingAd || sb.playingAd) {
            status = "Both players must be READY before Play."
            return false
        }
        fun inLiveWindow(value: NativeTimingSnapshot): Boolean {
            if (!value.live && !value.dynamic) return true
            val position = value.positionMs ?: return false
            val duration = value.durationMs ?: return false
            return duration > 0 && position >= 0 && position <= duration
        }
        if (requireCurrentLiveWindowOnPlay && (!inLiveWindow(sa) || !inLiveWindow(sb))) {
            // The presentation owner may retire just the affected sessions.
            // Return immediately: that callback can detach or close this pair.
            if (onStaleLive != null) return onStaleLive(buildSet {
                if (!inLiveWindow(sa)) add(NativeMixedSide.A)
                if (!inLiveWindow(sb)) add(NativeMixedSide.B)
            })
            val held = pause()
            status = if (held) "Play refused: live point outside/unknown current window; explicit catch-up required."
                else "Play refused: stale live point and pause refused; Stop required."
            return false
        }
        val focused = try { acquireFocus() } catch (_: Exception) { false }
        val admitted = try { playAdmission() } catch (_: Exception) { false }
        if (!focused || !admitted) { pause(); status = "Audio focus or playback admission denied; pause requested for both."; return false }
        val pa = playing(a, true)
        val pb = playing(b, true)
        if (!pa || !pb) { pause(); status = "Play refused; pause requested for both."; return false }
        status = "Play requested; no synchronization lock."
        return true
    }

    fun setVolume(side: NativeMixedSide, volume: Float): Boolean {
        if (closed || !volume.isFinite() || volume !in 0f..1f) return false
        return try { member(side).setVolume(volume) } catch (_: Exception) { false }
    }

    fun shift(side: NativeMixedSide, deltaMs: Long): NativeSeekPlan = shift(side, deltaMs, false)

    private fun shift(side: NativeMixedSide, deltaMs: Long, requireFullStep: Boolean): NativeSeekPlan {
        if (closed) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        // A newer timing command cancels any previous automatic restoration.
        if (busy && !pause()) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        val began = generation
        if (!timingAdmitted(began)) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        val sa = snapshot(a)
        val sb = snapshot(b)
        if (!timingAdmitted(began)) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        val selected = if (side == NativeMixedSide.A) sa else sb
        val plan = when {
            sa == null || sb == null -> NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
            sa.playingAd || sb.playingAd -> NativeSeekPlan(NativeSeekOutcome.AD_BLOCKED)
            else -> nativeRelativeSeekPlan(selected, deltaMs)
        }
        if (plan.outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.CLAMPED, NativeSeekOutcome.NO_CHANGE)) {
            status = "Shift refused: ${plan.outcome}"
            return plan
        }
        val position = selected?.positionMs ?: return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        val target = plan.targetMs ?: return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        val liveClock = selected.live || selected.dynamic
        val initialClock = if (liveClock) selected.contentTimeMs else position
        if (initialClock == null) {
            status = "Shift refused: stable live content clock unavailable."
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        val movement = try { Math.subtractExact(target, position) }
            catch (_: ArithmeticException) { return NativeSeekPlan(NativeSeekOutcome.OUTSIDE_WINDOW) }
        if (requireFullStep && (plan.outcome != NativeSeekOutcome.REQUESTED || movement != deltaMs ||
                position < 0 || position > checkNotNull(selected.durationMs) || sa?.state != 3 || sb?.state != 3)) {
            status = "Relative shift refused: selected full step no longer available."
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        val targetClock: Long
        val adjustment: Long
        try {
            targetClock = Math.addExact(initialClock, movement)
            adjustment = Math.addExact(requestedAdjustmentMs,
                if (side == NativeMixedSide.A) movement else Math.negateExact(movement))
            if (targetClock < 0) return NativeSeekPlan(NativeSeekOutcome.OUTSIDE_WINDOW)
        } catch (_: ArithmeticException) { return NativeSeekPlan(NativeSeekOutcome.OUTSIDE_WINDOW) }
        if (plan.outcome == NativeSeekOutcome.NO_CHANGE) {
            status = "No shift within the current window."
            return plan
        }
        val resume = sa?.playWhenReady == true && sb?.playWhenReady == true
        val expected = began + 1
        if (!pause() || !timingAdmitted(expected)) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        val peer = if (side == NativeMixedSide.A) NativeMixedSide.B else NativeMixedSide.A
        val continuation = bufferedContinuation(peer, if (peer == NativeMixedSide.A) sa else sb, snapshot(member(peer)))
        if (!timingAdmitted(expected)) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        busy = true
        lastTimingAttempt = Any()
        val accepted = try { member(side).seekToMs(target) }
            catch (_: Exception) { NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) }
        if (!timingAdmitted(expected)) {
            if (!closed && generation == expected) pause()
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        // A changed window must not silently change the requested transaction.
        if (accepted.outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE) ||
            accepted.targetMs != target) {
            pause(); status = "Seek refused or changed; pause requested for both."
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        val started = clockMs()
        if (!timingAdmitted(expected)) {
            if (!closed && generation == expected) pause()
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        requestedAdjustmentMs = adjustment
        // A full relative step smaller than 200 ms must not settle at its
        // unchanged starting point. Historical direct shifts retain 100 ms.
        val tolerance = if (requireFullStep) minOf(100L, kotlin.math.abs(movement) / 2) else 100L
        pending = Pending(side, targetClock, liveClock, resume, started, tolerance, expected, continuation,
            if (side == NativeMixedSide.A) movement else Math.negateExact(movement))
        busy = true
        status = "Holding both; waiting for selected target clock (8 s maximum)."
        return plan
    }

    fun shiftRelative(deltaMs: Long): NativeSeekPlan {
        lastRelativePlan = null
        if (closed || busy) {
            if (!closed) status = "Relative shift refused: wait for the current seek check."
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        val plan = nativeRelativeNudgePlan(snapshot(a), snapshot(b), deltaMs)
        lastRelativePlan = plan
        if (plan.outcome != NativeRelativeNudgeOutcome.SELECTED) {
            status = "Relative shift: ${plan.outcome}; no full step selected."
            return NativeSeekPlan(when (plan.outcome) {
                NativeRelativeNudgeOutcome.AD_BLOCKED -> NativeSeekOutcome.AD_BLOCKED
                NativeRelativeNudgeOutcome.NO_CHANGE -> NativeSeekOutcome.NO_CHANGE
                else -> NativeSeekOutcome.UNAVAILABLE
            })
        }
        // Exactly one dispatch. Do not try the other side after an uncertain
        // platform/hold/settlement failure. Existing transaction guards apply.
        return shift(checkNotNull(plan.side), checkNotNull(plan.movementMs), true)
    }

    // User-marked alignment may split an exact movement across both windows.
    // No other timing control acquires this composite behavior.
    fun alignRelative(deltaMs: Long): NativeSeekPlan {
        if (closed || busy) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        lastAlignmentPlan = null
        val began = generation
        fun admitted() = !closed && generation == began && listOf(a, b).all {
            try { admission(it) } catch (_: Exception) { false }
        } && generation == began && !closed
        if (!admitted()) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        val sa = snapshot(a); val sb = snapshot(b)
        val initial = nativeMarkedAlignmentPlan(sa, sb, deltaMs)
        if (!admitted()) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        lastAlignmentPlan = initial
        if (initial.outcome != NativeSeekOutcome.REQUESTED) {
            status = if (initial.outcome == NativeSeekOutcome.NO_CHANGE) "No adjustment needed for the marked event."
            else initial.availableMagnitudeMs?.let {
                "Marked alignment unavailable: requested ${kotlin.math.abs(deltaMs / 1_000.0)} s; combined current windows allow ${it / 1_000.0} s."
            } ?: "Marked alignment refused: ${initial.outcome}."
            return NativeSeekPlan(initial.outcome)
        }
        val adjustment = try { Math.addExact(requestedAdjustmentMs, deltaMs) }
            catch (_: ArithmeticException) { return NativeSeekPlan(NativeSeekOutcome.OUTSIDE_WINDOW) }
        val resume = sa?.playWhenReady == true && sb?.playWhenReady == true
        val expected = began + 1
        if (!pause() || closed || generation != expected) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        fun current() = !closed && generation == expected && listOf(a, b).all {
            try { admission(it) } catch (_: Exception) { false }
        } && generation == expected && !closed
        val freshA = snapshot(a); val freshB = snapshot(b)
        fun held(value: NativeTimingSnapshot?) = value != null && value.state == 3 &&
            !value.playing && !value.playWhenReady && !value.playingAd
        val fresh = nativeMarkedAlignmentPlan(freshA, freshB, deltaMs)
        if (!closed && generation == expected) lastAlignmentPlan = fresh
        if (!current() || !held(freshA) || !held(freshB) || fresh.outcome != NativeSeekOutcome.REQUESTED ||
            (sa?.live == true || sa?.dynamic == true) != (freshA?.live == true || freshA?.dynamic == true) ||
            (sb?.live == true || sb?.dynamic == true) != (freshB?.live == true || freshB?.dynamic == true)) {
            if (!closed && generation == expected) status = "Marked alignment refused after holding both feeds."
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        val wasValid = requestedAdjustmentValid
        val untouched = NativeMixedSide.entries.singleOrNull { side -> fresh.targets.none { it.side == side } }
        val continuation = untouched?.let { side -> bufferedContinuation(side,
            if (side == NativeMixedSide.A) sa else sb, if (side == NativeMixedSide.A) freshA else freshB) }
        busy = true
        var dispatchedAny = false
        for (target in fresh.targets) {
            if (!current()) {
                if (!closed && generation == expected) pause()
                return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
            }
            val selected = member(target.side)
            val check = snapshot(selected)
            val checkedClock = if (target.liveClock) check?.contentTimeMs else check?.positionMs
            val checkedTarget = if (check?.positionMs != null && checkedClock != null) try {
                Math.addExact(check.positionMs, Math.subtractExact(target.targetClockMs, checkedClock))
            } catch (_: ArithmeticException) { null } else null
            val plan = checkedTarget?.let { nativeSeekPlan(check, it) }
            if (!current() || !held(check) || (check?.live == true || check?.dynamic == true) != target.liveClock ||
                checkedTarget == null || plan?.outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE) ||
                plan?.targetMs != checkedTarget) {
                if (!closed && generation == expected) {
                    pause(); status = if (dispatchedAny) "Marked alignment target changed after a seek; both paused."
                        else "Marked alignment target changed before dispatch; both paused."
                }
                return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
            }
            // Holding alone cannot invalidate previously confirmed history.
            // A dispatched seek can move footage even if its reply is uncertain.
            requestedAdjustmentValid = false
            dispatchedAny = true
            lastTimingAttempt = Any()
            val accepted = try { selected.seekToMs(checkedTarget) }
                catch (_: Exception) { NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) }
            if (!current() || accepted.outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE) ||
                accepted.targetMs != checkedTarget) {
                if (!closed && generation == expected) {
                    pause(); status = "Marked alignment dispatch uncertain; both paused."
                }
                return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
            }
        }
        val started = clockMs()
        if (!current()) {
            if (!closed && generation == expected) pause()
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        requestedAdjustmentMs = adjustment
        requestedAdjustmentValid = wasValid
        alignmentPending = AlignmentPending(fresh.targets, resume, started, expected, continuation, deltaMs)
        status = "Holding both; waiting for all marked targets (8 s maximum)."
        return NativeSeekPlan(NativeSeekOutcome.REQUESTED)
    }

    val pendingSeekDiagnostics: List<NativePendingSeekDiagnostic> get() {
        val alignment = alignmentPending
        val ordinary = pending
        val started = alignment?.startedMs ?: ordinary?.startedMs ?: return emptyList()
        val targets = alignment?.targets ?: listOf(NativeAlignmentTarget(checkNotNull(ordinary).side, 0, 0,
            ordinary.targetClockMs, ordinary.liveClock))
        val elapsed = try { Math.subtractExact(clockMs(), started) } catch (_: ArithmeticException) { Long.MAX_VALUE }
        return targets.map { target ->
            val value = snapshot(member(target.side))
            NativePendingSeekDiagnostic(target.side, target.targetClockMs,
                if (target.liveClock) value?.contentTimeMs else value?.positionMs, target.liveClock,
                value != null && (value.live || value.dynamic) == target.liveClock, value?.state,
                value?.playing, value?.playWhenReady, value?.playingAd,
                if (alignment != null) minOf(100L, kotlin.math.abs(target.movementMs) / 2) else checkNotNull(ordinary).settlementToleranceMs,
                elapsed)
        }
    }

    // Explicit recovery, never a fallback for an ordinary timing nudge. The
    // SDK chooses its current live default, not an exact or future live edge.
    fun catchUpPlan(side: NativeMixedSide): NativeSeekPlan =
        if (closed || busy) NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) else recovery.plan(member(side))

    fun catchUp(side: NativeMixedSide): NativeSeekPlan {
        if (closed || busy) {
            if (!closed) status = "Catch-up refused: wait for the current seek check."
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        val began = generation
        if (!timingAdmitted(began)) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        val sa = snapshot(a); val sb = snapshot(b)
        if (!timingAdmitted(began)) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        val resume = sa?.playing == true && sa.playWhenReady && sb?.playing == true && sb.playWhenReady
        // Keep reentrant timing commands out while recovery performs its own
        // hold/revalidation. Explicit Pause still cancels this generation.
        busy = true
        val result = recovery.start(member(side))
        if (!timingAdmitted(began)) {
            if (!closed && generation == began) {
                if (result.state == NativeLiveRecoveryStartState.STARTED) {
                    // Only a published recovery needs cancellation/holding.
                    // Refused starts already performed their own bounded hold
                    // and must not invoke provider pause hooks again.
                    pause()
                    status = "Catch-up authorization lost; both paused; timing anchor invalid."
                } else {
                    busy = false
                    recoveryPending = null
                    status = when (result.state) {
                        NativeLiveRecoveryStartState.HOLD_REFUSED -> "Pause refused; Stop required."
                        NativeLiveRecoveryStartState.DISPATCH_REFUSED -> if (result.held == true)
                            "Catch-up refused; both paused; timing anchor invalid."
                            else "Catch-up refused; pause refused; Stop required; timing anchor invalid."
                        else -> "Catch-up not dispatched: authorization lost."
                    }
                }
            }
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        if (result.state != NativeLiveRecoveryStartState.STARTED) busy = false
        when (result.state) {
            NativeLiveRecoveryStartState.PREFLIGHT_REFUSED, NativeLiveRecoveryStartState.RECHECK_REFUSED ->
                status = "Catch-up not dispatched: ${result.plan.outcome}."
            NativeLiveRecoveryStartState.HOLD_REFUSED -> status = "Pause refused; Stop required."
            NativeLiveRecoveryStartState.DISPATCH_REFUSED -> status = if (result.held == true) "Catch-up refused; both paused; timing anchor invalid."
                else "Catch-up refused; pause refused; Stop required; timing anchor invalid."
            NativeLiveRecoveryStartState.STARTED -> {
                val peer = if (side == NativeMixedSide.A) NativeMixedSide.B else NativeMixedSide.A
                val continuation = bufferedContinuation(peer, if (peer == NativeMixedSide.A) sa else sb,
                    snapshot(member(peer)))
                if (!timingAdmitted(began)) {
                    if (!closed && generation == began) pause()
                    return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
                }
                recoveryPending = RecoveryPending(side, began, resume, continuation)
                busy = true
                status = "Catch-up requested; waiting for held live window (8 s maximum)."
            }
        }
        return result.plan
    }

    fun poll() {
        if (closed || polling) return
        polling = true
        try { pollTransactions() } finally { polling = false }
    }

    private fun recordSettlement(deltaMs: Long) {
        val revision = try { Math.addExact(lastTimingSettlement?.revision ?: 0L, 1L) }
            catch (_: ArithmeticException) { return }
        lastTimingSettlement = NativeTimingSettlement(revision, deltaMs)
    }

    private fun pollTransactions() {
        if (closed) return
        alignmentPending?.let { transaction ->
            fun current() = !closed && generation == transaction.generation && alignmentPending === transaction
            fun admitted() = current() && listOf(a, b).all {
                try { admission(it) } catch (_: Exception) { false }
            } && current()
            val elapsed = try { Math.subtractExact(clockMs(), transaction.startedMs) }
                catch (_: ArithmeticException) { Long.MAX_VALUE }
            if (!admitted()) {
                if (current()) { pause(); status = "Marked alignment authorization lost; both paused." }
                return
            }
            if (elapsed < 0 || elapsed >= 8_000) {
                if (current()) { pause(); status = "Marked alignment timed out or its clock moved backward; both paused." }
                return
            }
            val values = mapOf(NativeMixedSide.A to snapshot(a), NativeMixedSide.B to snapshot(b))
            if (!admitted()) {
                if (current()) { pause(); status = "Marked alignment authorization lost; both paused." }
                return
            }
            val afterRead = try { Math.subtractExact(clockMs(), transaction.startedMs) }
                catch (_: ArithmeticException) { Long.MAX_VALUE }
            if (afterRead < elapsed || afterRead >= 8_000) {
                if (current()) { pause(); status = "Marked alignment timed out or its clock moved backward; both paused." }
                return
            }
            if (values.values.any { it == null || it.state != 3 || it.playing || it.playWhenReady || it.playingAd }) return
            if (transaction.targets.any { target ->
                val value = checkNotNull(values[target.side])
                val clock = if (target.liveClock) value.contentTimeMs else value.positionMs
                val difference = if (clock == null || clock < 0) null else try {
                    Math.subtractExact(clock, target.targetClockMs)
                } catch (_: ArithmeticException) { null }
                (value.live || value.dynamic) != target.liveClock || difference == null ||
                    difference < -minOf(100L, kotlin.math.abs(target.movementMs) / 2) ||
                    difference > minOf(100L, kotlin.math.abs(target.movementMs) / 2)
            }) return
            if (!admitted()) return
            val targetProblem = transaction.targets.firstNotNullOfOrNull { target ->
                markedTargetProblem(target, values[target.side], allowBufferedExpired = transaction.resume)
            }
            if (targetProblem != null) {
                if (current()) { pause(); status = "$targetProblem; both paused." }
                return
            }
            status = "All marked target clocks observed held; no frame/audio precision claim."
            if (transaction.resume) resumeTiming(transaction.generation, transaction.targets.associate { it.side to it.liveClock },
                transaction.continuation, "Marked alignment", ::current,
                MarkedResumeProof(transaction.targets.map { MarkedTargetProof(it, member(it.side)) }, transaction.startedMs))
            if (current()) { recordSettlement(transaction.deltaMs); alignmentPending = null; busy = false }
            return
        }
        if (recovery.busy) {
            // start() may still be capturing the held peer in a reentrant
            // snapshot callback. It has not published a resumable transaction.
            val transaction = recoveryPending ?: return
            fun current() = !closed && generation == transaction.generation &&
                recoveryPending === transaction
            val result = recovery.poll()
            if (!current()) return
            if (result.state != NativeLiveRecoveryPollState.SETTLED) busy = recovery.busy
            when (result.state) {
                NativeLiveRecoveryPollState.TIMED_OUT -> status = if (result.held == true) "Catch-up check timed out; both paused; timing anchor invalid."
                    else "Catch-up check timed out; pause refused; Stop required; timing anchor invalid."
                NativeLiveRecoveryPollState.ADMISSION_LOST -> status = if (result.held == true) "Catch-up authorization lost; both paused; timing anchor invalid."
                    else "Catch-up authorization lost; pause refused; Stop required; timing anchor invalid."
                NativeLiveRecoveryPollState.SETTLED -> {
                    if (transaction.resume) {
                        resumeTiming(transaction.generation, mapOf(transaction.side to true), transaction.continuation,
                            "Catch-up", ::current)
                    } else status = "SDK READY in current live window; both paused. Explicit Play required; not exact live edge."
                }
                else -> Unit
            }
            if (current() && !recovery.busy) { recoveryPending = null; busy = false }
            return
        }
        val transaction = pending ?: return
        fun current() = !closed && generation == transaction.generation && pending === transaction
        fun admitted() = current() && timingAdmitted(transaction.generation) && current()
        val elapsed = try { Math.subtractExact(clockMs(), transaction.startedMs) }
            catch (_: ArithmeticException) { Long.MAX_VALUE }
        if (!admitted() || elapsed < 0 || elapsed >= 8_000) {
            if (current()) { pause(); status = "Seek check timed out or authorization lost; pause requested for both." }
            return
        }
        val sa = snapshot(a)
        val sb = snapshot(b)
        if (!admitted()) { if (current()) pause(); return }
        val afterRead = try { Math.subtractExact(clockMs(), transaction.startedMs) }
            catch (_: ArithmeticException) { Long.MAX_VALUE }
        if (afterRead < elapsed || afterRead >= 8_000) {
            if (current()) { pause(); status = "Seek check timed out; pause requested for both." }
            return
        }
        if (!heldReady(sa) || !heldReady(sb)) return
        val selected = checkNotNull(if (transaction.side == NativeMixedSide.A) sa else sb)
        if ((selected.live || selected.dynamic) != transaction.liveClock) return
        val clock = if (transaction.liveClock) selected.contentTimeMs else selected.positionMs
        val difference = if (clock == null || clock < 0) null else try {
            Math.subtractExact(clock, transaction.targetClockMs)
        } catch (_: ArithmeticException) { null }
        if (difference == null || difference < -transaction.settlementToleranceMs ||
            difference > transaction.settlementToleranceMs || !admitted()) return
        status = "Selected held target observed within ${transaction.settlementToleranceMs} ms; no frame/audio precision claim."
        if (transaction.resume) resumeTiming(transaction.generation, mapOf(transaction.side to transaction.liveClock),
            transaction.continuation, "Timing", ::current)
        if (current()) { recordSettlement(transaction.deltaMs); pending = null; busy = false }
    }

    private fun markedTargetProblem(target: NativeAlignmentTarget, value: NativeTimingSnapshot?,
        allowBufferedExpired: Boolean): String? {
        if (!heldReady(value) || value == null) return "Marked target is no longer held READY outside advertisements"
        if ((value.live || value.dynamic) != target.liveClock) return "Marked target clock kind changed"
        val clock = if (target.liveClock) value.contentTimeMs else value.positionMs
        val difference = if (clock == null || clock < 0) null else try {
            Math.subtractExact(clock, target.targetClockMs)
        } catch (_: ArithmeticException) { null }
        val tolerance = minOf(100L, kotlin.math.abs(target.movementMs) / 2)
        if (difference == null || difference !in -tolerance..tolerance) return "Marked target clock moved after confirmation"
        val position = value.positionMs ?: return "Marked target position is unavailable"
        val duration = value.durationMs ?: return "Marked target playback window is unavailable"
        if (duration <= 0) return "Marked target playback window is unavailable"
        if (position in 0..duration) return null
        if (position < 0 && target.liveClock) {
            if (!allowBufferedExpired) return "Marked target expired while playback was paused"
            if (value.bufferedMs?.let { it > position } == true) return null
            return "Marked target expired and its buffered continuation is unavailable"
        }
        return "Marked target is beyond its playback window"
    }

    private fun resumeTiming(expected: Long, targeted: Map<NativeMixedSide, Boolean>, continuation: BufferedContinuation?,
        label: String, current: () -> Boolean, markedProof: MarkedResumeProof? = null) {
        var markedFailure = "Marked alignment authorization changed"
        fun admitted(): Boolean {
            if (!current() || !timingAdmitted(expected) || !current()) {
                markedFailure = "Marked alignment authorization changed"; return false
            }
            if (markedProof != null) {
                val elapsed = try { Math.subtractExact(clockMs(), markedProof.startedMs) }
                    catch (_: ArithmeticException) { Long.MAX_VALUE }
                if (elapsed < 0 || elapsed >= 8_000) {
                    markedFailure = "Marked alignment resume timed out"; return false
                }
            }
            return markedProof == null || current() && timingAdmitted(expected) && current()
        }
        fun fail(default: String) {
            if (current()) {
                val held = pause()
                val cause = if (markedProof != null) markedFailure else default.removeSuffix("; both paused.")
                status = if (held) "$cause; both paused." else "$cause; pause refused; Stop required."
            }
        }
        fun playable(side: NativeMixedSide, value: NativeTimingSnapshot?): Boolean {
            val proof = markedProof?.targets?.singleOrNull { it.target.side == side }
            if (proof != null) {
                val problem = if (proof.member !== member(side)) "Marked target session changed"
                    else markedTargetProblem(proof.target, value, allowBufferedExpired = true)
                if (problem != null) { markedFailure = problem; return false }
                return true
            }
            if (!heldReady(value) || value == null) {
                if (markedProof != null) markedFailure = "Marked alignment untouched feed is no longer held READY"
                return false
            }
            val position = value.positionMs
            val duration = value.durationMs
            val liveClock = value.live || value.dynamic
            val inWindow = position != null && duration != null && duration > 0 && position in 0..duration
            if (side in targeted) return targeted[side] == liveClock && inWindow
            if (continuation?.side == side && !liveClock) {
                if (markedProof != null) markedFailure = "Marked alignment untouched feed clock kind changed"
                return false
            }
            if (!liveClock || inWindow) return true
            return canContinueBuffered(continuation, side, value).also {
                if (!it) markedFailure = "Marked alignment untouched feed cannot continue its buffered point"
            }
        }
        fun ready(): Boolean {
            if (!admitted()) return false
            val values = NativeMixedSide.entries.associateWith { snapshot(member(it)) }
            return admitted() && values.all { (side, value) -> playable(side, value) }
        }
        if (!ready()) {
            fail("$label cannot resume in the current live window; both paused.")
            return
        }
        val focused = try { acquireFocus() } catch (_: Exception) { false }
        if (!focused || !ready()) {
            if (!focused) markedFailure = "Marked alignment audio focus denied"
            fail("$label resume denied; both paused.")
            return
        }
        for (side in NativeMixedSide.entries) {
            if (!admitted()) { fail("$label resume denied; both paused."); return }
            val value = snapshot(member(side))
            if (!admitted() || !playable(side, value)) { fail("$label resume denied; both paused."); return }
            val accepted = playing(member(side), true)
            if (!current()) return
            if (!accepted || !admitted()) {
                if (!accepted) markedFailure = "Marked alignment playback request was refused"
                fail("$label resume denied; both paused."); return
            }
        }
        if (current()) status = "$label playback requested; no synchronization lock."
    }

    // A presentation borrowing its sessions may end coordination without ending
    // either session or shared audio focus. Cancel automatic seek restoration;
    // deliberately preserve each member's current transport state.
    fun detach() {
        if (closed) return
        generation++
        alignmentPending = null
        recoveryPending = null
        pending = null
        recovery.cancel()
        busy = false
        closed = true
        requestedAdjustmentValid = false
        status = "Pair coordination ended."
    }

    override fun close() {
        if (closed) return
        pause()
        closed = true
        for (cleanup in listOf<() -> Unit>({ a.close() }, { b.close() }, releaseFocus)) {
            try { cleanup() } catch (_: Exception) { cleanupFailed = true }
        }
        status = if (cleanupFailed) "Stopped; cleanup reported failure." else "Stopped."
    }
}
