package net.fstab.tachiai.platform.media

internal enum class NativeLiveRecoveryStartState { PREFLIGHT_REFUSED, HOLD_REFUSED, RECHECK_REFUSED, DISPATCH_REFUSED, STARTED }
internal data class NativeLiveRecoveryStart(val state: NativeLiveRecoveryStartState, val plan: NativeSeekPlan,
    val held: Boolean? = null)
internal enum class NativeLiveRecoveryPollState { NONE, WAITING, SETTLED, TIMED_OUT, ADMISSION_LOST }
internal data class NativeLiveRecoveryPoll(val state: NativeLiveRecoveryPollState, val held: Boolean? = null)

// Borrows one or two actual participants. No player/session lifetime ownership,
// provider reads, absolute live-edge estimate, retry or automatic Play.
internal class NativeLiveRecovery(
    participants: List<NativePairMember>,
    private val clockMs: () -> Long,
    private val admission: (NativePairMember) -> Boolean,
    private val beforeDispatch: () -> Unit = {},
) {
    private val participants = participants.toList()
    init { require(this.participants.size in 1..2) }
    private data class Pending(val selected: NativePairMember, val startedMs: Long)
    private var pending: Pending? = null
    private var generation = 0L
    val busy: Boolean get() = pending != null
    private fun admitted() = participants.all { try { admission(it) } catch (_: Exception) { false } }
    private fun snapshots(): List<NativeTimingSnapshot?> = participants.map {
        try { it.timingSnapshot() } catch (_: Exception) { null }
    }
    private fun held(expectedGeneration: Long): Boolean {
        var result = true
        for (participant in participants) {
            if (generation != expectedGeneration) return false
            val accepted = try { participant.setTimingPlaying(false) } catch (_: Exception) { false }
            if (generation != expectedGeneration) return false
            if (!accepted) result = false
        }
        return result
    }

    fun plan(selected: NativePairMember): NativeSeekPlan {
        val began = generation
        val index = participants.indexOfFirst { it === selected }
        if (busy || index < 0 || !admitted()) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        val values = snapshots()
        val allowed = admitted()
        if (generation != began || !allowed || values.any { it == null || it.state != 3 })
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        if (values.any { it?.playingAd == true }) return NativeSeekPlan(NativeSeekOutcome.AD_BLOCKED)
        return nativeLiveDefaultPlan(values[index])
    }

    fun start(selected: NativePairMember): NativeLiveRecoveryStart {
        val began = generation
        val initial = plan(selected)
        if (initial.outcome != NativeSeekOutcome.REQUESTED)
            return NativeLiveRecoveryStart(NativeLiveRecoveryStartState.PREFLIGHT_REFUSED, initial)
        if (!held(began)) return NativeLiveRecoveryStart(NativeLiveRecoveryStartState.HOLD_REFUSED,
            NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE), false)
        // Holding may change a window, command, membership or authorization.
        // The initial UI/preflight plan never authorizes a later dispatch.
        if (generation != began)
            return NativeLiveRecoveryStart(NativeLiveRecoveryStartState.RECHECK_REFUSED, NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE), true)
        val fresh = plan(selected)
        if (fresh.outcome != NativeSeekOutcome.REQUESTED)
            return NativeLiveRecoveryStart(NativeLiveRecoveryStartState.RECHECK_REFUSED, fresh, true)
        beforeDispatch()
        val allowed = admitted()
        if (generation != began || !allowed)
            return NativeLiveRecoveryStart(NativeLiveRecoveryStartState.RECHECK_REFUSED, NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE), true)
        val accepted = try { selected.seekLiveDefault() }
            catch (_: Exception) { NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) }
        val afterDispatchAdmission = admitted()
        val stillCurrent = generation == began && afterDispatchAdmission
        if (accepted.outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE) || !stillCurrent) {
            val plan = if (!stillCurrent) NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) else accepted
            val held = generation == began && held(began)
            return NativeLiveRecoveryStart(NativeLiveRecoveryStartState.DISPATCH_REFUSED, plan, held)
        }
        pending = Pending(selected, clockMs())
        return NativeLiveRecoveryStart(NativeLiveRecoveryStartState.STARTED, accepted)
    }

    fun poll(): NativeLiveRecoveryPoll {
        val transaction = pending ?: return NativeLiveRecoveryPoll(NativeLiveRecoveryPollState.NONE)
        val began = generation
        val now = clockMs()
        fun expired(value: Long) = value < transaction.startedMs ||
            try { Math.subtractExact(value, transaction.startedMs) >= 8_000 } catch (_: ArithmeticException) { true }
        fun stop(state: NativeLiveRecoveryPollState): NativeLiveRecoveryPoll {
            if (generation != began || pending !== transaction) return NativeLiveRecoveryPoll(NativeLiveRecoveryPollState.NONE)
            cancel()
            return NativeLiveRecoveryPoll(state, held(generation))
        }
        if (expired(now)) {
            return stop(NativeLiveRecoveryPollState.TIMED_OUT)
        }
        if (!admitted()) {
            return stop(NativeLiveRecoveryPollState.ADMISSION_LOST)
        }
        val values = snapshots()
        val allowed = admitted()
        if (generation != began || pending !== transaction) return NativeLiveRecoveryPoll(NativeLiveRecoveryPollState.NONE)
        if (!allowed) {
            return stop(NativeLiveRecoveryPollState.ADMISSION_LOST)
        }
        val afterRead = clockMs()
        if (afterRead < now || expired(afterRead)) {
            return stop(NativeLiveRecoveryPollState.TIMED_OUT)
        }
        if (values.any { it == null || it.state != 3 || it.playing || it.playWhenReady || it.playingAd })
            return NativeLiveRecoveryPoll(NativeLiveRecoveryPollState.WAITING)
        val selected = values[participants.indexOfFirst { it === transaction.selected }] ?: return NativeLiveRecoveryPoll(NativeLiveRecoveryPollState.WAITING)
        val position = selected.positionMs
        val duration = selected.durationMs
        if (!selected.live || position == null || duration == null || duration <= 0 || position < 0 || position > duration)
            return NativeLiveRecoveryPoll(NativeLiveRecoveryPollState.WAITING)
        pending = null
        return NativeLiveRecoveryPoll(NativeLiveRecoveryPollState.SETTLED)
    }

    fun cancel() { pending = null; generation++ }
}
