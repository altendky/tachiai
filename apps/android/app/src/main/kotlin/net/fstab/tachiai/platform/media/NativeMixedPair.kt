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
) : AutoCloseable {
    var status = "Prepared paused; press Play."
        private set
    // Positive = A requested later relative to B, not measured synchronization.
    var requestedAdjustmentMs = 0L
        private set
    var requestedAdjustmentValid = true
        private set
    var busy = false
        private set
    var cleanupFailed = false
        private set
    private var closed = false
    private data class Pending(
        val side: NativeMixedSide,
        val targetClockMs: Long,
        val liveClock: Boolean,
        val resume: Boolean,
        val startedMs: Long,
    )
    private var pending: Pending? = null
    private data class Recovery(val side: NativeMixedSide, val startedMs: Long)
    private var recovery: Recovery? = null
    var lastRelativePlan: NativeRelativeNudgePlan? = null
        private set

    private fun member(side: NativeMixedSide) = if (side == NativeMixedSide.A) a else b
    private fun snapshot(member: NativePairMember): NativeTimingSnapshot? =
        try { member.timingSnapshot() } catch (_: Exception) { null }
    private fun playing(member: NativePairMember, value: Boolean): Boolean =
        try { member.setTimingPlaying(value) } catch (_: Exception) { false }

    fun pause(): Boolean {
        if (closed) return false
        pending = null
        recovery = null
        busy = false
        val pa = playing(a, false)
        val pb = playing(b, false)
        status = if (pa && pb) "Paused." else "Pause refused; Stop required."
        return pa && pb
    }

    fun focusLost() {
        if (closed) return
        val paused = pause()
        status = if (paused) "Audio focus lost; explicit Play required."
            else "Audio focus lost; pause refused; Stop required."
    }

    fun play(): Boolean {
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
            val held = pause()
            status = if (held) "Play refused: live point outside/unknown current window; explicit catch-up required."
                else "Play refused: stale live point and pause refused; Stop required."
            return false
        }
        val focused = try { acquireFocus() } catch (_: Exception) { false }
        if (!focused) { pause(); status = "Audio focus denied; pause requested for both."; return false }
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
        val sa = snapshot(a)
        val sb = snapshot(b)
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
        if (!pause()) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        val accepted = try { member(side).seekToMs(target) }
            catch (_: Exception) { NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) }
        // A changed window must not silently change the requested transaction.
        if (accepted.outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE) ||
            accepted.targetMs != target) {
            pause(); status = "Seek refused or changed; pause requested for both."
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        requestedAdjustmentMs = adjustment
        pending = Pending(side, targetClock, liveClock, resume, clockMs())
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

    // Explicit recovery, never a fallback for an ordinary timing nudge. The
    // SDK chooses its current live default, not an exact or future live edge.
    fun catchUp(side: NativeMixedSide): NativeSeekPlan {
        if (closed || busy) {
            if (!closed) status = "Catch-up refused: wait for the current seek check."
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        val sa = snapshot(a)
        val sb = snapshot(b)
        val plan = when {
            sa == null || sb == null || sa.state != 3 || sb.state != 3 -> NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
            sa.playingAd || sb.playingAd -> NativeSeekPlan(NativeSeekOutcome.AD_BLOCKED)
            else -> nativeLiveDefaultPlan(if (side == NativeMixedSide.A) sa else sb)
        }
        if (plan.outcome != NativeSeekOutcome.REQUESTED) {
            status = "Catch-up not dispatched: ${plan.outcome}."
            return plan
        }
        if (!pause()) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        // Even an ambiguous dispatch failure could have moved the player. Keep
        // the old number as history only; later nudges cannot restore its anchor.
        requestedAdjustmentValid = false
        lastRelativePlan = null
        val accepted = try { member(side).seekLiveDefault() }
            catch (_: Exception) { NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) }
        if (accepted.outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE)) {
            val held = pause()
            status = if (held) "Catch-up refused; both paused; timing anchor invalid."
                else "Catch-up refused; pause refused; Stop required; timing anchor invalid."
            return accepted
        }
        recovery = Recovery(side, clockMs())
        busy = true
        status = "Catch-up requested; waiting for held live window (8 s maximum)."
        return accepted
    }

    fun poll() {
        if (closed) return
        recovery?.let { transaction ->
            val elapsed = clockMs() - transaction.startedMs
            if (elapsed < 0 || elapsed >= 8_000) {
                val held = pause()
                status = if (held) "Catch-up check timed out; both paused; timing anchor invalid."
                    else "Catch-up check timed out; pause refused; Stop required; timing anchor invalid."
                return
            }
            val sa = snapshot(a)
            val sb = snapshot(b)
            fun heldReady(value: NativeTimingSnapshot?) = value != null && value.state == 3 &&
                !value.playing && !value.playWhenReady && !value.playingAd
            if (!heldReady(sa) || !heldReady(sb)) return
            val selected = checkNotNull(if (transaction.side == NativeMixedSide.A) sa else sb)
            val position = selected.positionMs
            val duration = selected.durationMs
            if (!selected.live || position == null || duration == null || duration <= 0 ||
                position < 0 || position > duration) return
            recovery = null
            busy = false
            status = "SDK READY in current live window; both paused. Explicit Play required; not exact live edge."
            return
        }
        val transaction = pending ?: return
        val elapsed = clockMs() - transaction.startedMs
        if (elapsed < 0 || elapsed >= 8_000) {
            pause(); status = "Seek check timed out; pause requested for both."
            return
        }
        val sa = snapshot(a)
        val sb = snapshot(b)
        fun heldReady(value: NativeTimingSnapshot?) = value != null && value.state == 3 &&
            !value.playing && !value.playWhenReady && !value.playingAd
        if (!heldReady(sa) || !heldReady(sb)) return
        val selected = checkNotNull(if (transaction.side == NativeMixedSide.A) sa else sb)
        if ((selected.live || selected.dynamic) != transaction.liveClock) return
        val clock = if (transaction.liveClock) selected.contentTimeMs else selected.positionMs
        if (clock == null || clock < 0 ||
            kotlin.math.abs(clock - transaction.targetClockMs) > 100) return
        pending = null
        busy = false
        status = "Selected held target observed within 100 ms; no frame/audio precision claim."
        if (transaction.resume) play()
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
