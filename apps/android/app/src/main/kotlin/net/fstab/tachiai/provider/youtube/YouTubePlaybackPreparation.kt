package net.fstab.tachiai.provider.youtube

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.CookieHandler
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

internal enum class YouTubePreparationPhase {
    COOKIE_HANDLER_REJECTED, METHOD_REJECTED, DESTINATION_REJECTED, HTTP_RESPONSE, RESOLVED,
}
internal data class YouTubePreparationDiagnostic(
    val phase: YouTubePreparationPhase,
    val httpStatus: Int? = null,
    val rejectedHost: String? = null,
)

internal fun resolveYouTubePlayback(
    videoId: String,
    current: () -> Boolean,
    onDiagnostic: (YouTubePreparationDiagnostic) -> Unit = {},
): Pair<URI, Boolean> {
    require(validYouTubeVideoId(videoId))
    return YouTubeExtractionScope(onDiagnostic, current).use { YouTubeExtractor.resolve(it, videoId) }
}

internal fun allowedYouTubeMediaUri(uri: URI): Boolean = youtubeHttps(uri) && youtubeDomain(uri.host, "googlevideo.com")
private fun youtubeHttps(uri: URI): Boolean = uri.scheme.equals("https", true) &&
    uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null && uri.port in listOf(-1, 443)
private fun youtubeDomain(host: String, domain: String): Boolean = host.equals(domain, true) || host.endsWith(".$domain", true)
private fun allowedYouTubeExtractionUri(uri: URI): Boolean = youtubeHttps(uri) &&
    (listOf("youtube.com", "youtube-nocookie.com", "googlevideo.com", "ytimg.com", "ggpht.com")
        .any { youtubeDomain(uri.host, it) } || uri.host.equals("youtubei.googleapis.com", true))

// Public extraction only. Each worker binds its cancellation/budget scope to
// the shared downloader; credentials and signed source URLs never enter logs.
internal object YouTubeExtractor {
    private val scope = ThreadLocal<YouTubeExtractionScope>()
    init {
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response =
                (scope.get() ?: throw IOException("No extraction owner")).execute(request)
        })
    }
    // NewPipe owns mutable process-wide player-script/client caches. Serialize
    // extraction, while each feed retains its own requests and native player.
    @Synchronized
    fun resolve(owner: YouTubeExtractionScope, videoId: String): Pair<URI, Boolean> {
        scope.set(owner)
        try {
            owner.check()
            val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")
            owner.check()
            if (info.id != videoId || info.ageLimit > 0) throw IOException("Public source unavailable")
            val muxed = info.videoStreams.firstOrNull { stream ->
                stream.isUrl && stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                    runCatching { allowedYouTubeMediaUri(URI(stream.content)) }.getOrDefault(false)
            }
            if (muxed != null) {
                owner.report(YouTubePreparationPhase.RESOLVED)
                return URI(muxed.content) to true
            }
            val hls = info.hlsUrl.takeIf { it.isNotBlank() }?.let { URI(it) }
            if (hls != null && allowedYouTubeMediaUri(hls)) {
                owner.report(YouTubePreparationPhase.RESOLVED)
                return hls to false
            }
            throw IOException("Supported public stream unavailable")
        } finally { scope.remove() }
    }
}

internal class YouTubeExtractionScope(
    private val onDiagnostic: (YouTubePreparationDiagnostic) -> Unit = {},
    private val current: () -> Boolean,
) : AutoCloseable {
    private val lock = Any()
    private val connections = mutableSetOf<HttpsURLConnection>()
    private var stopped = false
    private val deadline = System.nanoTime() / 1_000_000 + 40_000
    fun report(phase: YouTubePreparationPhase, status: Int? = null, host: String? = null) {
        val publicHost = host?.takeIf { Regex("[A-Za-z0-9.-]{1,253}").matches(it) }
        runCatching { onDiagnostic(YouTubePreparationDiagnostic(phase, status, publicHost)) }
    }
    fun check() {
        if (synchronized(lock) { stopped } || !current() || Thread.currentThread().isInterrupted ||
            System.nanoTime() / 1_000_000 >= deadline) throw IOException("Extraction ended")
    }
    fun execute(request: Request): Response {
        check()
        if (CookieHandler.getDefault() != null) {
            report(YouTubePreparationPhase.COOKIE_HANDLER_REJECTED)
            throw IOException("Unexpected shared cookie handler")
        }
        if (request.httpMethod() !in setOf("GET", "HEAD", "POST")) {
            report(YouTubePreparationPhase.METHOD_REJECTED)
            throw IOException("Extraction method rejected")
        }
        var uri = URI(request.url())
        repeat(6) {
            if (!allowedYouTubeExtractionUri(uri)) {
                report(YouTubePreparationPhase.DESTINATION_REJECTED, host = uri.host)
                throw IOException("Extraction destination rejected")
            }
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            synchronized(lock) {
                if (stopped) { connection.disconnect(); throw IOException("Extraction ended") }
                connections.add(connection)
            }
            try {
                check()
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.requestMethod = request.httpMethod()
                if (request.headers().keys.none { it.equals("User-Agent", true) }) {
                    connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
                }
                for ((name, values) in request.headers()) for (value in values) connection.addRequestProperty(name, value)
                request.dataToSend()?.let { body ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                report(YouTubePreparationPhase.HTTP_RESPONSE, status)
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Redirect unavailable")
                    uri = uri.resolve(location)
                } else {
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.use { input ->
                        val output = ByteArrayOutputStream()
                        val chunk = ByteArray(16 * 1024)
                        while (true) {
                            check()
                            val count = input.read(chunk)
                            if (count < 0) break
                            if (output.size() + count > 8 * 1024 * 1024) throw IOException("Extraction response too large")
                            output.write(chunk, 0, count)
                        }
                        output.toString("UTF-8")
                    }.orEmpty()
                    val headers = connection.headerFields.filterKeys { it != null }
                    return Response(status, connection.responseMessage.orEmpty(), headers, body, uri.toString())
                }
            } finally {
                connection.disconnect()
                synchronized(lock) { connections.remove(connection) }
            }
        }
        throw IOException("Too many redirects")
    }
    override fun close() {
        val owned = synchronized(lock) { stopped = true; connections.toList() }
        owned.forEach { it.disconnect() }
    }
}
