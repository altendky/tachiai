package net.fstab.tachiai.platform.network

import android.app.Application
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import net.fstab.tachiai.provider.abema.account.AbemaAccountCleanupReceipt
import org.junit.Assume.assumeTrue
import org.junit.Test

// Provider-free JNI admission/cleanup proof only. Default runs skip before any
// resource or private state. No saved owner/grant reads or writes, provider
// request, browser, global proxy override or authenticated allowed CONNECT.
class AbemaSystemApprovalBindingDeviceTest {
    companion object {
        private const val LOG_TAG = "TachiaiAbemaSystemBridgeProbe"
        private const val CASE_COUNT = 9
        // Uncertain closes retain the actual resources until process exit.
        private val retained = mutableListOf<Owned>()
    }
    private enum class Failure { INITIALIZATION, ADMISSION, RESPONSE, DEADLINE, LISTENER_OPEN, CLEANUP }
    private class ClosedFailure(val category: Failure) : RuntimeException()
    private class Owned {
        val preparation = RoutePreparation()
        var backend: AbemaAccountCleanupReceipt<RouteBackend>? = null
        var route: RouteSession? = null
        var routeClose: AbemaAccountCleanupReceipt<RouteSession>? = null
        var repeatedBackendClose: AbemaAccountCleanupReceipt<RouteBackend>? = null
        val sockets = mutableListOf<AbemaAccountCleanupReceipt<Socket>>()

        fun own(socket: Socket): AbemaAccountCleanupReceipt<Socket> =
            AbemaAccountCleanupReceipt(socket, Socket::close).also { sockets.add(it) }

        fun closeConfirmed(): Boolean {
            sockets.forEach { it.start() }
            var confirmed = true
            sockets.forEach { if (!it.await()) confirmed = false }
            if (routeClose?.await() == false) confirmed = false
            if (backend?.await() == false) confirmed = false
            if (repeatedBackendClose?.await() == false) confirmed = false
            return confirmed && preparation.cleanupConfirmed
        }
    }

