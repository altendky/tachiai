package net.fstab.tachiai.platform.web

import android.util.Log
import net.fstab.tachiai.BuildConfig

internal const val POPUP_LOG_TAG = "TachiaiPopup"

internal enum class PopupDestinationClass {
    ALLOWED_HTTPS, ALTERNATE_PROVIDER_HTTPS, INITIAL_BLANK, OTHER,
}

internal enum class PopupEventKind(val failure: Boolean = false) {
    REQUEST_NO_GESTURE(true), REQUEST_ALREADY_OPEN(true), REQUEST_INVALID_TRANSPORT(true),
    OPENED, NAVIGATION_ALLOWED, INITIAL_BLANK, NAVIGATION_BLOCKED(true),
    COMMIT_VISIBLE, COMMIT_IGNORED, PAGE_FINISHED,
    NETWORK_ERROR(true), HTTP_ERROR(true), TLS_ERROR(true), RENDERER_GONE(true),
    SUBRESOURCE_NETWORK_ERROR, SUBRESOURCE_HTTP_ERROR, SUBRESOURCE_LIMIT_REACHED,
    NESTED_WINDOW_DENIED, PERMISSION_DENIED, CLOSED,
    SCRIPT_ALERT_CANCELLED, SCRIPT_CONFIRM_CANCELLED, SCRIPT_PROMPT_CANCELLED,
    SCRIPT_BEFORE_UNLOAD_CANCELLED, CONSOLE_ERROR_SUPPRESSED, CONSOLE_WARNING_SUPPRESSED,
}

// Only closed enums and native numeric codes cross the logging boundary.
// Never accept a URL, provider string, exception, console message or account data.
internal data class PopupDiagnosticEvent(
    val popupId: Int,
    val kind: PopupEventKind,
    val destination: PopupDestinationClass? = null,
    val platformCode: Int? = null,
) {
    init {
        require(popupId > 0)
        require(platformCode == null || kind in setOf(PopupEventKind.NETWORK_ERROR, PopupEventKind.HTTP_ERROR,
            PopupEventKind.SUBRESOURCE_NETWORK_ERROR, PopupEventKind.SUBRESOURCE_HTTP_ERROR))
    }

    fun fixedMessage(): String = "popup=$popupId event=${kind.name}" +
        (destination?.let { " destination=${it.name}" } ?: "") +
        (platformCode?.let { " code=$it" } ?: "")
}

internal fun logPopupEvent(
    event: PopupDiagnosticEvent,
    debug: Boolean = BuildConfig.DEBUG,
    sink: (String) -> Unit = { Log.d(POPUP_LOG_TAG, it) },
) {
    if (debug) sink(event.fixedMessage())
}
