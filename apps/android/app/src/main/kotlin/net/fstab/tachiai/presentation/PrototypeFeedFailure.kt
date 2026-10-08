package net.fstab.tachiai.presentation

import java.util.concurrent.atomic.AtomicReference

internal enum class PrototypeFailureReason(val message: String) {
    LOGIN_MISSING("No saved Twitch login is available. Connect through the login screen."),
    LOGIN_EXPIRED("The saved Twitch login expired. Reconnect through the login screen."),
    LOGIN_UNAVAILABLE("The saved Twitch login could not be read. Reconnect through the login screen."),
    LOGIN_REJECTED("Twitch rejected the saved login. Reconnect through the login screen."),
    MEDIA_NOT_FOUND("The stream could not be found. A live channel may be offline."),
    HTTP_REJECTED("The provider rejected a playback request."),
    NETWORK_FAILED("A playback network request failed. Check your connection."),
    ROUTE_FAILED("The selected route could not be prepared. No System-network fallback was used."),
    MEDIA_BLOCKED("A media destination was blocked by this prototype's network policy."),
    MEDIA_APPROVAL_REQUIRED("A new media destination is blocked pending approval."),
    UNSUPPORTED_MEDIA("This prototype cannot play the media format or provider configuration."),
    LICENSE_FAILED("The video license could not be prepared."),
    PREPARATION_TIMEOUT("The feed did not finish preparing in time."),
    PREPARATION_FAILED("The provider could not prepare this feed."),
    PLAYER_FAILED("The video player could not continue."),
    PLAYBACK_LIMIT("The prototype playback time or request limit was reached."),
    LIVE_POSITION_FAILED("The current live position could not be prepared."),
    ORIGINAL_PLAYER_NOT_PAUSED("The original provider player could not be paused."),
    CLEANUP_FAILED("Playback could not be safely stopped. Close and reopen Tachiai."),
    STOPPED("Playback stopped."),
}

// Loader callbacks and main-thread teardown may report the same failure.
// Preserve its first cause through later request/player/cleanup notifications.
internal class PrototypeFeedFailureLatch {
    private val first = AtomicReference<PrototypeFeedFailure?>()
    val failure: PrototypeFeedFailure? get() = first.get()
    fun remember(value: PrototypeFeedFailure) { first.compareAndSet(null, value) }
}

// Only closed reasons and bounded numeric diagnostics cross the provider boundary.
// Never carry provider messages, exceptions, response bodies or source URLs here.
internal data class PrototypeFeedFailure(
    val reason: PrototypeFailureReason,
    val httpStatus: Int? = null,
    val playerCode: Int? = null,
) {
    init {
        require(httpStatus == null || httpStatus in 100..599)
        require(playerCode == null || playerCode in 1000..9999)
    }

    val message: String get() = buildString {
        append(reason.message)
        httpStatus?.let { append(" HTTP ").append(it).append('.') }
        playerCode?.let { append(" Player error ").append(it).append('.') }
    }
}
