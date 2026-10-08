package net.fstab.tachiai.platform.web

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.provider.BrowserProbeRole
import net.fstab.tachiai.provider.BrowserRequest

internal const val PAGE_LOG_TAG = "TachiaiPage"

internal enum class PageEventKind {
    STARTED, COMMIT_VISIBLE, FINISHED, NAVIGATION_BLOCKED,
    NETWORK_ERROR, HTTP_ERROR, TLS_RESOURCE_ERROR, RENDERER_GONE,
    SUBRESOURCE_NETWORK_ERROR, SUBRESOURCE_HTTP_ERROR, SUBRESOURCE_LIMIT_REACHED,
    SCRIPT_ALERT_CANCELLED, SCRIPT_CONFIRM_CANCELLED, SCRIPT_PROMPT_CANCELLED,
    SCRIPT_BEFORE_UNLOAD_CANCELLED, WINDOW_REQUESTED, WINDOW_DENIED_BY_POLICY,
    PERMISSION_DENIED, PERMISSION_GRANTED,
    CONSOLE_ERROR_SUPPRESSED, CONSOLE_WARNING_SUPPRESSED,
    GEOMETRY_LIMIT_REACHED,
    SCRIPT_ALERT_DELEGATED, SCRIPT_CONFIRM_DELEGATED, SCRIPT_PROMPT_DELEGATED,
    SCRIPT_BEFORE_UNLOAD_DELEGATED,
}

internal fun suppressScriptDialogs(request: BrowserRequest, debug: Boolean = BuildConfig.DEBUG): Boolean =
    request.suppressScriptDialogsAndConsole && !(debug && request.debugUseDefaultScriptDialogs)

internal data class NativeBrowserGeometry(val widthPx: Int, val heightPx: Int) {
    init { require(widthPx >= 0 && heightPx >= 0) }
}

// No URL, provider text, account state or page content enters this interface.
internal data class PageDiagnosticEvent(
    val browserId: Int,
    val kind: PageEventKind,
    val platformCode: Int? = null,
) {
    init {
        require(browserId > 0)
        require(platformCode == null || kind in setOf(PageEventKind.NETWORK_ERROR, PageEventKind.HTTP_ERROR,
            PageEventKind.SUBRESOURCE_NETWORK_ERROR, PageEventKind.SUBRESOURCE_HTTP_ERROR))
    }

    fun fixedMessage(): String = "browser=$browserId event=${kind.name}" +
        (platformCode?.let { " code=$it" } ?: "")
}

internal class BrowserPageDiagnostics(
    enabled: Boolean,
    debug: Boolean = BuildConfig.DEBUG,
    private val sink: (String) -> Unit = { Log.d(PAGE_LOG_TAG, it) },
) {
    private val browserId = if (enabled && debug) nextBrowserId.incrementAndGet() else null
    private var lastGeometry: NativeBrowserGeometry? = null
    private var geometryCount = 0
    private var geometryLimitReported = false

    fun recordHostPolicy(role: BrowserProbeRole, dialogsSuppressed: Boolean) {
        val id = browserId ?: return
        sink("browser=$id event=HOST_POLICY role=${role.name} dialogs=" +
            if (dialogsSuppressed) "CANCELLED" else "DEFAULT")
    }

    fun recordGeometry(widthPx: Int, heightPx: Int) {
        val id = browserId ?: return
        val geometry = NativeBrowserGeometry(widthPx, heightPx)
        if (geometry == lastGeometry) return
        lastGeometry = geometry
        if (geometryCount >= 32) {
            if (!geometryLimitReported) {
                geometryLimitReported = true
                record(PageEventKind.GEOMETRY_LIMIT_REACHED)
            }
            return
        }
        geometryCount += 1
        sink("browser=$id event=GEOMETRY widthPx=$widthPx heightPx=$heightPx")
    }

    fun record(kind: PageEventKind, platformCode: Int? = null) {
        val id = browserId ?: return
        sink(PageDiagnosticEvent(id, kind, platformCode).fixedMessage())
    }

    private companion object {
        val nextBrowserId = AtomicInteger(0)
    }
}
