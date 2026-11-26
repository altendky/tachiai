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
    private data class TimingTargetProof(val side: NativeMixedSide, val member: NativePairMember,
        val targetClockMs: Long, val liveClock: Boolean, val toleranceMs: Long)
    private data class TimingResumeProof(val targets: List<TimingTargetProof>, val startedMs: Long,
        val targetLabel: String)
    private data class AlignmentPending(val targets: List<NativeAlignmentTarget>, val resume: Boolean,
        val startedMs: Long, val generation: Long, val continuation: BufferedContinuation?, val deltaMs: Long)
    private var alignmentPending: AlignmentPending? = null
    private data class RecoveryPending(val side: NativeMixedSide, val generation: Long,
        val resume: Boolean, val continuation: BufferedContinuation?)
    private var recoveryPending: RecoveryPending? = null
    // A skip or scrub moving both feeds by one amount (#655, live #660). It
    // keeps the relative offset, so it records no timing settlement.
    private data class SkipPending(val targets: List<NativeSkipTarget>, val resume: Boolean, val startedMs: Long,
        val generation: Long, val toleranceMs: Long, val statusBefore: String)
    private var skipPending: SkipPending? = null
    var lastSkipPlan: NativePairSkipPlan? = null
        private set
    // Building delay (#618): one feed held on its frame while the other plays
    // on. heldClockMs/peerClockMs are each feed's own clock just before the
    // hold began; only progress within each clock is compared. movementMs and
    // deltaMs change when Timing steps adjust the build. resumeClockMs is set
    // once the held feed has been sought to its target and asked to play.
    private class DelayBuild(val side: NativeMixedSide, val held: NativePairMember, val peer: NativePairMember,
        var movementMs: Long, var deltaMs: Long, val heldClockMs: Long, val peerClockMs: Long, val peerLive: Boolean,
        val startedMs: Long, val generation: Long) {
        var remainingMs = -movementMs
        var resumeClockMs: Long? = null
        var resumeStartedMs = 0L
        var reanchors = 0
    }
    private var delayPending: DelayBuild? = null
    // The held feed and the countdown to its resume, for its pane.
    val delayBuild: NativeDelayBuildState? get() = delayPending?.let {
        NativeDelayBuildState(it.side, it.remainingMs, (-it.movementMs).coerceAtLeast(0))
    }
    var lastDelayBuildEnd: NativeDelayBuildEnd? = null
        private set
    var lastDelayBuildPlan: NativeDelayBuildPlan? = null
        private set
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
        // Pause cancels a building delay; the held feed returns to its
        // previous relationship and both stay paused (#618).
        val build = delayPending
        val restore = build?.let(::restoreTarget)
        generation++
        val began = generation
        val ended = build?.let { endDelayBuild(it, restore, play = false) { "$DELAY_BUILD_CANCELED; paused." } }
        if (alignmentPending != null) requestedAdjustmentValid = false
        alignmentPending = null
        recoveryPending = null
        pending = null
        // Both skip seeks were dispatched together; cancelling only their resume
        // leaves the relative offset as it was.
        skipPending = null
        recovery.cancel()
        busy = false
        val pa = playing(a, false)
        if (closed || generation != began) return false
        val pb = playing(b, false)
        if (closed || generation != began) return false
        status = if (!pa || !pb) "Pause refused; Stop required." else ended?.takeIf { it.canceled }?.message ?: "Paused."
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
        // #618: a feed paused past its oldest recorded point resumes there.
        var resumed: NativeOldestResumePlan? = null
        if (requireCurrentLiveWindowOnPlay && (!inLiveWindow(sa) || !inLiveWindow(sb))) {
            resumed = nativeOldestResumePlan(mapOf(NativeMixedSide.A to sa, NativeMixedSide.B to sb))
            if (resumed != null && !resumeFromOldest(resumed)) return false
        }
        if (resumed == null && requireCurrentLiveWindowOnPlay && (!inLiveWindow(sa) || !inLiveWindow(sb))) {
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
        status = resumed?.let { nativeOldestResumeNotice(it, pair = true) } ?: "Play requested; no synchronization lock."
        return true
    }

    // The last Play that moved a feed to its oldest recorded point (#618).
    var lastOldestResume: NativeOldestResumePlan? = null
        private set

    private fun resumeFromOldest(plan: NativeOldestResumePlan): Boolean {
        val began = generation
        if (!timingAdmitted(began)) return false
        lastOldestResume = plan
        lastTimingAttempt = Any()
        // Feeds moved apart no longer hold the requested offset.
        if (!plan.keepsOffset) requestedAdjustmentValid = false
        for (target in plan.targets) {
            val accepted = try { member(target.side).seekToMs(target.targetMs) }
                catch (_: Exception) { NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) }
            if (!timingAdmitted(began) || accepted.outcome != NativeSeekOutcome.REQUESTED ||
                accepted.targetMs != target.targetMs) {
                requestedAdjustmentValid = false
                if (!closed && generation == began) {
                    pause(); status = "Resuming from the oldest recorded point was refused; pause requested for both."
                }
                return false
            }
        }
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
        if (liveClock && nativeLiveDelayExceeded(selected, movement)) {
            status = LIVE_DELAY_LIMIT_MESSAGE
            return NativeSeekPlan(NativeSeekOutcome.OUTSIDE_WINDOW)
        }
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
        val sa = snapshot(a); val sb = snapshot(b)
        val plan = nativeRelativeNudgePlan(sa, sb, deltaMs)
        lastRelativePlan = plan
        lastDelayBuildPlan = null
        if (plan.outcome == NativeRelativeNudgeOutcome.NO_FULL_STEP) {
            // #618: not enough recorded history, but within the delay limit.
            val build = nativeDelayBuildPlan(sa, sb, deltaMs)
            lastDelayBuildPlan = build
            when (build.outcome) {
                NativeDelayBuildOutcome.BUILD -> return startDelayBuild(build)
                NativeDelayBuildOutcome.NOT_PLAYING -> {
                    status = DELAY_BUILD_NEEDS_PLAYBACK
                    return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
                }
                else -> Unit
            }
        }
        if (plan.outcome != NativeRelativeNudgeOutcome.SELECTED) {
            status = if (plan.outcome == NativeRelativeNudgeOutcome.NO_FULL_STEP && nativeNudgeDelayLimited(sa, sb, deltaMs))
                LIVE_DELAY_LIMIT_MESSAGE else "Relative shift: ${plan.outcome}; no full step selected."
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

    // Building delay (#618): use the history the feed to delay already has,
    // then hold it on that frame for the rest; the other feed and its audio
    // play on.
    private fun startDelayBuild(plan: NativeDelayBuildPlan): NativeSeekPlan {
        val side = checkNotNull(plan.side)
        val peerSide = if (side == NativeMixedSide.A) NativeMixedSide.B else NativeMixedSide.A
        val held = member(side)
        val peer = member(peerSide)
        if (!timingAdmitted(generation)) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        generation++
        val expected = generation
        lastTimingAttempt = Any()
        if (!playing(held, false) || !timingAdmitted(expected)) {
            if (!closed && generation == expected) {
                requestedAdjustmentValid = false
                pause(); status = "Building delay refused; pause requested for both."
            }
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        // Each clock as the hold began: the held feed's has stopped.
        val heldValue = snapshot(held)
        val peerValue = snapshot(peer)
        if (!timingAdmitted(expected)) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        val heldClock = heldValue?.takeIf { it.live || it.dynamic }?.contentTimeMs
        val peerLive = peerValue != null && (peerValue.live || peerValue.dynamic)
        val peerClock = if (peerLive) peerValue?.contentTimeMs else peerValue?.positionMs
        if (heldClock == null || peerClock == null || peerValue?.playWhenReady != true) {
            // Held for a moment only: let it play on where it was.
            playing(held, true)
            status = "Building delay refused: a playback clock is unavailable."
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        // Go back as far as the recorded history allows, keeping the usual
        // margin inside the oldest point; only the shortfall is built.
        val position = checkNotNull(heldValue).positionMs
        val recorded = nativeDelayBuildRecordedMs(position, plan.movementMs)
        if (recorded > 0) {
            val target = checkNotNull(position) - recorded
            val accepted = if (nativeSeekPlan(heldValue, target).let { it.outcome == NativeSeekOutcome.REQUESTED && it.targetMs == target })
                try { held.seekToMs(target) } catch (_: Exception) { NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) }
                else null
            if (!timingAdmitted(expected)) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
            if (accepted == null) {
                // The window changed under the plan: nothing was sought.
                playing(held, true)
                status = "Building delay refused: ${side}'s window changed."
                return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
            }
            if (accepted.outcome != NativeSeekOutcome.REQUESTED || accepted.targetMs != target) {
                requestedAdjustmentValid = false
                pause(); status = "Building delay refused after a seek; pause requested for both."
                return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
            }
        }
        delayPending = DelayBuild(side, held, peer, plan.movementMs, plan.deltaMs, heldClock, peerClock, peerLive,
            clockMs(), expected).also { it.remainingMs = -plan.movementMs - recorded }
        busy = true
        status = "Building delay for $side; $peerSide keeps playing."
        return NativeSeekPlan(NativeSeekOutcome.REQUESTED)
    }

    // Where a build stands, from both feeds' snapshots: how much further back
    // the held feed still has to go (negative once past its target) and the
    // window position it belongs at now. Null while that can't be read.
    private class BuildProgress(val needMs: Long, val resumeTargetMs: Long, val resumeClockMs: Long)

    private fun buildProgress(build: DelayBuild, heldValue: NativeTimingSnapshot, peerValue: NativeTimingSnapshot,
        movementMs: Long = build.movementMs): BuildProgress? {
        val position = heldValue.positionMs ?: return null
        val heldClock = heldValue.contentTimeMs ?: return null
        val peerClock = (if (build.peerLive) peerValue.contentTimeMs else peerValue.positionMs) ?: return null
        return try {
            val peerAdvance = Math.subtractExact(peerClock, build.peerClockMs)
            val heldAdvance = Math.subtractExact(heldClock, build.heldClockMs)
            val required = Math.addExact(Math.addExact(build.heldClockMs, movementMs), peerAdvance)
            val movement = Math.subtractExact(required, heldClock)
            // A seek to the frame it is on would be dropped by the player, and
            // it is the seek that resets Media3's live target.
            val step = if (movement == 0L) 1L else movement
            BuildProgress(nativeDelayBuildNeedMs(movementMs, peerAdvance, heldAdvance), Math.addExact(position, step),
                Math.addExact(heldClock, step))
        } catch (_: ArithmeticException) { null }
    }

    // A Timing step while a delay builds adjusts it (user, 2026-11-25). A step
    // that holds the feed further behind adds to the build, up to the delay
    // limit; an opposite step reduces it, and once nothing is left to build
    // the held feed resumes at the net requested offset.
    fun adjustDelayBuild(deltaMs: Long): NativeDelayBuildAdjustment {
        val build = delayPending ?: return NativeDelayBuildAdjustment.NOT_BUILDING
        if (closed || build.resumeClockMs != null || deltaMs == 0L) return NativeDelayBuildAdjustment.UNAVAILABLE
        val total: Long
        val movement: Long
        try {
            total = Math.addExact(build.deltaMs, deltaMs)
            movement = if (build.side == NativeMixedSide.A) total else Math.negateExact(total)
        } catch (_: ArithmeticException) { return NativeDelayBuildAdjustment.UNAVAILABLE }
        val heldValue = snapshot(build.held)
        val peerValue = snapshot(build.peer)
        val progress = if (heldValue != null && peerValue != null) buildProgress(build, heldValue, peerValue, movement) else null
        val need = progress?.needMs ?: try { Math.subtractExact(build.remainingMs,
            if (build.side == NativeMixedSide.A) deltaMs else Math.negateExact(deltaMs)) }
            catch (_: ArithmeticException) { return NativeDelayBuildAdjustment.UNAVAILABLE }
        val further = movement < build.movementMs
        if (further && heldValue != null && need > 0 && nativeLiveDelayExceeded(heldValue, -need)) {
            status = LIVE_DELAY_LIMIT_MESSAGE
            return NativeDelayBuildAdjustment.DELAY_LIMITED
        }
        if (need <= 0) {
            // Resuming now: its target must be in the held feed's window.
            val target = progress?.resumeTargetMs
            val plan = if (heldValue != null && target != null) nativeSeekPlan(heldValue, target) else null
            if (plan == null || plan.outcome != NativeSeekOutcome.REQUESTED || plan.targetMs != target) {
                status = "${build.side} can't move that far forward; the delay is still building."
                return NativeDelayBuildAdjustment.UNAVAILABLE
            }
        }
        build.movementMs = movement
        build.deltaMs = total
        build.remainingMs = need.coerceAtLeast(0)
        status = if (need > 0) "Building delay for ${build.side}: ${formatDelayBuildRemaining(need)} to go."
            else "Building delay for ${build.side}: resuming."
        if (need <= 0) poll()
        return if (further) NativeDelayBuildAdjustment.ADDED else NativeDelayBuildAdjustment.REDUCED
    }

    private fun peerSide(build: DelayBuild) = if (build.side == NativeMixedSide.A) NativeMixedSide.B else NativeMixedSide.A

    // How far the held feed has fallen behind its previous relationship with
    // the other feed: what moving it forward by this would restore.
    private fun restoreShift(build: DelayBuild, held: NativeTimingSnapshot?, peer: NativeTimingSnapshot?): Long? {
        val heldClock = held?.contentTimeMs ?: return null
        val peerClock = (if (build.peerLive) peer?.contentTimeMs else peer?.positionMs) ?: return null
        return try {
            Math.subtractExact(Math.subtractExact(peerClock, build.peerClockMs), Math.subtractExact(heldClock, build.heldClockMs))
        } catch (_: ArithmeticException) { null }?.coerceAtLeast(0)
    }

    // The held feed's window position for its previous relationship; null
    // when it can't be known, or once it is already resuming at its target.
    private fun restoreTarget(build: DelayBuild): Long? {
        if (build.resumeClockMs != null) return null
        val held = snapshot(build.held)
        val shift = restoreShift(build, held, snapshot(build.peer)) ?: return null
        val position = held?.positionMs ?: return null
        return try { Math.addExact(position, shift) } catch (_: ArithmeticException) { null }
    }

    // The held feed as it would be had it kept playing, for a skip or Live
    // that cancels a build: that one seek both restores and moves it.
    private fun restoredView(build: DelayBuild, values: Map<NativeMixedSide, NativeTimingSnapshot?>): Map<NativeMixedSide, NativeTimingSnapshot?> {
        val held = values[build.side] ?: return values
        val shift = restoreShift(build, held, values[peerSide(build)]) ?: return values
        return try {
            values + (build.side to held.copy(positionMs = held.positionMs?.let { Math.addExact(it, shift) },
                bufferedMs = held.bufferedMs?.let { Math.addExact(it, shift) },
                liveOffsetMs = held.liveOffsetMs?.let { Math.subtractExact(it, shift) }))
        } catch (_: ArithmeticException) { values }
    }

    // Ends a build without applying it. A build already resuming at its
    // target is complete instead. Otherwise the held feed is sought to
    // `restore` (its previous relationship), which also resets its live
    // target offset; without that, the offset is unknown.
    private fun endDelayBuild(build: DelayBuild, restore: Long?, play: Boolean,
        message: (restored: Boolean) -> String): NativeDelayBuildEnd? {
        if (delayPending !== build) return null
        if (build.resumeClockMs != null) return completeDelayBuild(build)
        delayPending = null
        busy = false
        val restored = restore != null && run {
            val position = snapshot(build.held)?.positionMs
            if (position == restore) return@run true
            val accepted = try { build.held.seekToMs(restore) }
                catch (_: Exception) { NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) }
            accepted.outcome == NativeSeekOutcome.REQUESTED && accepted.targetMs == restore
        }
        if (!restored) requestedAdjustmentValid = false
        if (play) playing(build.held, true)
        return NativeDelayBuildEnd(build.side, completed = false, restored = restored, canceled = true,
            message = message(restored)).also { lastDelayBuildEnd = it }
    }

    private fun completeDelayBuild(build: DelayBuild): NativeDelayBuildEnd {
        delayPending = null
        busy = false
        try { requestedAdjustmentMs = Math.addExact(requestedAdjustmentMs, build.deltaMs) }
        catch (_: ArithmeticException) { requestedAdjustmentValid = false }
        recordSettlement(build.deltaMs)
        val message = "Delay built; ${build.side} resumed."
        status = message
        return NativeDelayBuildEnd(build.side, completed = true, restored = false, canceled = false, message = message)
            .also { lastDelayBuildEnd = it }
    }

    private fun failDelayBuild(build: DelayBuild, reason: String) {
        if (closed || delayPending !== build) return
        delayPending = null
        busy = false
        requestedAdjustmentValid = false
        val message = "Building delay stopped: $reason; both paused."
        lastDelayBuildEnd = NativeDelayBuildEnd(build.side, completed = false, restored = false, canceled = false,
            message = message)
        val held = pause()
        if (!closed) status = if (held) message else "Building delay stopped: $reason; pause refused; Stop required."
    }

    // A skip or Live that can't move cancels a build (#618). The held feed
    // returns to its previous relationship with the other feed and plays on.
    fun cancelDelayBuild(): NativeDelayBuildEnd? {
        val build = delayPending ?: return null
        if (closed) return null
        val restore = restoreTarget(build)
        generation++
        val ended = endDelayBuild(build, restore, play = true) { restored ->
            if (restored) "$DELAY_BUILD_CANCELED; the offset is unchanged." else "$DELAY_BUILD_CANCELED; the offset is unknown."
        } ?: return null
        status = ended.message
        return ended
    }

    private fun pollDelayBuild(build: DelayBuild) {
        fun current() = !closed && generation == build.generation && delayPending === build
        fun admitted() = current() && timingAdmitted(build.generation) && current()
        val heldName = build.side.name
        val peerName = peerSide(build).name
        if (!admitted()) { if (current()) failDelayBuild(build, "authorization lost"); return }
        val now = clockMs()
        val elapsed = try { Math.subtractExact(now, build.startedMs) } catch (_: ArithmeticException) { Long.MAX_VALUE }
        if (elapsed < 0) { failDelayBuild(build, "its clock moved backward"); return }
        val heldValue = snapshot(build.held)
        val peerValue = snapshot(build.peer)
        if (!admitted()) { if (current()) failDelayBuild(build, "authorization lost"); return }
        build.resumeClockMs?.let { target ->
            val since = now - build.resumeStartedMs
            if (since < 0 || since >= DELAY_BUILD_RESUME_LIMIT_MS) { failDelayBuild(build, "$heldName didn't resume"); return }
            val clock = heldValue?.contentTimeMs
            if (heldValue != null && heldValue.state == 3 && heldValue.playing && heldValue.playWhenReady &&
                !heldValue.playingAd && clock != null && clock >= target - 100) completeDelayBuild(build)
            return
        }
        val limit = try { Math.addExact((-build.movementMs).coerceAtLeast(0), DELAY_BUILD_SLACK_MS) }
            catch (_: ArithmeticException) { Long.MAX_VALUE }
        if (elapsed >= limit) { failDelayBuild(build, "$peerName didn't play on long enough"); return }
        if (heldValue == null || heldValue.playWhenReady || heldValue.playing || !(heldValue.live || heldValue.dynamic)) {
            failDelayBuild(build, "$heldName changed while held"); return
        }
        if (peerValue == null || !peerValue.playWhenReady || (peerValue.live || peerValue.dynamic) != build.peerLive) {
            failDelayBuild(build, "$peerName stopped playing"); return
        }
        val position = heldValue.positionMs
        // Nothing is judged while the held feed buffers, as right after its
        // jump back into recorded history: its position may not be settled.
        if (heldValue.state != 3 || heldValue.playingAd || peerValue.state != 3 || peerValue.playingAd) return
        if (position != null && position < 0) { reanchorDelayBuild(build, heldValue); return }
        if (heldValue.contentTimeMs == null || position == null ||
            (if (build.peerLive) peerValue.contentTimeMs else peerValue.positionMs) == null) return
        val progress = buildProgress(build, heldValue, peerValue)
            ?: run { failDelayBuild(build, "a playback clock is unavailable"); return }
        build.remainingMs = progress.needMs.coerceAtLeast(0)
        if (progress.needMs > 0) return
        // Enough has been built. Resume with a seek to where the held feed
        // belongs now, at most a poll past it, and play at once: the seek also
        // gives Media3's live speed control the built delay as its target, so
        // it does not play the feed faster to catch up (#618).
        val target = progress.resumeTargetMs
        val plan = nativeSeekPlan(heldValue, target)
        if (plan.outcome != NativeSeekOutcome.REQUESTED || plan.targetMs != target) {
            failDelayBuild(build, "$heldName's target left its window"); return
        }
        val accepted = try { build.held.seekToMs(target) }
            catch (_: Exception) { NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) }
        if (!current()) return
        if (accepted.outcome != NativeSeekOutcome.REQUESTED || accepted.targetMs != target ||
            !playing(build.held, true)) {
            if (current()) failDelayBuild(build, "$heldName's resume was refused")
            return
        }
        if (!current()) return
        build.resumeClockMs = progress.resumeClockMs
        build.resumeStartedMs = clockMs()
        build.remainingMs = 0
    }

    // The window slid past the held frame: a segment the recorder didn't cache
    // before it left, or the byte limit. Move the frame back inside; the
    // countdown grows by what was lost, so the offset is still exact.
    private fun reanchorDelayBuild(build: DelayBuild, heldValue: NativeTimingSnapshot) {
        val heldName = build.side.name
        val duration = heldValue.durationMs
        if (build.reanchors >= DELAY_BUILD_REANCHOR_LIMIT || duration == null || duration <= 0) {
            failDelayBuild(build, "$heldName's recorded history ran out"); return
        }
        val target = minOf(DELAY_BUILD_HOLD_MARGIN_MS, duration / 2)
        val plan = nativeSeekPlan(heldValue, target)
        val accepted = if (plan.outcome == NativeSeekOutcome.REQUESTED && plan.targetMs == target)
            try { build.held.seekToMs(target) } catch (_: Exception) { NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) }
            else NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        if (closed || delayPending !== build) return
        if (accepted.outcome != NativeSeekOutcome.REQUESTED || accepted.targetMs != target) {
            failDelayBuild(build, "$heldName's recorded history ran out"); return
        }
        build.reanchors++
        status = "$heldName's oldest history moved on; the delay takes a little longer."
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
        lastDelayBuildPlan = null
        // #618: a saved-offset restore, toggle or marked alignment that needs
        // more recorded history than the feed to hold back has builds it.
        if (initial.outcome != NativeSeekOutcome.REQUESTED && initial.outcome != NativeSeekOutcome.NO_CHANGE &&
            !initial.delayLimited) {
            val build = nativeDelayBuildPlan(sa, sb, deltaMs)
            lastDelayBuildPlan = build
            when (build.outcome) {
                NativeDelayBuildOutcome.BUILD -> return startDelayBuild(build)
                NativeDelayBuildOutcome.NOT_PLAYING -> {
                    status = DELAY_BUILD_NEEDS_PLAYBACK
                    return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
                }
                NativeDelayBuildOutcome.DELAY_LIMITED -> {
                    status = LIVE_DELAY_LIMIT_MESSAGE
                    return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
                }
                else -> Unit
            }
        }
        if (initial.outcome != NativeSeekOutcome.REQUESTED) {
            status = if (initial.outcome == NativeSeekOutcome.NO_CHANGE) "No adjustment needed for the marked event."
            else if (initial.delayLimited) LIVE_DELAY_LIMIT_MESSAGE
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

    // Skip/scrub (#655): hold both, seek both by one checked movement, wait
    // for both held targets, then resume if both were requested playing. The
    // relative offset and its history are unchanged. If either feed cannot
    // move, neither is sought; a refused second dispatch rolls the first back.
    // A live feed's target is confirmed on its content clock, since its
    // window can slide while the pair is held (#660).
    fun seekTogether(deltaMs: Long): NativePairSkipPlan = moveTogether { nativePairSkipPlan(it, deltaMs) }

    // Live (user, 2026-11-25): the same held transaction to the nearer live
    // point, keeping the offset. If a feed is outside its window and that
    // can't bring it back, each live feed goes to its own live point instead,
    // and the requested offset is no longer valid (#660).
    fun goLive(): NativePairSkipPlan = moveTogether(::nativeLivePlan)

    private fun moveTogether(planner: (Map<NativeMixedSide, NativeTimingSnapshot?>) -> NativePairSkipPlan): NativePairSkipPlan {
        lastSkipPlan = null
        if (closed) return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE)
        // A skip or Live cancels a building delay (#618). One already resuming
        // at its target is complete instead.
        delayPending?.takeIf { it.resumeClockMs != null }?.let(::completeDelayBuild)
        val build = delayPending
        if (busy && build == null) return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE)
        // The held feed plans from its previous relationship with the other
        // feed, so the one seek that moves it also restores it.
        fun view(values: Map<NativeMixedSide, NativeTimingSnapshot?>) =
            if (build == null) values else restoredView(build, values)
        val began = generation
        if (!timingAdmitted(began)) return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE)
        val sa = snapshot(a); val sb = snapshot(b)
        if (!timingAdmitted(began)) return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE)
        val pre = view(mapOf(NativeMixedSide.A to sa, NativeMixedSide.B to sb))
        val initial = planner(pre)
        lastSkipPlan = initial
        if (initial.outcome != NativeSeekOutcome.REQUESTED && initial.outcome != NativeSeekOutcome.CLAMPED) {
            if (build != null) cancelDelayBuild()
            return initial
        }
        if (build == null) return moveHeldTogether(planner, null, pre, initial, began) {}
        delayPending = null
        busy = false
        var restored = false
        try {
            return moveHeldTogether(planner, build, pre, initial, began) { restored = it }
        } finally {
            if (!restored) requestedAdjustmentValid = false
            lastDelayBuildEnd = NativeDelayBuildEnd(build.side, completed = false, restored = restored,
                canceled = true, message = "$DELAY_BUILD_CANCELED.")
        }
    }

    private fun moveHeldTogether(planner: (Map<NativeMixedSide, NativeTimingSnapshot?>) -> NativePairSkipPlan,
        build: DelayBuild?, pre: Map<NativeMixedSide, NativeTimingSnapshot?>, initial: NativePairSkipPlan, began: Long,
        restored: (Boolean) -> Unit): NativePairSkipPlan {
        fun view(values: Map<NativeMixedSide, NativeTimingSnapshot?>) =
            if (build == null) values else restoredView(build, values)
        val before = if (build != null) "$DELAY_BUILD_CANCELED." else status
        // A building delay means the pair was playing.
        val resume = build != null ||
            pre[NativeMixedSide.A]?.playWhenReady == true && pre[NativeMixedSide.B]?.playWhenReady == true
        val expected = began + 1
        if (!pause() || !timingAdmitted(expected)) return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE)
        fun current() = !closed && generation == expected && timingAdmitted(expected)
        fun refuse(message: String): NativePairSkipPlan {
            if (!closed && generation == expected) { pause(); status = message }
            return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        // Nothing has been sought yet: name the reason and let playback go on.
        // A canceled build's held feed is restored on its own first.
        fun refuseUnmoved(plan: NativePairSkipPlan, reason: String): NativePairSkipPlan {
            if (closed || generation != expected) return plan.copy(reason = reason)
            val resumed = if (build != null) resumeCanceledBuild(build, restored) else resume && play()
            val prefix = if (build != null) "$DELAY_BUILD_CANCELED. " else ""
            status = prefix + if (resumed) "$reason; neither feed moved." else "$reason; neither feed moved; both paused."
            return plan.copy(reason = reason)
        }
        val freshA = snapshot(a); val freshB = snapshot(b)
        if (!current()) return refuse("Skip authorization lost; both paused.")
        val held = view(mapOf(NativeMixedSide.A to freshA, NativeMixedSide.B to freshB))
        held.entries.firstOrNull { !heldReady(it.value) }?.let { (side, _) ->
            return refuseUnmoved(NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE, blockedBy = side),
                "$side wasn't ready once held")
        }
        // Recheck the held feeds with the same plan. A playing feed's position,
        // and a live point taken from the continuous live offset, advance
        // between the two reads, so targets agree within a small tolerance;
        // the fresh, held targets are the ones dispatched (#660).
        val fresh = planner(held)
        if (fresh.outcome != NativeSeekOutcome.REQUESTED && fresh.outcome != NativeSeekOutcome.CLAMPED)
            return refuseUnmoved(fresh, heldRefusalReason(fresh))
        // Name the feed whose own move changed; a shared move changes for both.
        val drifted = skipPlanDrift(initial, fresh)?.let { shared ->
            NativeMixedSide.entries.firstOrNull { side ->
                skipPlanDrift(planner(mapOf(side to pre[side])), planner(mapOf(side to held[side]))) != null
            } ?: shared
        }
        if (drifted != null) return refuseUnmoved(NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE, blockedBy = drifted),
            "$drifted changed while holding both feeds")
        val wasValid = requestedAdjustmentValid
        busy = true
        lastTimingAttempt = Any()
        val dispatched = mutableListOf<NativeSkipTarget>()
        for (target in fresh.targets) {
            if (!current()) return refuse("Skip authorization lost; both paused.")
            // Until both seeks are accepted, one feed may have moved alone.
            requestedAdjustmentValid = false
            dispatched += target
            val accepted = try { member(target.side).seekToMs(target.targetMs) }
                catch (_: Exception) { NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) }
            if (!current()) return refuse("Skip authorization lost; both paused.")
            if (accepted.outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE) ||
                accepted.targetMs != target.targetMs) {
                // An uncertain reply may still have moved this feed.
                rollBack(dispatched, wasValid)
                return refuse("Skip refused by ${target.side}; both paused.")
            }
        }
        // Feeds moved by different amounts no longer hold the requested offset.
        requestedAdjustmentValid = wasValid && fresh.keepsOffset
        restored(true)
        val started = clockMs()
        if (!current()) return refuse("Skip authorization lost; both paused.")
        skipPending = SkipPending(fresh.targets, resume, started, expected,
            minOf(100L, fresh.targets.minOf { kotlin.math.abs(it.targetMs - it.fromMs) } / 2), before)
        // Keep the previous status: a skip is not a status change to announce.
        status = before
        return fresh
    }

    // A skip or Live that canceled a build but moved neither feed: restore
    // the held feed's previous relationship and play both on.
    private fun resumeCanceledBuild(build: DelayBuild, restored: (Boolean) -> Unit): Boolean {
        val restore = restoreTarget(build)
        val position = snapshot(build.held)?.positionMs
        val moved = restore != null && (position == restore || run {
            val accepted = try { build.held.seekToMs(restore) }
                catch (_: Exception) { NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) }
            accepted.outcome == NativeSeekOutcome.REQUESTED && accepted.targetMs == restore
        })
        restored(moved)
        val focused = try { acquireFocus() } catch (_: Exception) { false }
        return focused && playing(a, true) && playing(b, true)
    }

    private fun heldRefusalReason(plan: NativePairSkipPlan): String {
        val side = plan.blockedBy ?: plan.limitedBy
        val subject = side?.name ?: "A feed"
        return when (plan.outcome) {
            NativeSeekOutcome.AD_BLOCKED -> "$subject started an advertisement while holding both feeds"
            NativeSeekOutcome.UNSUPPORTED -> "$subject can no longer skip"
            NativeSeekOutcome.OUTSIDE_WINDOW -> "$subject is outside its window"
            NativeSeekOutcome.NO_CHANGE -> if (plan.limitedAtLive) "$subject is live" else "$subject can't move any further"
            else -> "$subject wasn't ready once held"
        }
    }

    // Return already moved feeds to where they were. If that is refused, the
    // requested offset history is unknown.
    private fun rollBack(moved: List<NativeSkipTarget>, wasValid: Boolean) {
        var restored = true
        for (target in moved) {
            val accepted = try { member(target.side).seekToMs(target.fromMs) }
                catch (_: Exception) { NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) }
            if (accepted.outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE) ||
                accepted.targetMs != target.fromMs) restored = false
        }
        requestedAdjustmentValid = wasValid && restored
    }

    val pendingSeekDiagnostics: List<NativePendingSeekDiagnostic> get() {
        skipPending?.let { skip ->
            val elapsed = try { Math.subtractExact(clockMs(), skip.startedMs) } catch (_: ArithmeticException) { Long.MAX_VALUE }
            return skip.targets.map { target ->
                val value = snapshot(member(target.side))
                NativePendingSeekDiagnostic(target.side, target.targetClockMs,
                    if (target.liveClock) value?.contentTimeMs else value?.positionMs, target.liveClock,
                    value != null && (value.live || value.dynamic) == target.liveClock, value?.state, value?.playing,
                    value?.playWhenReady, value?.playingAd, skip.toleranceMs, elapsed)
            }
        }
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
    // Back to live is offered while a delay builds, and cancels it (#618).
    fun catchUpPlan(side: NativeMixedSide): NativeSeekPlan =
        if (closed || busy && delayPending == null) NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) else recovery.plan(member(side))

    fun catchUp(side: NativeMixedSide): NativeSeekPlan {
        // Back to live cancels a building delay (#618). It ends the pair's
        // offset anyway, so the held feed is not restored first: a restoring
        // seek would leave it buffering, which Back to live would refuse. The
        // pair was playing, so it resumes.
        var canceled: NativeDelayBuildEnd? = null
        delayPending?.takeIf { !closed }?.let { build ->
            generation++
            canceled = endDelayBuild(build, null, play = false) { "$DELAY_BUILD_CANCELED." }?.takeIf { it.canceled }
        }
        if (closed || busy) {
            if (!closed) status = "Catch-up refused: wait for the current seek check."
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        val began = generation
        if (!timingAdmitted(began)) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        val sa = snapshot(a); val sb = snapshot(b)
        if (!timingAdmitted(began)) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        val resume = canceled != null || sa?.playing == true && sa.playWhenReady && sb?.playing == true && sb.playWhenReady
        // Keep reentrant timing commands out while recovery performs its own
        // hold/revalidation. Explicit Pause still cancels this generation.
        busy = true
        val result = recovery.start(member(side))
        canceled?.let { ended ->
            // Nothing was held: the canceled feed plays on beside the other.
            if (result.state == NativeLiveRecoveryStartState.PREFLIGHT_REFUSED && !closed) playing(member(ended.side), true)
        }
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
        canceled?.let { if (!closed && generation == began) status = "${it.message} $status" }
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
        delayPending?.let { build ->
            pollDelayBuild(build)
            return
        }
        skipPending?.let { transaction ->
            pollSkip(transaction)
            return
        }
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
                timingTargetProblem(timingTargetProof(target), values[target.side],
                    allowBufferedExpired = transaction.resume, label = "Marked target")
            }
            if (targetProblem != null) {
                if (current()) { pause(); status = "$targetProblem; both paused." }
                return
            }
            status = "All marked target clocks observed held; no frame/audio precision claim."
            if (transaction.resume) resumeTiming(transaction.generation, transaction.targets.associate { it.side to it.liveClock },
                transaction.continuation, "Marked alignment", ::current,
                TimingResumeProof(transaction.targets.map(::timingTargetProof), transaction.startedMs, "Marked target"))
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
            transaction.continuation, "Timing", ::current,
            TimingResumeProof(listOf(TimingTargetProof(transaction.side, member(transaction.side),
                transaction.targetClockMs, transaction.liveClock, transaction.settlementToleranceMs)),
                transaction.startedMs, "Timing target"))
        if (current()) { recordSettlement(transaction.deltaMs); pending = null; busy = false }
    }

    private fun pollSkip(transaction: SkipPending) {
        fun current() = !closed && generation == transaction.generation && skipPending === transaction
        fun admitted() = current() && timingAdmitted(transaction.generation) && current()
        fun elapsed() = try { Math.subtractExact(clockMs(), transaction.startedMs) }
            catch (_: ArithmeticException) { Long.MAX_VALUE }
        val first = elapsed()
        if (!admitted() || first < 0 || first >= 8_000) {
            if (current()) { pause(); status = "Skip check timed out or authorization lost; both paused." }
            return
        }
        val values = NativeMixedSide.entries.associateWith { snapshot(member(it)) }
        if (!admitted()) { if (current()) { pause(); status = "Skip authorization lost; both paused." }; return }
        val afterRead = elapsed()
        if (afterRead < first || afterRead >= 8_000) {
            if (current()) { pause(); status = "Skip check timed out; both paused." }
            return
        }
        if (values.values.any { !heldReady(it) }) return
        if (transaction.targets.any { target ->
            val value = values[target.side]
            val clock = if (target.liveClock) value?.contentTimeMs else value?.positionMs
            value == null || (value.live || value.dynamic) != target.liveClock || clock == null ||
                kotlin.math.abs(clock - target.targetClockMs) > transaction.toleranceMs
        }) return
        if (!admitted()) return
        if (transaction.resume) resumeTiming(transaction.generation, transaction.targets.associate { it.side to it.liveClock },
            null, "Skip", ::current, TimingResumeProof(transaction.targets.map {
                TimingTargetProof(it.side, member(it.side), it.targetClockMs, it.liveClock, transaction.toleranceMs)
            }, transaction.startedMs, "Skip target"), successStatus = transaction.statusBefore)
        else status = transaction.statusBefore
        if (current()) { skipPending = null; busy = false }
    }

    private fun timingTargetProof(target: NativeAlignmentTarget) = TimingTargetProof(target.side, member(target.side),
        target.targetClockMs, target.liveClock, minOf(100L, kotlin.math.abs(target.movementMs) / 2))

    private fun timingTargetProblem(target: TimingTargetProof, value: NativeTimingSnapshot?,
        allowBufferedExpired: Boolean, label: String): String? {
        if (target.member !== member(target.side)) return "$label session changed"
        if (!heldReady(value) || value == null) return "$label is no longer held READY outside advertisements"
        if ((value.live || value.dynamic) != target.liveClock) return "$label clock kind changed"
        val clock = if (target.liveClock) value.contentTimeMs else value.positionMs
        val difference = if (clock == null || clock < 0) null else try {
            Math.subtractExact(clock, target.targetClockMs)
        } catch (_: ArithmeticException) { null }
        if (difference == null || difference !in -target.toleranceMs..target.toleranceMs) return "$label clock moved after confirmation"
        val position = value.positionMs ?: return "$label position is unavailable"
        val duration = value.durationMs ?: return "$label playback window is unavailable"
        if (duration <= 0) return "$label playback window is unavailable"
        if (position in 0..duration) return null
        if (position < 0 && target.liveClock) {
            if (!allowBufferedExpired) return "$label expired while playback was paused"
            if (value.bufferedMs?.let { it > position } == true) return null
            return "$label expired and its buffered continuation is unavailable"
        }
        return "$label is beyond its playback window"
    }

    private fun resumeTiming(expected: Long, targeted: Map<NativeMixedSide, Boolean>, continuation: BufferedContinuation?,
        label: String, current: () -> Boolean, targetProof: TimingResumeProof? = null, successStatus: String? = null) {
        var targetFailure = "$label authorization changed"
        fun admitted(): Boolean {
            if (!current() || !timingAdmitted(expected) || !current()) {
                targetFailure = "$label authorization changed"; return false
            }
            if (targetProof != null) {
                val elapsed = try { Math.subtractExact(clockMs(), targetProof.startedMs) }
                    catch (_: ArithmeticException) { Long.MAX_VALUE }
                if (elapsed < 0 || elapsed >= 8_000) {
                    targetFailure = "$label resume timed out"; return false
                }
            }
            return targetProof == null || current() && timingAdmitted(expected) && current()
        }
        fun fail(default: String) {
            if (current()) {
                val held = pause()
                val cause = if (targetProof != null) targetFailure else default.removeSuffix("; both paused.")
                status = if (held) "$cause; both paused." else "$cause; pause refused; Stop required."
            }
        }
        fun playable(side: NativeMixedSide, value: NativeTimingSnapshot?): Boolean {
            val proof = targetProof?.targets?.singleOrNull { it.side == side }
            if (proof != null) {
                val problem = timingTargetProblem(proof, value, allowBufferedExpired = true, label = targetProof.targetLabel)
                if (problem != null) { targetFailure = problem; return false }
                return true
            }
            if (!heldReady(value) || value == null) {
                if (targetProof != null) targetFailure = "$label untouched feed is no longer held READY"
                return false
            }
            val position = value.positionMs
            val duration = value.durationMs
            val liveClock = value.live || value.dynamic
            val inWindow = position != null && duration != null && duration > 0 && position in 0..duration
            if (side in targeted) return targeted[side] == liveClock && inWindow
            if (continuation?.side == side && !liveClock) {
                if (targetProof != null) targetFailure = "$label untouched feed clock kind changed"
                return false
            }
            if (!liveClock || inWindow) return true
            return canContinueBuffered(continuation, side, value).also {
                if (!it) targetFailure = "$label untouched feed cannot continue its buffered point"
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
            if (!focused) targetFailure = "$label audio focus denied"
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
                if (!accepted) targetFailure = "$label playback request was refused"
                fail("$label resume denied; both paused."); return
            }
        }
        if (current()) status = successStatus ?: "$label playback requested; no synchronization lock."
    }

    // A presentation borrowing its sessions may end coordination without ending
    // either session or shared audio focus. Cancel automatic seek restoration;
    // deliberately preserve each member's current transport state.
    fun detach() {
        if (closed) return
        generation++
        delayPending?.let { build ->
            lastDelayBuildEnd = NativeDelayBuildEnd(build.side, completed = false, restored = false, canceled = true,
                message = "$DELAY_BUILD_CANCELED; pair coordination ended.")
        }
        delayPending = null
        alignmentPending = null
        recoveryPending = null
        pending = null
        skipPending = null
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
