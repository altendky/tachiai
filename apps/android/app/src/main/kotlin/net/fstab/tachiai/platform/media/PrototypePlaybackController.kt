package net.fstab.tachiai.platform.media

// Presentation transport over independently prepared slots. Sessions and shared
// focus are borrowed: only the Activity may end those lifetimes. Attaching a
// member means its provider-page pause/startup checks have already succeeded.
internal class PrototypePlaybackController(
    private val acquireFocus: () -> Boolean,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    private var a: NativePairMember? = null
    private var b: NativePairMember? = null
    private var closed = false
    private var message = "No playable feeds."
    var pair: NativeMixedPair? = null
        private set
    val status: String get() = pair?.status ?: message
    val busy: Boolean get() = pair?.busy == true
    val playingRequested: Boolean get() = members().any { snapshot(it)?.playWhenReady == true }

    fun member(side: NativeMixedSide): NativePairMember? = if (side == NativeMixedSide.A) a else b
    private fun members(): List<NativePairMember> = listOfNotNull(a, b)
    private fun snapshot(member: NativePairMember): NativeTimingSnapshot? =
        try { member.timingSnapshot() } catch (_: Exception) { null }
    private fun playing(member: NativePairMember, requested: Boolean): Boolean =
        try { member.setTimingPlaying(requested) } catch (_: Exception) { false }

    fun setMember(side: NativeMixedSide, member: NativePairMember?) {
        if (closed || this.member(side) === member) return
        // Do not close the old pair: it owns members in historical callers.
        // Detach cancels pending restores without pausing the surviving feed.
        pair?.detach()
        pair = null
        this.member(side)?.let { playing(it, false) }
        if (side == NativeMixedSide.A) a = member else b = member
        val preparedA = a
        val preparedB = b
        if (preparedA != null && preparedB != null) {
            pair = NativeMixedPair(preparedA, preparedB, acquireFocus, {}, clockMs,
                requireCurrentLiveWindowOnPlay = true)
        }
        message = when {
            members().isEmpty() -> "No playable feeds."
            playingRequested -> "One feed playing; the other feed is unavailable."
            else -> "One feed prepared paused; press Play."
        }
    }

    fun play(): Boolean {
        if (closed) return false
        pair?.let { return it.play() }
        val selected = members().singleOrNull()
        if (selected == null) { message = "No playable feeds."; return false }
        val value = snapshot(selected)
        if (value == null || value.state != 3 || value.playingAd) {
            message = "The available feed must be READY and outside an advertisement before Play."
            return false
        }
        if (value.live || value.dynamic) {
            val position = value.positionMs
            val duration = value.durationMs
            if (position == null || duration == null || duration <= 0 || position !in 0..duration) {
                val held = pause()
                message = if (held) "Play refused: live point outside/unknown current window."
                    else "Play refused: stale live point and pause refused; return to sources."
                return false
            }
        }
        val focused = try { acquireFocus() } catch (_: Exception) { false }
        if (!focused) { pause(); message = "Audio focus denied; available feed paused."; return false }
        if (!playing(selected, true)) {
            val held = pause()
            message = if (held) "Play refused; available feed paused."
                else "Play refused and pause refused; return to sources."
            return false
        }
        message = "One feed playing; the other feed is unavailable."
        return true
    }

    fun pause(): Boolean {
        if (closed) return false
        pair?.let { return it.pause() }
        var accepted = true
        members().forEach { if (!playing(it, false)) accepted = false }
        message = if (accepted) "Paused." else "Pause refused; return to sources."
        return accepted
    }

    fun focusLost() {
        if (closed) return
        pair?.let { it.focusLost(); return }
        val held = pause()
        message = if (held) "Audio focus lost; explicit Play required."
            else "Audio focus lost; pause refused; return to sources."
    }

    fun poll() { if (!closed) pair?.poll() }

    fun setVolume(side: NativeMixedSide, volume: Float): Boolean {
        if (closed || !volume.isFinite() || volume !in 0f..1f) return false
        val selected = member(side) ?: return true
        return try { selected.setVolume(volume) } catch (_: Exception) { false }
    }

    fun shiftRelative(deltaMs: Long): NativeSeekPlan {
        if (!closed) pair?.let { return it.shiftRelative(deltaMs) }
        message = "Relative timing requires two playable feeds."
        return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
    }

    fun catchUp(side: NativeMixedSide): NativeSeekPlan {
        if (!closed) pair?.let { return it.catchUp(side) }
        message = "Joint catch-up requires two playable feeds."
        return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
    }

    override fun close() {
        if (closed) return
        pause()
        pair?.detach()
        pair = null
        a = null
        b = null
        closed = true
        message = "Stopped."
    }
}
