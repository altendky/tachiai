package net.fstab.tachiai.presentation

import java.util.concurrent.atomic.AtomicReference

internal enum class PrototypeFailureReason(val message: String) {
    LOGIN_MISSING("No saved Twitch login is available. Connect in Providers → Twitch."),
    LOGIN_EXPIRED("The saved Twitch login expired. Reconnect in Providers → Twitch."),
    LOGIN_UNAVAILABLE("The saved Twitch login could not be read. Reconnect in Providers → Twitch."),
    LOGIN_REJECTED("Twitch rejected the saved login. Reconnect in Providers → Twitch."),
    ABEMA_ACCOUNT_UNAVAILABLE("The saved ABEMA account is unavailable. Check its connection in Providers → ABEMA."),
    CATALOG_CONNECTION_REQUIRED("Connect or reconnect the Twitch account in Providers."),
    CATALOG_UNAVAILABLE("Twitch channel discovery is unavailable. Check its connection in Providers."),
    CATALOG_RATE_LIMITED("Twitch channel discovery is rate limited. Try again later."),
    MEDIA_NOT_FOUND("The stream could not be found. A live channel may be offline."),
    HTTP_REJECTED("The provider rejected a playback request."),
    NETWORK_FAILED("A playback network request failed. Check your connection."),
    NETWORK_SECURITY_FAILED("A secure playback connection could not be established."),
    ROUTE_FAILED("The selected route could not be prepared. No System-network fallback was used."),
    MEDIA_BLOCKED("A media destination was blocked by this prototype's network policy."),
    MEDIA_APPROVAL_REQUIRED("A new media destination is blocked pending approval."),
    UNSUPPORTED_MEDIA("This prototype cannot play the media format or provider configuration."),
    LICENSE_FAILED("The video license could not be prepared."),
    PREPARATION_TIMEOUT("The feed did not finish preparing in time."),
    PREPARATION_FAILED("The provider could not prepare this feed."),
    PLAYER_FAILED("The video player could not continue."),
    // A feed whose player reached the end of its media (#553). Not failures:
    // they are not journaled as such and never start automatic recovery.
    REPLAY_ENDED("This replay has ended."),
    BROADCAST_ENDED("This broadcast has ended."),
    PLAYBACK_LIMIT("A playback time or media size limit was reached."),
    LIVE_POSITION_FAILED("The current live position could not be prepared."),
    LIVE_WINDOW_EXPIRED("The live position is outside or missing from the current window."),
    ORIGINAL_PLAYER_NOT_PAUSED("The original provider player could not be paused."),
    CLEANUP_FAILED("Playback could not be safely stopped. Close and reopen Tachiai."),
    STOPPED("Playback stopped."),
}

