package net.fstab.tachiai.provider.abema

import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.provider.abema.account.AbemaAccountException
import net.fstab.tachiai.provider.abema.account.AbemaAccountFailure
import net.fstab.tachiai.provider.abema.account.AbemaAccountHttpTransport
import net.fstab.tachiai.provider.abema.account.AbemaSubscriptionProfileResult

// Owns only the HTTP operation, borrowing the already-created native route.
// The session/coordinator must retain that route until this receipt confirms
// cleanup. No backend factory, route close, guest or renewal exists here.
internal class AbemaBorrowedSlotProfile(
    open: (URL) -> HttpsURLConnection,
    private val current: () -> Boolean,
    private val elapsedMs: () -> Long,
    wallMs: () -> Long,
    private val readinessDeadline: Long,
    cleanupWaitMs: Long = 1000,
) {
    private val began = elapsedMs()
    private val cancelled = AtomicBoolean()
    private val started = AtomicBoolean()
    private val finished = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val http = AbemaAccountHttpTransport(open, ::canRequest, elapsedMs, wallMs, cleanupWaitMs)
    val cleanupConfirmed get() = finished.get() && !failed.get() && http.cleanupConfirmed
    private fun canRequest(): Boolean {
        val before = elapsedMs()
        if (cancelled.get() || before < began || before >= readinessDeadline) return false
        val admitted = current()
        // Durable owner/account checks can block. They consume the original
        // readiness budget; a pre-check timestamp cannot admit a late request.
        val after = elapsedMs()
        return admitted && !cancelled.get() && after >= before && after < readinessDeadline
    }
    fun read(id: String, bearer: String): AbemaSubscriptionProfileResult {
        if (!started.compareAndSet(false, true)) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
        try {
            if (!canRequest()) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            val result = http.subscriptionProfile(id, bearer)
            if (!canRequest()) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            return result
        } finally {
            try {
                http.close()
                if (!http.cleanupConfirmed) throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
                finished.set(true)
            } catch (_: Throwable) {
                failed.set(true)
                throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
            }
        }
    }
    fun cancel() {
        cancelled.set(true)
        try { http.cancelActiveRequest() }
        catch (_: Throwable) { failed.set(true) }
    }
    override fun toString() = "AbemaBorrowedSlotProfile(redacted)"
}
