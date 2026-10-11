package net.fstab.tachiai.provider.abema.account

import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.network.RouteSession

internal enum class AbemaApprovalRegionFailure {
    REFUSED_REQUEST, OWNER_NOT_CURRENT, EXPIRED, CANCELLED, REDIRECT_REFUSED, INVALID_RESPONSE,
    TRANSPORT_FAILED, CLEANUP_FAILED,
}

internal sealed interface AbemaApprovalRegionResult {
    // No response body, cookie, arbitrary header or provider reason is retained.
    class Reply(val status: Int, headers: Map<String, String>) : AbemaApprovalRegionResult {
        val headers = headers.toMap()
        override fun toString() = "AbemaApprovalRegionResult.Reply(status=$status)"
    }
    data class Failure(val category: AbemaApprovalRegionFailure) : AbemaApprovalRegionResult
}

// WebView resource redirects are not re-intercepted. This adapter owns the one
// verified GET itself rather than admitting unrestricted traffic to a CDN host.
// The opener seam is provider-free test support; production uses the captured,
// credential-free saved RouteSession and never Java's default URL opener.
internal class AbemaApprovalRegionCheck(
    private val open: (URL) -> HttpsURLConnection,
    private val canRequest: () -> Boolean,
    private val elapsedMs: () -> Long,
    private val deadlineElapsedMs: Long,
    private val cleanupWaitMs: Long = 1000,
) {
    constructor(route: RouteSession, canRequest: () -> Boolean, elapsedMs: () -> Long,
        deadlineElapsedMs: Long) : this(route::open, canRequest, elapsedMs, deadlineElapsedMs) {
        require(!route.isSystem) { "ABEMA region check requires a saved route" }
    }

    companion object {
        const val URI = "https://ds-glb-linear-abematv.akamaized.net/region"
        private val origins = setOf("https://abema.tv", "https://auth.abema.tv")
        private val retained = ConcurrentLinkedQueue<Handle>()
        private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "abema-approval-region-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
    init { require(deadlineElapsedMs >= 0 && cleanupWaitMs in 1..5000) }

    private class Handle(val connection: HttpsURLConnection, waitMs: Long) {
        val receipt = AbemaAccountCleanupReceipt(connection, HttpsURLConnection::disconnect, waitMs)
        val retainedOnce = AtomicBoolean()
    }
    private class Refusal(val category: AbemaApprovalRegionFailure) : RuntimeException()
    private val lock = Any()
    private var closed = false
    private var poisoned = false
    private var busy = false
    private var timedOut = false
    private var active: Handle? = null
    private var completed: CountDownLatch? = null
    val cleanupConfirmed get() = synchronized(lock) { !poisoned && !busy && active == null }

    private fun poison(handle: Handle?) {
        synchronized(lock) { poisoned = true }
        if (handle != null && handle.retainedOnce.compareAndSet(false, true)) retained.add(handle)
    }
    private fun currentFailure(began: Long, end: Long): AbemaApprovalRegionFailure? {
        synchronized(lock) {
            if (poisoned) return AbemaApprovalRegionFailure.CLEANUP_FAILED
            if (timedOut) return AbemaApprovalRegionFailure.EXPIRED
            if (closed) return AbemaApprovalRegionFailure.CANCELLED
        }
        val now = try { elapsedMs() } catch (_: Throwable) { return AbemaApprovalRegionFailure.EXPIRED }
        if (now < began || now >= end) return AbemaApprovalRegionFailure.EXPIRED
        if (!runCatching(canRequest).getOrDefault(false)) return AbemaApprovalRegionFailure.OWNER_NOT_CURRENT
        val afterOwner = try { elapsedMs() } catch (_: Throwable) { return AbemaApprovalRegionFailure.EXPIRED }
        if (afterOwner < now || afterOwner >= end) return AbemaApprovalRegionFailure.EXPIRED
        synchronized(lock) {
            if (poisoned) return AbemaApprovalRegionFailure.CLEANUP_FAILED
            if (timedOut) return AbemaApprovalRegionFailure.EXPIRED
            if (closed) return AbemaApprovalRegionFailure.CANCELLED
        }
        return null
    }

    // Called on the interception worker. Only a canonical first-party Origin
    // may be copied; no cookie, bearer, referrer or arbitrary browser header is.
    fun check(origin: String? = null): AbemaApprovalRegionResult {
        if (origin != null && origin !in origins)
            return AbemaApprovalRegionResult.Failure(AbemaApprovalRegionFailure.REFUSED_REQUEST)
        val done = CountDownLatch(1)
        synchronized(lock) {
            if (poisoned) return AbemaApprovalRegionResult.Failure(AbemaApprovalRegionFailure.CLEANUP_FAILED)
            if (closed || busy) return AbemaApprovalRegionResult.Failure(AbemaApprovalRegionFailure.CANCELLED)
            busy = true; timedOut = false; completed = done
        }
        var handle: Handle? = null
        var watchdog: java.util.concurrent.ScheduledFuture<*>? = null
        var result: AbemaApprovalRegionResult = AbemaApprovalRegionResult.Failure(AbemaApprovalRegionFailure.TRANSPORT_FAILED)
        var began = -1L
        var end = -1L
        fun checkCurrent() { currentFailure(began, end)?.let { throw Refusal(it) } }
        try {
            began = elapsedMs()
            if (began < 0 || began >= deadlineElapsedMs) throw Refusal(AbemaApprovalRegionFailure.EXPIRED)
            // No operation extends the original challenge; an individual public
            // check also has a finite 15-second budget and no retries.
            end = minOf(deadlineElapsedMs, if (began > Long.MAX_VALUE - 15_000) Long.MAX_VALUE else began + 15_000)
            checkCurrent()
            watchdog = deadlines.schedule({
                val pending = synchronized(lock) {
                    if (completed === done && busy) { timedOut = true; active } else null
                }
                pending?.receipt?.start()
            }, (end - elapsedMs()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
            val allocated = Handle(open(URL(URI)), cleanupWaitMs)
            handle = allocated
            synchronized(lock) { active = allocated }
            checkCurrent()
            val connection = allocated.connection
            if (connection.url.toExternalForm() != URI) throw Refusal(AbemaApprovalRegionFailure.REFUSED_REQUEST)
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.requestMethod = "GET"; connection.doOutput = false
            val remaining = (end - elapsedMs()).coerceIn(1, 10_000).toInt()
            connection.connectTimeout = remaining; connection.readTimeout = remaining
            connection.setRequestProperty("Accept", "*/*")
            if (origin != null) connection.setRequestProperty("Origin", origin)
            checkCurrent()
            val status = connection.responseCode
            checkCurrent()
            if (connection.url.toExternalForm() != URI) throw Refusal(AbemaApprovalRegionFailure.REFUSED_REQUEST)
            if (status in 300..399) throw Refusal(AbemaApprovalRegionFailure.REDIRECT_REFUSED)
            if (status !in 100..599) throw Refusal(AbemaApprovalRegionFailure.INVALID_RESPONSE)
            val allowOrigin = connection.getHeaderField("Access-Control-Allow-Origin")
            val headers = when {
                allowOrigin == null -> emptyMap()
                allowOrigin == "*" || allowOrigin in origins -> mapOf("Access-Control-Allow-Origin" to allowOrigin)
                else -> throw Refusal(AbemaApprovalRegionFailure.INVALID_RESPONSE)
            }
            checkCurrent()
            result = AbemaApprovalRegionResult.Reply(status, headers)
        } catch (error: Refusal) {
            result = AbemaApprovalRegionResult.Failure(error.category)
        } catch (_: Throwable) {
            result = AbemaApprovalRegionResult.Failure(currentFailure(began, end) ?: AbemaApprovalRegionFailure.TRANSPORT_FAILED)
        } finally {
            watchdog?.cancel(false)
            val confirmed = handle?.receipt?.await() != false
            if (!confirmed || synchronized(lock) { poisoned }) {
                poison(handle)
                result = AbemaApprovalRegionResult.Failure(AbemaApprovalRegionFailure.CLEANUP_FAILED)
            } else {
                currentFailure(began, end)?.let { result = AbemaApprovalRegionResult.Failure(it) }
            }
            synchronized(lock) { if (!poisoned && active === handle) active = null; busy = false }
            done.countDown()
        }
        return result
    }

    // Nonblocking cancellation for lifecycle callbacks; bounded confirmation
    // runs on the cleanup worker before its owner releases the RouteSession.
    fun cancel() {
        val pending = synchronized(lock) { closed = true; active }
        pending?.receipt?.start()
    }
    fun closeConfirmed(): Boolean {
        cancel()
        val done = synchronized(lock) { completed }
        val finished = try { done?.await(cleanupWaitMs, TimeUnit.MILLISECONDS) != false }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        val pending = synchronized(lock) { active }
        if (!finished || pending?.receipt?.await() == false) poison(pending)
        return cleanupConfirmed
    }
    override fun toString() = "AbemaApprovalRegionCheck(redacted)"
}
