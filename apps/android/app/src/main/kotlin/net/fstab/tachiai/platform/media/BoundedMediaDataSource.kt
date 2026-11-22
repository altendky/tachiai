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
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.network.ProgressingMediaResponse
import net.fstab.tachiai.platform.network.RoutedHttpsConnection
import net.fstab.tachiai.platform.network.ProviderTransportException
import net.fstab.tachiai.platform.network.ProviderTransportFailure

private class NativeMediaCleanupException : IOException("Native media cleanup unconfirmed")

// Media3 adds this metadata hint to progressive loads, including video. It is
// ignored by our transport; credentials and arbitrary request headers stay denied.
internal fun allowedNativeMediaHeaders(headers: Map<String, String>, allowIcyMetadata: Boolean): Boolean =
    headers.isEmpty() || allowIcyMetadata && headers.size == 1 && headers.entries.single().let {
        it.key.equals("Icy-MetaData", ignoreCase = true) && it.value == "1"
    }

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
    // Called only after this source confirmed a normal disconnect.
    private val onConnectionReleased: (HttpsURLConnection) -> Unit = {},
    private val readCosts: NativeMediaReadCosts? = null,
    private val coalesceMediaReads: Boolean = false,
    transportObserver: NativeMediaTransportObserver? = null,
    // #568: a live feed's in-memory rewind buffer, or null.
    private val cache: NativeLiveSegmentCache? = null,
) : AutoCloseable {
    private val active = Collections.newSetFromMap(ConcurrentHashMap<HttpsURLConnection, Boolean>())
    private data class ReportedEvent(val event: NativeMediaEvent, val status: Int = 0)
    private val reported = Collections.newSetFromMap(ConcurrentHashMap<ReportedEvent, Boolean>())
    private val transportDiagnostics = transportObserver?.let(::NativeMediaTransportDiagnostics)
    fun report(event: NativeMediaEvent, http: Int = 0) {
        // A later authentication refusal must not be hidden by an earlier
        // availability response. Other notifications retain per-event dedup.
        val status = if (event == NativeMediaEvent.HTTP_REJECTED) http.takeIf { it in 100..599 } ?: 0 else 0
        if (reported.add(ReportedEvent(event, status))) onEvent(event, http)
    }
    fun create(type: Int, mediaResponseLimitBytes: Long = 32 * 1024 * 1024L,
        allowIcyMetadata: Boolean = false, recorder: Boolean = false): Source =
        Source(type, mediaResponseLimitBytes, allowIcyMetadata, recorder)
    override fun close() {
        budget.stop()
        try {
            closeNativeResources(*active.toList().map { request ->
                { observeNativeFailure(NativeFailureStage.MEDIA_DISCONNECT, onFailure) { request.disconnect() } }
            }.toTypedArray())
        } finally { active.clear() }
    }

    internal inner class Source(private val type: Int, private val mediaResponseLimitBytes: Long,
        private val allowIcyMetadata: Boolean, private val recorder: Boolean = false) : BaseDataSource(true) {
        init { require(mediaResponseLimitBytes in 1..4L * 1024 * 1024 * 1024) }
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
        // A cached body served instead of the network, or a complete network
        // body being copied into the cache.
        private var cached: java.nio.ByteBuffer? = null
        private var lease: NativeLiveSegmentCache.Lease? = null
        private var tee: ByteArrayOutputStream? = null
        private var teeUri: URI? = null
        private var teeExpected = -1L
        private val diagnosticOwner = transportDiagnostics?.let { AtomicReference<Any?>() }
        private val limit get() = if (type == C.DATA_TYPE_MANIFEST) 512 * 1024L else mediaResponseLimitBytes
        private fun requestCancelled(error: Exception) = Thread.currentThread().isInterrupted ||
            error is NativeMediaRequestException && error.failure in setOf(NativeMediaRequestFailure.CANCELLED,
                NativeMediaRequestFailure.TRANSPORT_CANCELLED) ||
            error is ProviderTransportException && error.evidence.failure == ProviderTransportFailure.CANCELLED
        private fun checkActive() {
            if (Thread.currentThread().isInterrupted) throw NativeMediaRequestException(NativeMediaRequestFailure.CANCELLED)
            if (!budget.active) throw NativeMediaRequestException(NativeMediaRequestFailure.PLAYBACK_INACTIVE)
            // This boolean includes owner, acceptance deadline and provider
            // grant/admission checks. A refusal does not identify which failed.
            val admission = costs?.begin(NativeMediaReadCostPhase.ADMISSION)
            val admitted = try { canRequest() } finally { costs?.end(admission) }
            if (Thread.currentThread().isInterrupted) throw NativeMediaRequestException(NativeMediaRequestFailure.CANCELLED)
            if (!admitted) throw NativeMediaRequestException(NativeMediaRequestFailure.REQUEST_ADMISSION_REFUSED)
            if (!mediaBodyAccepted && clockMs() - requestStarted >= 30_000)
                throw NativeMediaRequestException(NativeMediaRequestFailure.REQUEST_DEADLINE)
        }

        override fun getUri(): Uri? = source?.toString()?.toUri()
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        override fun open(dataSpec: DataSpec): Long {
            val uri = try {
                require(dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET && dataSpec.httpBody == null &&
                    allowedNativeMediaHeaders(dataSpec.httpRequestHeaders, allowIcyMetadata))
                transferInitializing(dataSpec)
                URI(dataSpec.uri.toString())
            } catch (error: Exception) {
                if (requestCancelled(error)) report(NativeMediaEvent.REQUEST_CANCELLED)
                else reportNativeFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, onFailure,
                    nativeMediaRequestException(error, NativeMediaOpenPhase.REQUEST_SETUP))
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
            var openFailureEvent = NativeMediaEvent.REQUEST_SETUP_FAILED
            fun observeFailure(error: Exception) {
                if (failureObserved || error is NativeMediaCleanupException || requestCancelled(error)) return
                failureObserved = true
                val phase = when (openFailureEvent) {
                    NativeMediaEvent.CONNECTION_OPEN_FAILED -> NativeMediaOpenPhase.CONNECTION_OPEN
                    NativeMediaEvent.RESPONSE_OPEN_FAILED -> NativeMediaOpenPhase.RESPONSE_OPEN
                    NativeMediaEvent.RESPONSE_POLICY_FAILED -> NativeMediaOpenPhase.RESPONSE_POLICY
                    NativeMediaEvent.INPUT_OPEN_FAILED -> NativeMediaOpenPhase.INPUT_OPEN
                    else -> NativeMediaOpenPhase.REQUEST_SETUP
                }
                reportNativeFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, onFailure, nativeMediaRequestException(error, phase))
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
                responseHttp = 0
                requestStarted = clockMs()
                checkActive()
                if (type == C.DATA_TYPE_DRM) {
                    report(NativeMediaEvent.ENCRYPTION_UNSUPPORTED); throw IOException()
                }
                if (!allowedUri(uri)) { report(NativeMediaEvent.SOURCE_NOT_ALLOWLISTED); throw IOException() }
                require(position >= 0 && (length == C.LENGTH_UNSET.toLong() || length > 0))
                if (type == C.DATA_TYPE_MEDIA && cache != null) {
                    val leased = cache.lease(uri, player = !recorder)
                    if (leased != null) {
                        lease = leased
                        val hit = leased.body
                        val size = hit.remaining().toLong()
                        require(position <= size)
                        val end = if (length == C.LENGTH_UNSET.toLong()) size else minOf(size, Math.addExact(position, length))
                        hit.position(Math.toIntExact(position)); hit.limit(Math.toIntExact(end))
                        cached = hit
                        source = uri
                        readBytes = 0
                        inputEnded = false
                        remaining = end - position
                        if (transferSpec != null) {
                            transferOpen = true
                            transferStarted(transferSpec)
                        }
                        costs?.opened()
                        if (remaining == 0L) costs?.bodyComplete()
                        return remaining
                    }
                }
                // Never inherit a process-wide cookie store. WebView uses its own profile.
                require(CookieHandler.getDefault() == null)
                openFailureEvent = NativeMediaEvent.CONNECTION_OPEN_FAILED
                val request = openConnection(uri.toURL())
                connection = request
                onConnectionOwned(request)
                active.add(request)
                transportDiagnostics?.let { diagnostics ->
                    val owner = Any().also { diagnosticOwner?.set(it) }
                    (request as? RoutedHttpsConnection)?.observeAdditionalFailures { diagnostic ->
                        diagnostics.record(when (type) {
                            C.DATA_TYPE_MANIFEST -> NativeMediaRequestType.MANIFEST
                            C.DATA_TYPE_MEDIA -> NativeMediaRequestType.MEDIA
                            else -> NativeMediaRequestType.OTHER
                        }, diagnostic) { budget.active && diagnosticOwner?.get() === owner && active.contains(request) }
                    }
                }
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
                openFailureEvent = NativeMediaEvent.RESPONSE_OPEN_FAILED
                val headers = costs?.begin(NativeMediaReadCostPhase.HEADERS)
                val status = try { request.responseCode } finally { costs?.end(headers) }
                responseHttp = status
                openFailureEvent = NativeMediaEvent.RESPONSE_POLICY_FAILED
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
                    throw NativeMediaRequestException(NativeMediaRequestFailure.HTTP_REJECTED,
                        httpStatus = status.takeIf { it in 100..599 })
                }
                if (position != 0L && status != 206) {
                    report(NativeMediaEvent.RANGE_NOT_HONORED, status)
                    throw NativeMediaRequestException(NativeMediaRequestFailure.RANGE_NOT_HONORED)
                }
                if (request.contentLengthLong > limit || length > limit) {
                    report(NativeMediaEvent.LIMIT_REACHED)
                    throw NativeMediaRequestException(NativeMediaRequestFailure.RESPONSE_LIMIT)
                }
                source = uri
                openFailureEvent = NativeMediaEvent.INPUT_OPEN_FAILED
                val acquireInput = costs?.begin(NativeMediaReadCostPhase.INPUT_OPEN)
                input = try { openInput(request) } finally { costs?.end(acquireInput) }
                readBytes = 0
                inputEnded = false
                remaining = if (length != C.LENGTH_UNSET.toLong()) length else request.contentLengthLong
                if (type == C.DATA_TYPE_MEDIA && cache != null && position == 0L &&
                    length == C.LENGTH_UNSET.toLong() && status == 200 && cache.accepts(uri)) {
                    teeExpected = request.contentLengthLong
                    tee = ByteArrayOutputStream(if (teeExpected in 1..limit) teeExpected.toInt() else 256 * 1024)
                    teeUri = uri
                }
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
                val cancelled = requestCancelled(error)
                openingCosts?.openFailed()
                observeFailure(error)
                if (!cancelled) runCatching { report(openFailureEvent, responseHttp) }
                close(); report(if (cancelled) NativeMediaEvent.REQUEST_CANCELLED else NativeMediaEvent.REQUEST_FAILED)
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
                cached?.let { body ->
                    if (!body.hasRemaining()) {
                        costs?.bodyComplete(); costs?.readFinished(reading, eof = true)
                        return C.RESULT_END_OF_INPUT
                    }
                    val count = minOf(length, body.remaining())
                    body.get(buffer, offset, count)
                    readBytes += count
                    if (remaining >= 0) remaining -= count
                    if (transferOpen) bytesTransferred(count)
                    report(NativeMediaEvent.MEDIA_BYTES, 200)
                    if (remaining == 0L) costs?.bodyComplete()
                    costs?.readFinished(reading)
                    return count
                }
                if (remaining == 0L || inputEnded) {
                    if (remaining <= 0L) costs?.bodyComplete()
                    finishTee()
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
                    finishTee()
                    costs?.readFinished(reading, eof = true)
                    return C.RESULT_END_OF_INPUT
                }
                readBytes += count
                if (readBytes > limit) {
                    report(NativeMediaEvent.LIMIT_REACHED)
                    throw NativeMediaRequestException(NativeMediaRequestFailure.RESPONSE_LIMIT)
                }
                if (remaining >= 0) remaining -= count
                if (count > 0) tee?.write(buffer, offset, count)
                if (remaining == 0L) finishTee()
                if (transferOpen && count > 0) bytesTransferred(count)
                report(if (type == C.DATA_TYPE_MANIFEST) NativeMediaEvent.MANIFEST_BYTES else NativeMediaEvent.MEDIA_BYTES, responseHttp)
                if (remaining == 0L || (inputEnded && remaining < 0L)) costs?.bodyComplete()
                costs?.readFinished(reading)
                return count
            } catch (error: Exception) {
                val cancelled = requestCancelled(error)
                costs?.readFinished(reading, error = true)
                if (!cancelled) reportNativeFailure(NativeFailureStage.MEDIA_REQUEST_READ, onFailure, nativeMediaRequestException(error))
                close()
                report(if (cancelled) NativeMediaEvent.REQUEST_CANCELLED else NativeMediaEvent.REQUEST_FAILED)
                throw IOException("Native media read failed")
            }
        }

        // Only a complete, unranged body enters the cache.
        private fun finishTee() {
            val body = tee ?: return
            val uri = teeUri
            tee = null; teeUri = null
            if (uri != null && (teeExpected < 0 || body.size().toLong() == teeExpected)) cache?.put(uri, body.toByteArray())
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
            diagnosticOwner?.set(null)
            cached = null; tee = null; teeUri = null
            lease?.close(); lease = null
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
                if (disconnected) {
                    connection = null
                    if (request != null) { active.remove(request); onConnectionReleased(request) }
                }
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
