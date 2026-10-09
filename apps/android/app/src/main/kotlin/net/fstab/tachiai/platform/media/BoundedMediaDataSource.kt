package net.fstab.tachiai.platform.media

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.net.CookieHandler
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

@UnstableApi
internal class BoundedMediaRequests(
    private val budget: NativePlaybackBudget,
    private val allowedUri: (URI) -> Boolean,
    private val onEvent: (NativeMediaEvent, Int) -> Unit,
    private val canRequest: () -> Boolean = { true },
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onManifestRejection: (Int, String) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val active = Collections.newSetFromMap(ConcurrentHashMap<HttpsURLConnection, Boolean>())
    private val reported = Collections.newSetFromMap(ConcurrentHashMap<NativeMediaEvent, Boolean>())
    fun report(event: NativeMediaEvent, http: Int = 0) {
        if (reported.add(event)) onEvent(event, http)
    }
    fun create(type: Int): Source = Source(type)
    override fun close() { budget.stop(); active.forEach { it.disconnect() }; active.clear() }

    internal inner class Source(private val type: Int) : BaseDataSource(true) {
        private var connection: HttpsURLConnection? = null
        private var input: InputStream? = null
        private var source: URI? = null
        private var readBytes = 0L
        private var remaining = C.LENGTH_UNSET.toLong()
        private var requestStarted = 0L
        private var responseHttp = 0
        private var transferOpen = false
        private val limit get() = if (type == C.DATA_TYPE_MANIFEST) 512 * 1024L else 32 * 1024 * 1024L
        private fun checkActive() {
            budget.check()
            if (!canRequest() || clockMs() - requestStarted >= 30_000) throw IOException("Playback acceptance ended")
        }

        override fun getUri(): Uri? = source?.toString()?.toUri()
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        override fun open(dataSpec: DataSpec): Long {
            try {
                require(dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET && dataSpec.httpBody == null &&
                    dataSpec.httpRequestHeaders.isEmpty())
                transferInitializing(dataSpec)
                return openUri(URI(dataSpec.uri.toString()), dataSpec.position, dataSpec.length, dataSpec)
            } catch (_: Exception) { throw IOException("Native media request failed") }
        }

        // Android-free seam for deterministic transport tests, not a public player API.
        internal fun openUri(uri: URI, position: Long = 0, length: Long = C.LENGTH_UNSET.toLong(),
            transferSpec: DataSpec? = null): Long {
            try {
                check(connection == null && !transferOpen)
                requestStarted = clockMs()
                checkActive()
                if (type == C.DATA_TYPE_DRM) {
                    report(NativeMediaEvent.ENCRYPTION_UNSUPPORTED); throw IOException()
                }
                if (!allowedUri(uri)) { report(NativeMediaEvent.SOURCE_NOT_ALLOWLISTED); throw IOException() }
                require(position >= 0 && (length == C.LENGTH_UNSET.toLong() || length > 0))
                // Never inherit a process-wide cookie store. WebView uses its own profile.
                require(CookieHandler.getDefault() == null)
                val request = openConnection(uri.toURL())
                connection = request
                active.add(request)
                checkActive()
                request.instanceFollowRedirects = false // All redirects fail closed, before any next hop.
                request.connectTimeout = 5_000
                request.readTimeout = 10_000
                request.useCaches = false
                request.requestMethod = "GET"
                request.setRequestProperty("Accept-Encoding", "identity")
                // No default HTTP datasource, cookies, OAuth/client headers or WebView profile.
                if (position != 0L || length != C.LENGTH_UNSET.toLong()) {
                    val end = if (length == C.LENGTH_UNSET.toLong()) "" else
                        Math.addExact(position, length - 1).toString()
                    request.setRequestProperty("Range", "bytes=$position-$end")
                }
                val status = request.responseCode
                responseHttp = status
                checkActive()
                if (status !in listOf(200, 206)) {
                    report(NativeMediaEvent.HTTP_REJECTED, status)
                    // Only bounded manifest-404 classification. No redirects, raw
                    // body logging, decoder exposure or provider knowledge here.
                    if (type == C.DATA_TYPE_MANIFEST && status == 404 && request.contentLengthLong <= 4096) {
                        val body = request.errorStream?.use { errorInput ->
                            val bytes = ByteArrayOutputStream()
                            val buffer = ByteArray(512)
                            while (true) {
                                checkActive()
                                val count = errorInput.read(buffer)
                                checkActive()
                                if (count < 0) break
                                if (bytes.size() + count > 4096) throw IOException()
                                bytes.write(buffer, 0, count)
                            }
                            bytes.toString("UTF-8")
                        } ?: ""
                        checkActive()
                        onManifestRejection(status, body)
                    }
                    throw IOException()
                }
                if (position != 0L && status != 206) throw IOException()
                if (request.contentLengthLong > limit || length > limit) {
                    report(NativeMediaEvent.LIMIT_REACHED); throw IOException()
                }
                source = uri
                input = request.inputStream
                readBytes = 0
                remaining = if (length != C.LENGTH_UNSET.toLong()) length else request.contentLengthLong
                // Only real Media3 opens report transfers; openUri remains the
                // Android-free transport seam used by existing policy fixtures.
                if (transferSpec != null) {
                    transferOpen = true
                    transferStarted(transferSpec)
                }
                return remaining
            } catch (_: Exception) {
                close(); report(NativeMediaEvent.REQUEST_FAILED)
                // Neither URI nor provider exception is attached as a cause.
                throw IOException("Native media request failed")
            }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            try {
                checkActive()
                if (remaining == 0L) return C.RESULT_END_OF_INPUT
                val count = checkNotNull(input).read(buffer, offset,
                    if (remaining < 0) length else minOf(length.toLong(), remaining).toInt())
                checkActive()
                if (count < 0) return C.RESULT_END_OF_INPUT
                readBytes += count
                if (readBytes > limit) { report(NativeMediaEvent.LIMIT_REACHED); throw IOException() }
                if (remaining >= 0) remaining -= count
                if (transferOpen && count > 0) bytesTransferred(count)
                report(if (type == C.DATA_TYPE_MANIFEST) NativeMediaEvent.MANIFEST_BYTES else NativeMediaEvent.MEDIA_BYTES, responseHttp)
                return count
            } catch (_: Exception) {
                close()
                report(NativeMediaEvent.REQUEST_FAILED)
                throw IOException("Native media read failed")
            }
        }
        override fun close() {
            val request = connection
            connection = null
            try { input?.close() } catch (_: Exception) { /* no raw exception */ }
            input = null
            try {
                request?.disconnect()
            } finally {
                if (request != null) active.remove(request)
                source = null
                if (transferOpen) {
                    transferOpen = false
                    transferEnded()
                }
            }
        }
    }
}
