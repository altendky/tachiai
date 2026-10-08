package net.fstab.tachiai.platform.web

import android.os.SystemClock
import android.util.Log
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.provider.BrowserResourceCategory

internal const val RESOURCE_LOG_TAG = "TachiaiResource"
internal enum class ResourceEventKind { REQUEST_OBSERVED, NETWORK_ERROR, HTTP_ERROR, LIMIT_REACHED }

// No request/response objects, URLs, strings from the provider, or success claims.
internal data class ResourceDiagnosticEvent(
    val popupId: Int,
    val kind: ResourceEventKind,
    val category: BrowserResourceCategory?,
    val elapsedMs: Long,
    val platformCode: Int? = null,
) {
    init {
        require(popupId > 0 && elapsedMs >= 0)
        require((category == null) == (kind == ResourceEventKind.LIMIT_REACHED))
        require((platformCode != null) == (kind == ResourceEventKind.NETWORK_ERROR || kind == ResourceEventKind.HTTP_ERROR))
    }

    fun fixedMessage(): String = "popup=$popupId event=${kind.name} elapsedMs=$elapsedMs" +
        (category?.let { " category=${it.name}" } ?: "") +
        (platformCode?.let { " code=$it" } ?: "")
}

internal class BrowserResourceDiagnostics(
    private val popupId: Int,
    enabled: Boolean,
    debug: Boolean = BuildConfig.DEBUG,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val sink: (String) -> Unit = { Log.d(RESOURCE_LOG_TAG, it) },
) {
    private val enabled = enabled && debug
    private val openedAt = if (this.enabled) clock() else 0L
    private val budget = NativeDiagnosticBudget<Triple<ResourceEventKind, BrowserResourceCategory, Int?>>(16)
    private var closed = false

    // shouldInterceptRequest runs off the UI thread; synchronize both the budget
    // and close guard. No post captures a provider URL or accumulates a queue.
    @Synchronized
    fun record(category: BrowserResourceCategory?, kind: ResourceEventKind, code: Int? = null) {
        if (!enabled || closed || category == null) return
        val event = ResourceDiagnosticEvent(popupId, kind, category, (clock() - openedAt).coerceAtLeast(0), code)
        if (budget.admit(Triple(kind, category, code))) sink(event.fixedMessage())
        if (budget.takeLimitMarker()) {
            sink(ResourceDiagnosticEvent(popupId, ResourceEventKind.LIMIT_REACHED, null, event.elapsedMs).fixedMessage())
        }
    }

    @Synchronized
    fun close() { closed = true }
}
