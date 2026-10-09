package net.fstab.tachiai.platform.media

internal interface NativePairMember : AutoCloseable {
    fun timingSnapshot(): NativeTimingSnapshot?
    fun qualitySnapshot(): NativeQualitySnapshot? = null
    fun setQualityPreferences(preferences: NativeQualityPreferences): Boolean = false
    fun seekToMs(targetMs: Long): NativeSeekPlan
    fun seekLiveDefault(): NativeSeekPlan = NativeSeekPlan(NativeSeekOutcome.UNSUPPORTED)
    fun setTimingPlaying(playing: Boolean): Boolean
    fun setVolume(volume: Float): Boolean
}

// Provider-independent, foreground diagnostic transaction. No source/auth data.
internal class NativeReplayPair(
    val a: NativePairMember,
    val b: NativePairMember,
    private val acquireFocus: () -> Boolean,
    private val releaseFocus: () -> Unit,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    var status = "Prepared paused; press Play."
        private set
    var requestedOffsetMs = 0L
        private set
    var busy = false
        private set
    var cleanupFailed = false
        private set
    private var closed = false
    private var pending: NativePairPlan? = null
    private var startedMs = 0L
    private var resume = false

    private fun playing(member: NativePairMember, value: Boolean): Boolean =
        try { member.setTimingPlaying(value) } catch (_: Exception) { false }
    private fun seek(member: NativePairMember, target: Long): NativeSeekPlan =
        try { member.seekToMs(target) } catch (_: Exception) { NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) }

    fun pause() {
        if (closed) return
        pending = null
        busy = false
        resume = false
        val pa = playing(a, false)
        val pb = playing(b, false)
        status = if (pa && pb) "Paused." else "Pause refused; Stop required."
    }

    fun focusLost() {
        if (closed) return
        pause()
        status = "Audio focus lost; explicit Play required."
    }

    fun play(): Boolean {
        if (closed || busy) return false
        val sa = a.timingSnapshot()
        val sb = b.timingSnapshot()
        if (sa == null || sb == null || sa.state != 3 || sb.state != 3 || sa.playingAd || sb.playingAd) {
            status = "Both players must be READY before Play."
            return false
        }
        val focused = try { acquireFocus() } catch (_: Exception) { false }
        if (!focused) { pause(); status = "Audio focus denied; pause requested for both."; return false }
        val pa = playing(a, true)
        val pb = playing(b, true)
        if (!pa || !pb) { pause(); status = "Play refused; both remain paused."; return false }
        status = "Play requested; measured offset is not a synchronization lock."
        return true
    }

    fun align(offsetMs: Long, anchorMs: Long? = null): NativePairPlan {
        if (closed || busy) return NativePairPlan(NativePairOutcome.UNAVAILABLE)
        val sa = a.timingSnapshot()
        val sb = b.timingSnapshot()
        val plan = nativeReplayPairPlan(sa, sb, offsetMs, anchorMs)
        if (plan.outcome != NativePairOutcome.REQUESTED) { status = "Alignment refused: ${plan.outcome}"; return plan }
        val wasPlaying = sa?.playWhenReady == true && sb?.playWhenReady == true
        val heldA = playing(a, false)
        val heldB = playing(b, false)
        if (!heldA || !heldB) { pause(); status = "Hold refused; both remain paused."; return NativePairPlan(NativePairOutcome.UNAVAILABLE) }
        val pa = seek(a, checkNotNull(plan.aMs))
        val pb = seek(b, checkNotNull(plan.bMs))
        val accepted = setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE)
        if (pa.outcome !in accepted || pb.outcome !in accepted) {
            pause(); status = "Partial seek refused; both remain paused."
            return NativePairPlan(NativePairOutcome.UNAVAILABLE)
        }
        requestedOffsetMs = offsetMs
        pending = plan
        busy = true
        resume = wasPlaying
        startedMs = clockMs()
        status = "Holding both; waiting for target clocks (8 s maximum)."
        return plan
    }

    fun poll() {
        if (closed) return
        val plan = pending ?: return
        val elapsed = clockMs() - startedMs
        if (elapsed < 0 || elapsed >= 8_000) {
            pause(); status = "Seek check timed out; both remain paused."
        } else if (nativePairSettled(plan, a.timingSnapshot(), b.timingSnapshot())) {
            val shouldResume = resume
            pending = null
            busy = false
            resume = false
            status = "Held targets observed within 100 ms; no frame/audio precision claim."
            if (shouldResume) play()
        }
    }

    override fun close() {
        if (closed) return
        pause()
        closed = true
        // Attempt every cleanup even if one platform/member operation throws.
        for (cleanup in listOf<() -> Unit>({ a.close() }, { b.close() }, releaseFocus)) {
            try { cleanup() } catch (_: Exception) { cleanupFailed = true }
        }
        status = if (cleanupFailed) "Stopped; cleanup reported failure." else "Stopped."
    }
}
