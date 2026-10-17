package net.fstab.tachiai.platform.media

// Presentation transport over independently prepared slots. Sessions and shared
// focus are borrowed: only the Activity may end those lifetimes. Attaching a
// member means its provider-page pause/startup checks have already succeeded.
internal class PrototypePlaybackController(
    private val acquireFocus: () -> Boolean,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val admission: (NativePairMember) -> Boolean = { true },
    private val playAllowed: () -> Boolean = { true },
) : AutoCloseable {
    private var a: NativePairMember? = null
    private var b: NativePairMember? = null
    private var closed = false
    private var message = "No playable feeds."
    private var recovery: NativeLiveRecovery? = null
    private var coordinationLost = false
    var pair: NativeMixedPair? = null
        private set
    val status: String get() = pair?.status ?: message
    val busy: Boolean get() = pair?.busy == true || recovery?.busy == true
    val playingRequested: Boolean get() = members().any { snapshot(it)?.playWhenReady == true }

    fun member(side: NativeMixedSide): NativePairMember? = if (side == NativeMixedSide.A) a else b
    private fun members(): List<NativePairMember> = listOfNotNull(a, b)
    private fun snapshot(member: NativePairMember): NativeTimingSnapshot? =
        try { member.timingSnapshot() } catch (_: Exception) { null }
    private fun playing(member: NativePairMember, requested: Boolean): Boolean =
        try { member.setTimingPlaying(requested) } catch (_: Exception) { false }

    fun setMember(side: NativeMixedSide, member: NativePairMember?) {
        if (closed || this.member(side) === member) return
        recovery?.cancel()
        recovery = null
        // Do not close the old pair: it owns members in historical callers.
        // Detach cancels pending restores without pausing the surviving feed.
        if (pair != null) coordinationLost = true
        pair?.detach()
        pair = null
        this.member(side)?.let { playing(it, false) }
        if (side == NativeMixedSide.A) a = member else b = member
        val preparedA = a
        val preparedB = b
        if (preparedA != null && preparedB != null) {
            pair = NativeMixedPair(preparedA, preparedB, {
                val focused = acquireFocus()
                focused && a === preparedA && b === preparedB && allowed() &&
                    admitted(preparedA) && admitted(preparedB)
            }, {}, clockMs,
                requireCurrentLiveWindowOnPlay = true,
                initialAdjustmentValid = !coordinationLost,
                admission = { !closed && members().any { current -> current === it } && admission(it) })
        } else members().singleOrNull()?.let { selected ->
            recovery = NativeLiveRecovery(listOf(selected), clockMs,
                { !closed && members().any { current -> current === it } && admission(it) })
        }
        message = when {
            members().isEmpty() -> "No playable feeds."
            playingRequested -> "One feed playing; the other feed is unavailable."
            else -> "One feed prepared paused; press Play."
        }
    }

    fun play(onStaleLive: ((NativeMixedSide, NativePairMember) -> Boolean)? = null): Boolean {
        if (closed) return false
        // Recheck ownership at the transport boundary, including after retiring
        // a stale partner. Session destruction remains the owner's responsibility.
        if (!allowed() || members().any { !admitted(it) }) { pause(); return false }
        val original = NativeMixedSide.entries.associateWith(::member)
        pair?.let { current ->
            return current.play(onStaleLive?.let { owner -> { sides ->
                pair === current && isolateStale(sides, original, owner)
            } })
        }
        if (busy && !pause()) return false
        val selected = members().singleOrNull()
        if (selected == null) { message = "No playable feeds."; return false }
        val side = if (a === selected) NativeMixedSide.A else NativeMixedSide.B
        val value = snapshot(selected)
        if (value == null || value.state != 3 || value.playingAd) {
            message = "The available feed must be READY and outside an advertisement before Play."
            return false
        }
        if (value.live || value.dynamic) {
            val position = value.positionMs
            val duration = value.durationMs
            if (position == null || duration == null || duration <= 0 || position !in 0..duration) {
                if (onStaleLive != null) return isolateStale(
                    setOf(side), original, onStaleLive)
                val held = pause()
                message = if (held) "Play refused: live point outside/unknown current window."
                    else "Play refused: stale live point and pause refused; return to sources."
                return false
            }
        }
        val focused = try { acquireFocus() } catch (_: Exception) { false }
        if (!focused || member(side) !== selected ||
            !allowed() || !admitted(selected)) {
            pause(); message = "Audio focus or playback admission denied; available feed paused."; return false
        }
        if (!playing(selected, true)) {
            val held = pause()
            message = if (held) "Play refused; available feed paused."
                else "Play refused and pause refused; return to sources."
            return false
        }
        message = "One feed playing; the other feed is unavailable."
        return true
    }

    private fun admitted(member: NativePairMember): Boolean =
        try { !closed && admission(member) } catch (_: Exception) { false }
    private fun allowed(): Boolean = try { !closed && playAllowed() } catch (_: Exception) { false }

    private fun isolateStale(sides: Set<NativeMixedSide>, original: Map<NativeMixedSide, NativePairMember?>,
        owner: (NativeMixedSide, NativePairMember) -> Boolean): Boolean {
        if (closed || NativeMixedSide.entries.any { member(it) !== original[it] }) return false
        val stale = sides.mapNotNull { side -> original[side]?.let { side to it } }
        for ((side, selected) in stale) {
            if (!allowed() || member(side) !== selected || !admitted(selected)) { pause(); return false }
            val continued = try { owner(side, selected) } catch (_: Exception) { false }
            if (!continued || !allowed() || member(side) != null || NativeMixedSide.entries.any {
                it !in sides && member(it) !== original[it]
            }) {
                if (!closed) pause()
                return false
            }
        }
        if (sides.any { member(it) != null }) { pause(); return false }
        return !closed && members().isNotEmpty() && play(owner)
    }

    fun pause(): Boolean {
        if (closed) return false
        pair?.let { return it.pause() }
        recovery?.cancel()
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

    fun poll() {
        if (closed) return
        pair?.let { it.poll(); return }
        val transaction = recovery ?: return
        val result = transaction.poll()
        if (closed || recovery !== transaction) return
        when (result.state) {
            NativeLiveRecoveryPollState.SETTLED -> message = "SDK READY in current live window; available feed paused. Explicit Play required; not exact live edge."
            NativeLiveRecoveryPollState.TIMED_OUT -> message = if (result.held == true) "Catch-up check timed out; available feed paused."
                else "Catch-up check timed out; pause refused; return to sources."
            NativeLiveRecoveryPollState.ADMISSION_LOST -> message = if (result.held == true) "Catch-up authorization lost; available feed paused."
                else "Catch-up authorization lost; pause refused; return to sources."
            else -> Unit
        }
    }

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

    fun catchUpPlan(side: NativeMixedSide): NativeSeekPlan {
        if (closed || busy) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        pair?.let { return it.catchUpPlan(side) }
        val selected = member(side) ?: return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        return recovery?.plan(selected) ?: NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
    }

    fun catchUp(side: NativeMixedSide): NativeSeekPlan {
        if (closed) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        pair?.let { return it.catchUp(side) }
        if (busy) {
            message = "Catch-up refused: wait for the current seek check."
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        val selected = member(side)
        val transaction = recovery
        if (selected == null || transaction == null) {
            message = "Catch-up requires an available live feed."
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        val result = transaction.start(selected)
        if (closed || recovery !== transaction || member(side) !== selected)
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        when (result.state) {
            NativeLiveRecoveryStartState.PREFLIGHT_REFUSED, NativeLiveRecoveryStartState.RECHECK_REFUSED ->
                message = "Catch-up not dispatched: ${result.plan.outcome}."
            NativeLiveRecoveryStartState.HOLD_REFUSED -> message = "Pause refused; return to sources."
            NativeLiveRecoveryStartState.DISPATCH_REFUSED -> message = if (result.held == true) "Catch-up refused; available feed paused."
                else "Catch-up refused; pause refused; return to sources."
            NativeLiveRecoveryStartState.STARTED -> message = "Catch-up requested; waiting for held live window (8 s maximum)."
        }
        return result.plan
    }

    override fun close() {
        if (closed) return
        pause()
        recovery?.cancel()
        recovery = null
        pair?.detach()
        pair = null
        a = null
        b = null
        closed = true
        message = "Stopped."
    }
}
