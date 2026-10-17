package net.fstab.tachiai.provider.abema

// This immutable scope belongs to one native run, not to the shared account's
// cached identity proof. The client supplies durable grant/owner checks.
internal class AbemaSlotAuthorityScope(
    val client: Any,
    val instanceId: String,
    val ownerProof: String,
    val sourceProof: String,
    val channelId: String,
    val slotId: String,
    val run: Long,
    val playbackDeadline: Long,
) {
    override fun toString() = "AbemaSlotAuthorityScope(redacted)"
}

internal class AbemaSlotAuthorityReceipt private constructor(
    private val scope: AbemaSlotAuthorityScope,
    val expiresAtMs: Long,
    private val beganWall: Long,
    private val beganElapsed: Long,
) {
    companion object {
        fun publish(scope: AbemaSlotAuthorityScope, expiresAtMs: Long, beganWall: Long,
            beganElapsed: Long, readinessDeadline: Long, wall: Long, elapsed: Long): AbemaSlotAuthorityReceipt? {
            if (beganWall <= 0 || beganElapsed < 0 || scope.run <= 0 ||
                readinessDeadline <= beganElapsed || readinessDeadline > scope.playbackDeadline ||
                wall < beganWall || elapsed < beganElapsed || elapsed >= readinessDeadline ||
                expiresAtMs <= wall || expiresAtMs > 9_007_199_254_740_991L) return null
            return AbemaSlotAuthorityReceipt(scope, expiresAtMs, beganWall, beganElapsed).takeIf {
                it.current(scope, wall, elapsed)
            }
        }
    }
    private val lock = Any()
    private var minimumWall = beganWall
    private var minimumElapsed = beganElapsed
    private var refused = false

    // RAM/clock only: safe for native presentation admission and the main
    // monitor. A lost scope or clock rollback permanently ends authority.
    fun current(expected: AbemaSlotAuthorityScope, wall: Long, elapsed: Long): Boolean = synchronized(lock) {
        val okay = !refused && expected === scope && wall >= minimumWall && elapsed >= minimumElapsed &&
            wall < expiresAtMs && elapsed < scope.playbackDeadline
        if (!okay) refused = true
        minimumWall = maxOf(minimumWall, wall)
        minimumElapsed = maxOf(minimumElapsed, elapsed)
        okay
    }
    override fun toString() = "AbemaSlotAuthorityReceipt(redacted)"
}
