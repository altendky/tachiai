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
import net.fstab.tachiai.platform.network.ProgressingMediaResponse

private class NativeMediaCleanupException : IOException("Native media cleanup unconfirmed")

@UnstableApi
internal class BoundedMediaRequests(
    private val budget: NativePlaybackBudget,
    private val allowedUri: (URI) -> Boolean,
    private val onEvent: (NativeMediaEvent, Int) -> Unit,
    private val canRequest: () -> Boolean = { true },
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onManifestRejection: (Int, String) -> Unit = { _, _ -> },
    private val onFailure: NativeFailureObserver = { _, _ -> },
    private val onConnectionOwned: (HttpsURLConnection) -> Unit = {},
    private val openInput: (HttpsURLConnection) -> InputStream = { it.inputStream },
    private val openErrorInput: (HttpsURLConnection) -> InputStream? = { it.errorStream },
    private val readCosts: NativeMediaReadCosts? = null,
    private val coalesceMediaReads: Boolean = false,
) : AutoCloseable {
    private val active = Collections.newSetFromMap(ConcurrentHashMap<HttpsURLConnection, Boolean>())
    private val reported = Collections.newSetFromMap(ConcurrentHashMap<NativeMediaEvent, Boolean>())
    fun report(event: NativeMediaEvent, http: Int = 0) {
        if (reported.add(event)) onEvent(event, http)
    }
    fun create(type: Int): Source = Source(type)
    override fun close() {
        budget.stop()
        try {
            closeNativeResources(*active.toList().map { request ->
                { observeNativeFailure(NativeFailureStage.MEDIA_DISCONNECT, onFailure) { request.disconnect() } }
            }.toTypedArray())
        } finally { active.clear() }
    }

    internal inner class Source(private val type: Int) : BaseDataSource(true) {
        private var connection: HttpsURLConnection? = null
        private var input: InputStream? = null
        private var source: URI? = null
        private var readBytes = 0L
        private var remaining = C.LENGTH_UNSET.toLong()
        private var requestStarted = 0L
        private var responseHttp = 0
        private var transferOpen = false
        private var mediaBodyAccepted = false
        private var inputEnded = false
        private var costs: NativeMediaReadCosts.Request? = null
        private val limit get() = if (type == C.DATA_TYPE_MANIFEST) 512 * 1024L else 32 * 1024 * 1024L
        private fun checkActive() {
            if (!budget.active) throw NativeMediaRequestException(NativeMediaRequestFailure.PLAYBACK_INACTIVE)
            // This boolean includes owner, acceptance deadline and provider
            // grant/admission checks. A refusal does not identify which failed.
            val admission = costs?.begin(NativeMediaReadCostPhase.ADMISSION)
            val admitted = try { canRequest() } finally { costs?.end(admission) }
            if (!admitted) throw NativeMediaRequestException(NativeMediaRequestFailure.REQUEST_ADMISSION_REFUSED)
            if (!mediaBodyAccepted && clockMs() - requestStarted >= 30_000)
                throw NativeMediaRequestException(NativeMediaRequestFailure.REQUEST_DEADLINE)
        }

        override fun getUri(): Uri? = source?.toString()?.toUri()
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        override fun open(dataSpec: DataSpec): Long {
            val uri = try {
                require(dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET && dataSpec.httpBody == null &&
                    dataSpec.httpRequestHeaders.isEmpty())
                transferInitializing(dataSpec)
                URI(dataSpec.uri.toString())
            } catch (error: Exception) {
                reportNativeFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, onFailure, nativeMediaRequestException(error))
                throw IOException("Native media request failed")
            }
            return try { openUri(uri, dataSpec.position, dataSpec.length, dataSpec) }
            catch (_: Exception) { throw IOException("Native media request failed") }
        }

        // Android-free seam for deterministic transport tests, not a public player API.
        internal fun openUri(uri: URI, position: Long = 0, length: Long = C.LENGTH_UNSET.toLong(),
            transferSpec: DataSpec? = null): Long {
            var openingCosts: NativeMediaReadCosts.Request? = null
            var failureObserved = false
            fun observeFailure(error: Exception) {
                if (failureObserved || error is NativeMediaCleanupException) return
                failureObserved = true
                reportNativeFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, onFailure, nativeMediaRequestException(error))
            }
            try {
                check(connection == null && !transferOpen)
                openingCosts = readCosts?.open(when (type) {
                    C.DATA_TYPE_MANIFEST -> NativeMediaReadCostType.MANIFEST
                    C.DATA_TYPE_MEDIA -> NativeMediaReadCostType.MEDIA
                    else -> NativeMediaReadCostType.OTHER
                })
                costs = openingCosts
                mediaBodyAccepted = false
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
                onConnectionOwned(request)
                active.add(request)
                checkActive()
                if (type == C.DATA_TYPE_MEDIA) (request as? ProgressingMediaResponse)?.requestProgressingBody()
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
                val headers = costs?.begin(NativeMediaReadCostPhase.HEADERS)
                val status = try { request.responseCode } finally { costs?.end(headers) }
                responseHttp = status
                checkActive()
                if (status !in listOf(200, 206)) {
                    report(NativeMediaEvent.HTTP_REJECTED, status)
                    // Only bounded manifest-404 classification. No redirects, raw
                    // body logging, decoder exposure or provider knowledge here.
                    if (type == C.DATA_TYPE_MANIFEST && status == 404 && request.contentLengthLong <= 4096) {
                        val errorInput = openErrorInput(request)
                        val body = errorInput?.let {
                            try {
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
                            } catch (error: Exception) {
                                // Observe the read before its finally block can
                                // replace it with an independent cleanup error.
                                observeFailure(error)
                                throw error
                            } finally {
                                try { observeNativeFailure(NativeFailureStage.MEDIA_DISCONNECT, onFailure) { errorInput.close() } }
                                catch (_: Throwable) { throw NativeMediaCleanupException() }
                            }
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
                val acquireInput = costs?.begin(NativeMediaReadCostPhase.INPUT_OPEN)
                input = try { openInput(request) } finally { costs?.end(acquireInput) }
                readBytes = 0
                inputEnded = false
                remaining = if (length != C.LENGTH_UNSET.toLong()) length else request.contentLengthLong
                checkActive() // Setup is still bounded, including input acquisition.
                if (type == C.DATA_TYPE_MEDIA) {
                    (request as? ProgressingMediaResponse)?.acceptProgressingBody()
                    mediaBodyAccepted = true
                    checkActive()
                }
                // Only real Media3 opens report transfers; openUri remains the
                // Android-free transport seam used by existing policy fixtures.
                if (transferSpec != null) {
                    transferOpen = true
                    transferStarted(transferSpec)
                }
                costs?.opened()
                if (remaining == 0L) costs?.bodyComplete()
                return remaining
            } catch (error: Exception) {
                openingCosts?.openFailed()
                observeFailure(error)
                close(); report(NativeMediaEvent.REQUEST_FAILED)
                // Neither URI nor provider exception is attached as a cause.
                throw IOException("Native media request failed")
            }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val reading = costs?.beginRead(length)
            try {
                require(offset >= 0 && length > 0 && offset <= buffer.size - length)
                val coalesce = coalesceMediaReads && type == C.DATA_TYPE_MEDIA
                if (coalesce) checkReadActivity()
                checkActive()
                if (remaining == 0L || inputEnded) {
                    if (remaining <= 0L) costs?.bodyComplete()
                    costs?.readFinished(reading, eof = true)
                    return C.RESULT_END_OF_INPUT
                }
                val requested = if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()
                val count = if (coalesce) readMediaBatch(buffer, offset, requested)
                    else readInput(buffer, offset, requested)
                checkActive()
                if (coalesce) checkReadActivity()
                if (count < 0) {
                    if (remaining < 0) costs?.bodyComplete()
                    costs?.readFinished(reading, eof = true)
                    return C.RESULT_END_OF_INPUT
                }
                readBytes += count
                if (readBytes > limit) { report(NativeMediaEvent.LIMIT_REACHED); throw IOException() }
                if (remaining >= 0) remaining -= count
                if (transferOpen && count > 0) bytesTransferred(count)
                report(if (type == C.DATA_TYPE_MANIFEST) NativeMediaEvent.MANIFEST_BYTES else NativeMediaEvent.MEDIA_BYTES, responseHttp)
                if (remaining == 0L || (inputEnded && remaining < 0L)) costs?.bodyComplete()
                costs?.readFinished(reading)
                return count
            } catch (error: Exception) {
                costs?.readFinished(reading, error = true)
                reportNativeFailure(NativeFailureStage.MEDIA_REQUEST_READ, onFailure, nativeMediaRequestException(error))
                close()
                report(NativeMediaEvent.REQUEST_FAILED)
                throw IOException("Native media read failed")
            }
        }

        private fun readInput(buffer: ByteArray, offset: Int, length: Int): Int {
            val inputReading = costs?.beginInput(length)
            val count = try { checkNotNull(input).read(buffer, offset, length) }
                finally { costs?.end(inputReading) }
            costs?.inputReturned(count)
            return count
        }

        private fun checkReadActivity() {
            if (!budget.active) throw NativeMediaRequestException(NativeMediaRequestFailure.PLAYBACK_INACTIVE)
            if (Thread.currentThread().isInterrupted)
                throw NativeMediaRequestException(NativeMediaRequestFailure.CANCELLED)
        }

        private fun readMediaBatch(buffer: ByteArray, offset: Int, length: Int): Int {
            // Fill only the caller's current buffer: no read-ahead or retained
            // authorization. Fresh durable admission brackets the entire batch,
            // before any accepted-byte callback or return to Media3.
            val requested = minOf(length, 16 * 1024)
            val started = clockMs()
            var total = 0
            repeat(16) {
                checkReadActivity()
                // Bounds starting another fragment, not an in-flight socket
                // read (which retains the existing ten-second timeout).
                if (total > 0 && clockMs() - started !in 0L until 100L) return total
                val count = readInput(buffer, offset + total, requested - total)
                checkReadActivity()
                if (count < 0) {
                    inputEnded = true
                    return if (total == 0) C.RESULT_END_OF_INPUT else total
                }
                if (count == 0) return total
                total += count
                if (total == requested) return total
            }
            return total
        }
        override fun close() {
            val request = connection
            val stream = input
            var streamClosed = stream == null
            var disconnected = request == null
            var cleanupFailed = false
            try { closeNativeResources(
                { observeNativeFailure(NativeFailureStage.MEDIA_DISCONNECT, onFailure) {
                    stream?.close(); streamClosed = true
                } },
                { observeNativeFailure(NativeFailureStage.MEDIA_DISCONNECT, onFailure) {
                    request?.disconnect(); disconnected = true
                } },
                {
                if (streamClosed) input = null
                if (disconnected) { connection = null; if (request != null) active.remove(request) }
                source = null
                mediaBodyAccepted = false
                if (transferOpen) {
                    transferOpen = false
                    transferEnded()
                }
                },
            ) } catch (_: Throwable) {
                cleanupFailed = true
                // Loader cleanup catches IOException only. Keep the original
                // privately observed/retained; never let its Error/text escape.
                throw NativeMediaCleanupException()
            } finally {
                costs?.close(error = cleanupFailed || !streamClosed || !disconnected)
                costs = null
            }
        }
    }
}
