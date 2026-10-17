package net.fstab.tachiai.feature.presentation

internal data class RestartProcessIdentity(val pid: Int, val uid: Int, val name: String)

internal enum class PlaybackRestartStatus { WAITING, READY, IDENTITY_MISMATCH, TIMED_OUT }

// The main-process relay must observe the old player exit before launching a
// replacement. An unavailable process snapshot is never evidence of exit.
internal class PlaybackRestartCoordinator(
    private val priorPid: Int,
    private val uid: Int,
    private val processName: String,
    val beganAt: Long,
    private val timeoutMs: Long = 10_000L,
) {
    init { require(priorPid > 0 && uid >= 0 && processName.isNotBlank() && beganAt >= 0 && timeoutMs > 0) }

    fun poll(now: Long, processes: List<RestartProcessIdentity>?): PlaybackRestartStatus {
        if (now < beganAt || now - beganAt >= timeoutMs) return PlaybackRestartStatus.TIMED_OUT
        if (processes == null) return PlaybackRestartStatus.WAITING
        val prior = processes.filter { it.pid == priorPid }
        if (prior.isEmpty()) return PlaybackRestartStatus.READY
        if (prior.size != 1 || prior.single().uid != uid || prior.single().name != processName)
            return PlaybackRestartStatus.IDENTITY_MISMATCH
        return PlaybackRestartStatus.WAITING
    }
}