    @Test fun optInSystemBridgeRejectsSyntheticConnectsAndClosesWithoutProviderTraffic() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tachiaiReadOnlyAbemaSystemBridge") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == instrumentation.targetContext.packageName)
        assumeTrue(Looper.myLooper() != Looper.getMainLooper())
        val verifiedAvd = try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("getprop ro.boot.qemu.avd_name")).use { input ->
                val bytes = ByteArray(129); var size = 0
                while (size < bytes.size) {
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                size <= 128 && String(bytes, 0, size, Charsets.UTF_8).trim() == "tachiai-dev"
            }
        } catch (_: Throwable) { false }
        assumeTrue("Requires verified tachiai-dev AVD", verifiedAvd)
        try { observe() }
        catch (error: ClosedFailure) { throw AssertionError("SYSTEM_BRIDGE_FAILURE_${error.category.name}") }
        catch (_: Throwable) {
            // Never retain a cause or print request fields/native exceptions.
            throw AssertionError("SYSTEM_BRIDGE_FAILURE_INITIALIZATION")
        }
    }

    private fun observe() {
        val owned = Owned()
        val began = SystemClock.elapsedRealtime()
        val deadline = began + 30_000
        fun current() {
            val now = SystemClock.elapsedRealtime()
            if (now < began || now >= deadline) throw ClosedFailure(Failure.DEADLINE)
        }
        try {
            owned.route = RouteSession.createAbemaApprovalBrowser(null, owned.preparation,
                ownBackend = { backend ->
                    val receipt = AbemaAccountCleanupReceipt(backend, RouteBackend::close)
                    owned.backend = receipt
                    object : RouteBackend {
                        override val proxyPort get() = backend.proxyPort
                        override fun close() { check(receipt.await()) }
                    }
                })
            val route = checkNotNull(owned.route)
            owned.routeClose = AbemaAccountCleanupReceipt(route, RouteSession::close)
            if (!route.isSystem || !route.hasProxyBridge || !route.isAbemaApprovalBridge ||
                route.proxyPort !in 1..65535 ||
                !route.matchesProxyChallenge("127.0.0.1", route.proxyRealm))
                throw ClosedFailure(Failure.INITIALIZATION)
            current()
            val authBytes = "${route.proxyUsername}:${route.proxyPassword}".toByteArray(Charsets.US_ASCII)
            val auth = try { "Basic " + Base64.getEncoder().encodeToString(authBytes) }
                finally { authBytes.fill(0) }
            var completed = 0
            fun refused(target: String, credentials: List<String>, expected: Int) {
                current()
                if (completed >= CASE_COUNT) throw ClosedFailure(Failure.ADMISSION)
                val status = connectStatus(owned, route.proxyPort, target, credentials, deadline)
                if (status != expected) throw ClosedFailure(Failure.ADMISSION)
                completed++
                current()
            }
            // Authentication must reject these before destination admission.
            refused("abema.tv:443", emptyList(), 407)
            refused("abema.tv:443", listOf("Basic c3ludGhldGljOndyb25n"), 407)
            refused("abema.tv:443", listOf(auth, auth), 407)
            // Every correctly authenticated case has a refused destination.
            // A successful provider tunnel is deliberately never requested.
            for (target in listOf("fixture.invalid:443", "127.0.0.1:443", "[::1]:443",
                "abema-tv.com:443", "api.abema-tv.com.invalid:443", "abema.tv:80"))
                refused(target, listOf(auth), 403)
            if (completed != CASE_COUNT) throw ClosedFailure(Failure.ADMISSION)

            if (owned.routeClose?.await() != true || owned.backend?.confirmed != true ||
                !owned.preparation.cleanupConfirmed) throw ClosedFailure(Failure.CLEANUP)
            // Exercise the actual JNI backend's idempotent native close, rather
            // than only RouteSession's already-closed Kotlin guard.
            val backend = checkNotNull(owned.backend).resource
            owned.repeatedBackendClose = AbemaAccountCleanupReceipt(backend, RouteBackend::close)
            if (owned.repeatedBackendClose?.await() != true) throw ClosedFailure(Failure.CLEANUP)
            val socket = Socket()
            val receipt = owned.own(socket)
            try {
                current()
                try {
                    socket.connect(InetSocketAddress("127.0.0.1", route.proxyPort), 500)
                    throw ClosedFailure(Failure.LISTENER_OPEN)
                } catch (_: ConnectException) {
                    // Local refusal confirms the ephemeral listener is gone.
                }
                current()
            } finally {
                if (!receipt.await()) throw ClosedFailure(Failure.CLEANUP)
            }
        } finally {
            if (!runCatching { owned.closeConfirmed() }.getOrDefault(false)) {
                synchronized(retained) { retained.add(owned) }
                throw ClosedFailure(Failure.CLEANUP)
            }
        }
        current()
        Log.i(LOG_TAG, "SYSTEM_APPROVAL_BRIDGE_TRUE AUTH_REFUSALS_3 DESTINATION_REFUSALS_6 " +
            "LISTENER_CLOSED_TRUE NATIVE_REPEATED_CLOSE_TRUE CLEANUP_CONFIRMED_TRUE")
    }

    private fun connectStatus(owned: Owned, port: Int, target: String, credentials: List<String>,
        overallDeadline: Long): Int {
        val socket = Socket()
        val receipt = owned.own(socket)
        val deadline = minOf(overallDeadline, SystemClock.elapsedRealtime() + 2_000)
        fun remaining(): Int = (deadline - SystemClock.elapsedRealtime()).let {
            if (it <= 0) throw ClosedFailure(Failure.DEADLINE)
            it.coerceAtMost(2_000).toInt()
        }
        val request = buildString {
            append("CONNECT "); append(target); append(" HTTP/1.1\r\nHost: ")
            append(target); append("\r\n")
            credentials.forEach { append("Proxy-Authorization: "); append(it); append("\r\n") }
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        val response = ByteArray(128)
        try {
            if (request.size > 512) throw ClosedFailure(Failure.ADMISSION)
            socket.connect(InetSocketAddress("127.0.0.1", port), minOf(500, remaining()))
            socket.getOutputStream().write(request)
            val input = socket.getInputStream()
            var size = 0
            while (size < response.size) {
                socket.soTimeout = remaining()
                val value = input.read()
                if (value < 0 || value !in 9..126) throw ClosedFailure(Failure.RESPONSE)
                response[size++] = value.toByte()
                if (value == '\n'.code) {
                    if (size < 2 || response[size - 2] != '\r'.code.toByte())
                        throw ClosedFailure(Failure.RESPONSE)
                    val line = String(response, 0, size, Charsets.US_ASCII)
                    return when {
                        line == "HTTP/1.1 407 Proxy Authentication Required\r\n" -> 407
                        line == "HTTP/1.1 403 Forbidden\r\n" -> 403
                        else -> throw ClosedFailure(Failure.RESPONSE)
                    }
                }
            }
            throw ClosedFailure(Failure.RESPONSE)
        } catch (error: ClosedFailure) { throw error }
        catch (_: Throwable) { throw ClosedFailure(Failure.RESPONSE) }
        finally {
            request.fill(0); response.fill(0)
            if (!receipt.await()) throw ClosedFailure(Failure.CLEANUP)
        }
    }
}
