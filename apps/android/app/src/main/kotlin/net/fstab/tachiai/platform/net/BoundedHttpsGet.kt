package net.fstab.tachiai.platform.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

// One bounded public GET through the caller's connection factory (the feed's
// route). The caller has already checked the exact URI. No redirects, caches,
// credentials or logging; a non-200 status returns null.
internal fun boundedHttpsGet(open: (URL) -> HttpsURLConnection, uri: URI, limit: Int, accept: String): ByteArray? {
    require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null && uri.port in listOf(-1, 443))
    require(limit in 1..(8 * 1024 * 1024))
    val connection = open(uri.toURL())
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.useCaches = false
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", accept)
        if (connection.responseCode != 200) return null
        if (connection.contentLengthLong > limit) throw IOException("Response too large")
        return connection.inputStream.use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > limit) throw IOException("Response too large")
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
    } finally { connection.disconnect() }
}