// Loader callbacks and main-thread teardown may report the same failure.
// Preserve its first cause through later request/player/cleanup notifications.
// An explicit terminal failure can supersede an earlier retryable observation
// observation, but must never hide a more specific refusal observed around it.
internal class PrototypeFeedFailureLatch {
    private data class State(val first: PrototypeFeedFailure? = null,
        val terminal: PrototypeFeedFailure? = null, val specific: PrototypeFeedFailure? = null,
        val playerFailureObserved: Boolean = false)
    private val state = AtomicReference(State())
    private fun bareNetwork(value: PrototypeFeedFailure?) = value?.reason == PrototypeFailureReason.NETWORK_FAILED &&
        value.httpStatus == null && value.playerCode == null
    private fun blocksTerminalRecovery(value: PrototypeFeedFailure): Boolean {
        if (value.httpStatus in setOf(401, 403)) return true
        return when (value.reason) {
            PrototypeFailureReason.NETWORK_FAILED -> !bareNetwork(value)
            PrototypeFailureReason.HTTP_REJECTED -> value.playerCode != null ||
                !(value.httpStatus in setOf(408, 429) || value.httpStatus?.let { it in 500..599 } == true)
            PrototypeFailureReason.MEDIA_NOT_FOUND -> value.playerCode != null ||
                value.httpStatus?.let { it != 404 } == true
            PrototypeFailureReason.LIVE_WINDOW_EXPIRED, PrototypeFailureReason.LIVE_POSITION_FAILED ->
                value.httpStatus != null || value.playerCode != null
            PrototypeFailureReason.PLAYER_FAILED -> value.playerCode != 1002 || value.httpStatus != null
            else -> true
        }
    }
    private fun conditionalRecovery(value: PrototypeFeedFailure) = value.httpStatus == null && value.playerCode == null &&
        value.reason in setOf(PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE, PrototypeFailureReason.LOGIN_EXPIRED,
            PrototypeFailureReason.LOGIN_UNAVAILABLE, PrototypeFailureReason.PREPARATION_TIMEOUT)
    private fun replaceable(value: PrototypeFeedFailure?) = value != null &&
        (!blocksTerminalRecovery(value) || conditionalRecovery(value))
    private fun specific(previous: PrototypeFeedFailure?, value: PrototypeFeedFailure): PrototypeFeedFailure =
        if (previous == null || priority(value) > priority(previous)) value else previous
    private fun priority(value: PrototypeFeedFailure) = when {
        !conditionalRecovery(value) -> 3
        value.reason == PrototypeFailureReason.PREPARATION_TIMEOUT -> 2
        else -> 1
    }
    val failure: PrototypeFeedFailure? get() {
        val value = state.get()
        return if (replaceable(value.first) && value.terminal != null)
            value.specific ?: value.terminal else value.first
    }
    fun remember(value: PrototypeFeedFailure) {
        state.updateAndGet { previous -> when {
            previous.first == null -> previous.copy(first = value,
                specific = value.takeIf(::blocksTerminalRecovery))
            // Retryable availability observations cannot become stale recovery
            // authority or outrank a later authentication/policy refusal.
            replaceable(previous.first) && blocksTerminalRecovery(value) &&
                value != previous.terminal &&
                // Internal player teardown emits STOPPED after PLAYER_FAILED.
                !(value.reason == PrototypeFailureReason.STOPPED && previous.terminal != null) ->
                previous.copy(specific = specific(previous.specific, value))
            else -> previous
        } }
    }
    fun rememberPlayerFailure(code: Int?) {
        val value = PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = code)
        rememberTerminalFailure(value)
    }
    fun rememberFallbackIfEmpty(value: PrototypeFeedFailure) {
        state.updateAndGet { previous -> if (previous.first == null)
            previous.copy(first = value, specific = value.takeIf(::blocksTerminalRecovery)) else previous }
    }
    fun rememberTerminalFailure(value: PrototypeFeedFailure, fromInactivePlayerBudget: Boolean = false) {
        state.updateAndGet { previous ->
            val updated = when {
                // Native close stops its child budget before posted failure
                // delivery. A poll during asynchronous cleanup is consequential;
                // it must not turn the recorded player failure into a new refusal.
                fromInactivePlayerBudget && value.reason == PrototypeFailureReason.STOPPED &&
                    value.httpStatus == null && value.playerCode == null && previous.playerFailureObserved -> previous
                previous.first == null -> previous.copy(first = value, terminal = value,
                    specific = value.takeIf(::blocksTerminalRecovery))
                replaceable(previous.first) && previous.terminal == null -> previous.copy(terminal = value,
                    specific = if (blocksTerminalRecovery(value)) specific(previous.specific, value)
                        else previous.specific)
                replaceable(previous.first) && blocksTerminalRecovery(value) && value != previous.terminal ->
                    previous.copy(specific = specific(previous.specific, value))
                else -> previous
            }
            if (value.reason == PrototypeFailureReason.PLAYER_FAILED) updated.copy(playerFailureObserved = true) else updated
        }
    }
}

// Only closed reasons and bounded numeric diagnostics cross the provider boundary.
// Never carry provider messages, exceptions, response bodies or source URLs here.
internal data class PrototypeFeedFailure(
    val reason: PrototypeFailureReason,
    val httpStatus: Int? = null,
    val playerCode: Int? = null,
    // A temporary provider failure before playback (for example the account
    // check), without media evidence. It may continue an active recovery.
    val transientPreparation: Boolean = false,
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
