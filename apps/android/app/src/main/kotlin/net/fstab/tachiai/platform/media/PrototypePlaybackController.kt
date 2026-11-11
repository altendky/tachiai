package net.fstab.tachiai.platform.media

// Presentation transport over independently prepared slots. Sessions and shared
// focus are borrowed: only the Activity may end those lifetimes. Attaching a
// member means its provider-page pause/startup checks have already succeeded.
internal class PrototypePlaybackController(
    private val acquireFocus: () -> Boolean,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val admission: (NativePairMember) -> Boolean = { true },
    private val playAllowed: () -> Boolean = { true },
    private val singleFeed: Boolean = false,
) : AutoCloseable {
    private var a: NativePairMember? = null
    private var b: NativePairMember? = null
    private var closed = false
    private var message = "No playable feeds."
    private var recovery: NativeLiveRecovery? = null
    private var transportGeneration = 0L
    private data class RecoveryResume(val side: NativeMixedSide, val member: NativePairMember, val generation: Long)
    private var recoveryResume: RecoveryResume? = null
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
        transportGeneration++
        recoveryResume = null
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
            pair = NativeMixedPair(preparedA, preparedB, acquireFocus, {}, clockMs,
                requireCurrentLiveWindowOnPlay = true,
                initialAdjustmentValid = !coordinationLost,
                admission = { !closed && members().any { current -> current === it } && admission(it) })
        } else members().singleOrNull()?.let { selected ->
            recovery = NativeLiveRecovery(listOf(selected), clockMs,
                { !closed && members().any { current -> current === it } && admission(it) })
        }
        message = when {
            members().isEmpty() -> "No playable feeds."
            playingRequested -> playingMessage()
            else -> if (singleFeed) "Feed prepared paused; press Play." else "One feed prepared paused; press Play."
        }
    }

    fun play(onStaleLive: ((NativeMixedSide, NativePairMember) -> Boolean)? = null): Boolean {
        if (closed) return false
        // Explicit Play supersedes a recovery restoration even after the SDK
        // seek has settled and stopped advertising busy.
        transportGeneration++
        recoveryResume = null
        // Recheck ownership at the transport boundary, including after retiring
        // a stale partner. Session destruction remains the owner's responsibility.
        if (!allowed() || members().any { !admitted(it) }) { pause(); return false }
        val original = NativeMixedSide.entries.associateWith(::member)
        pair?.let { current ->
            return current.play(playAdmission = {
                pair === current && NativeMixedSide.entries.all { member(it) === original[it] } &&
                    allowed() && members().all(::admitted)
            }, onStaleLive = onStaleLive?.let { owner -> { sides ->
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
        message = playingMessage()
        return true
    }

    private fun playingMessage() = if (singleFeed) "Feed playing." else "One feed playing; the other feed is unavailable."

    private fun admitted(member: NativePairMember): Boolean =
        try { !closed && admission(member) } catch (_: Exception) { false }
    private fun allowed(): Boolean = try { !closed && playAllowed() } catch (_: Exception) { false }

    // Join only a newly prepared slot. The healthy peer keeps its exact SDK
    // transport and buffered point, even when its live window has moved past
    // that point. This is not permission for ordinary Play on a stale held feed.
    fun rejoin(side: NativeMixedSide, replacement: NativePairMember, sibling: NativePairMember): Boolean {
        val siblingSide = if (side == NativeMixedSide.A) NativeMixedSide.B else NativeMixedSide.A
        if (closed || busy || replacement === sibling || member(side) !== replacement || member(siblingSide) !== sibling)
            return false
        val originalPair = pair
        val began = ++transportGeneration
        recoveryResume = null
        recovery?.cancel()
        fun current() = !closed && transportGeneration == began && pair === originalPair && !busy &&
            member(side) === replacement && member(siblingSide) === sibling
        fun admittedNow() = current() && allowed() && admitted(replacement) && admitted(sibling) && current()
        fun ready(): Boolean {
            if (!admittedNow()) return false
            val target = snapshot(replacement)
            val peer = snapshot(sibling)
            if (!admittedNow() || target == null || peer == null || target.state != 3 || target.playingAd ||
                peer.state != 3 || peer.playingAd || !peer.playing || !peer.playWhenReady) return false
            if (target.live || target.dynamic) {
                val position = target.positionMs
                val duration = target.durationMs
                if (position == null || duration == null || duration <= 0 || position !in 0..duration) return false
            }
            return current()
        }
        if (!ready()) return false
        val focused = try { acquireFocus() } catch (_: Exception) { false }
        if (!focused || !ready()) return false
        // No paired Play/Pause fallback: even a target refusal cannot interrupt
        // the sibling that the user has kept watching during this preparation.
        val accepted = playing(replacement, true)
        if (!accepted || !admittedNow()) {
            // A newer command can retain this SDK member. Roll back only while
            // this exact rejoin still owns transport, never a newer Play/seek.
            if (current()) playing(replacement, false)
            return false
        }
        message = "Retried feed playback requested; existing feed left playing."
        return true
    }

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
        transportGeneration++
        recoveryResume = null
        pair?.let { return it.pause() }
        recovery?.cancel()
        var accepted = true
        members().forEach { if (!playing(it, false)) accepted = false }
        message = if (accepted) "Paused." else "Pause refused; return to sources."
        return accepted
    }

    fun focusLost() {
        if (closed) return
        transportGeneration++
        recoveryResume = null
        pair?.let { it.focusLost(); return }
        val held = pause()
        message = if (held) "Audio focus lost; explicit Play required."
            else "Audio focus lost; pause refused; return to sources."
    }

    fun poll() {
        if (closed) return
        pair?.let { it.poll(); return }
        val transaction = recovery ?: return
        val restore = recoveryResume
        val result = transaction.poll()
        if (closed || recovery !== transaction) return
        when (result.state) {
            NativeLiveRecoveryPollState.SETTLED -> {
                message = "SDK READY in current live window; available feed paused. Explicit Play required; not exact live edge."
                if (restore != null) resumeRecoveredFeed(transaction, restore)
            }
            NativeLiveRecoveryPollState.TIMED_OUT -> message = if (result.held == true) "Catch-up check timed out; available feed paused."
                else "Catch-up check timed out; pause refused; return to sources."
            NativeLiveRecoveryPollState.ADMISSION_LOST -> message = if (result.held == true) "Catch-up authorization lost; available feed paused."
                else "Catch-up authorization lost; pause refused; return to sources."
            else -> Unit
        }
        if (!transaction.busy && recoveryResume === restore) recoveryResume = null
    }

    private fun resumeRecoveredFeed(transaction: NativeLiveRecovery, restore: RecoveryResume) {
        fun current() = !closed && recovery === transaction && recoveryResume === restore &&
            transportGeneration == restore.generation && member(restore.side) === restore.member &&
            members().size == 1
        fun ready(): Boolean {
            if (!current() || !admitted(restore.member)) return false
            val value = snapshot(restore.member) ?: return false
            val position = value.positionMs
            val duration = value.durationMs
            return current() && admitted(restore.member) && current() && value.state == 3 &&
                !value.playing && !value.playWhenReady && !value.playingAd && value.live &&
                position != null && duration != null && duration > 0 && position in 0..duration
        }
        fun refuse() {
            if (current()) { pause(); message = "Back to live cannot resume; available feed paused." }
        }
        if (!ready()) { refuse(); return }
        val focused = try { acquireFocus() } catch (_: Exception) { false }
        if (!current()) return
        if (!focused || !ready()) { refuse(); return }
        val accepted = playing(restore.member, true)
        if (!current()) return
        if (!accepted || !admitted(restore.member)) { refuse(); return }
        if (current()) message = "Back to live playback requested; SDK live default, not exact live edge."
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

    fun alignRelative(deltaMs: Long): NativeSeekPlan {
        if (!closed) pair?.let { return it.alignRelative(deltaMs) }
        message = "Marked alignment requires two playable feeds."
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
        val began = transportGeneration
        val original = snapshot(selected)
        if (closed || recovery !== transaction || member(side) !== selected || transportGeneration != began)
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        val resume = original?.let { it.state == 3 && it.playing && it.playWhenReady && !it.playingAd } == true
        val result = transaction.start(selected)
        if (closed || recovery !== transaction || member(side) !== selected || transportGeneration != began)
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        recoveryResume = if (resume && result.state == NativeLiveRecoveryStartState.STARTED)
            RecoveryResume(side, selected, began) else null
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
