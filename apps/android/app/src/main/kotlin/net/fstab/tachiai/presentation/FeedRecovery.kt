package net.fstab.tachiai.presentation

internal enum class FeedRecoveryAction(val label: String) {
    GO_LIVE("Go live"), RETRY("Retry"), CHECK_CONNECTION("Check connection"), PLAY_AGAIN("Play again"),
}

// A user-requested fresh attempt still passes normal account, route and media
// admission. An account check may reuse an existing verified renewal path;
// refusal, policy, licensing and unsafe cleanup never authorize repeated playback.
internal fun PrototypeFeedFailure.recoveryAction(): FeedRecoveryAction? {
    if (httpStatus in setOf(401, 403)) return null
    return when (reason) {
        PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE -> FeedRecoveryAction.CHECK_CONNECTION
        // Reopening the stream; an ended replay starts again from its beginning.
        PrototypeFailureReason.REPLAY_ENDED -> FeedRecoveryAction.PLAY_AGAIN
        PrototypeFailureReason.BROADCAST_ENDED -> FeedRecoveryAction.RETRY
        PrototypeFailureReason.LIVE_WINDOW_EXPIRED -> FeedRecoveryAction.GO_LIVE
        PrototypeFailureReason.PLAYER_FAILED -> when (playerCode) {
            1002 -> FeedRecoveryAction.GO_LIVE
            null, 1000, 1001, 1003, 2000, 2001, 2002 -> FeedRecoveryAction.RETRY
            else -> null
        }
        PrototypeFailureReason.NETWORK_FAILED, PrototypeFailureReason.NETWORK_SECURITY_FAILED, PrototypeFailureReason.PREPARATION_TIMEOUT,
        PrototypeFailureReason.LIVE_POSITION_FAILED, PrototypeFailureReason.MEDIA_NOT_FOUND,
        PrototypeFailureReason.CATALOG_RATE_LIMITED, PrototypeFailureReason.CHANNEL_NOT_AIRING -> FeedRecoveryAction.RETRY
        PrototypeFailureReason.HTTP_REJECTED -> FeedRecoveryAction.RETRY.takeIf {
            httpStatus == 408 || httpStatus == 429 || httpStatus?.let { it in 500..599 } == true
        }
        else -> null
    }
}

internal val PrototypeFeedFailure.viewerMessage: String get() = when {
    reason == PrototypeFailureReason.PLAYER_FAILED && playerCode == 1002 -> "Playback fell behind live."
    reason == PrototypeFailureReason.NETWORK_FAILED -> "Playback connection interrupted."
    else -> message
}

// Slot generations outlive their sessions. Invalidating before close prevents
// synchronous and delayed old callbacks from affecting a replacement session.
internal class FeedRetryState {
    @Volatile private var generation = 0L
    var requested = false
        private set
    var retrying = false
        private set
    var replayPositionMs: Long? = null
        private set
    fun newAttempt(): Long { requested = false; return ++generation }
    fun current(attempt: Long) = generation == attempt
    fun invalidate() { generation++; requested = false; retrying = false }
    fun request(): Boolean {
        if (requested || retrying) return false
        requested = true; retrying = true
        return true
    }
    fun ready() { retrying = false }
    fun rememberReplay(positionMs: Long) { if (positionMs >= 0) replayPositionMs = positionMs }
    fun forgetReplay() { replayPositionMs = null }
}
