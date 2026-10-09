package net.fstab.tachiai.platform.network

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage

// Owned by a playback run, not a display position. The owner shares one instance
// for a repeated imported profile and closes it after every dependent feed stops.
// It never installs Android VpnService or changes the device's active VPN.
internal class RouteSession private constructor(
    private val backend: RouteBackend?,
    val proxyUsername: String,
    val proxyPassword: String,
    val proxyRealm: String,
    private val diagnostics: FailureReporter,
) : AutoCloseable {
    val isSystem get() = backend == null
    val proxyPort: Int = backend?.proxyPort ?: 0
    private val closed = AtomicBoolean()
    private val connections = Collections.newSetFromMap(ConcurrentHashMap<RoutedHttpsConnection, Boolean>())
    private val client: OkHttpClient? = if (backend == null) null else OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
        .proxyAuthenticator(routeProxyAuthenticator(proxyUsername, proxyPassword, proxyRealm, proxyPort))
        .authenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES)
        .connectionPool(ConnectionPool())
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .build()

    fun open(url: URL): HttpsURLConnection {
        if (closed.get()) throw IOException("Route is closed")
        require(url.protocol == "https" && url.userInfo == null && url.ref == null && url.port in listOf(-1, 443))
        if (isSystem) return url.openConnection() as HttpsURLConnection
        val connection = RoutedHttpsConnection(url, checkNotNull(client), { !closed.get() }) { connections.remove(it) }
        connections.add(connection)
        if (closed.get()) { connection.disconnect(); throw IOException("Route is closed") }
        return connection
    }

    fun matchesProxyChallenge(host: String, realm: String) = !isSystem && !closed.get() &&
        host == "127.0.0.1" && realm == proxyRealm

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failed = false
        for ((stage, cleanup) in listOf<Pair<FailureStage, () -> Unit>>(
            FailureStage.ROUTE_DISCONNECT to {
                connections.forEach { connection ->
                    if (!diagnostics.cleanupAll(FailureStage.ROUTE_DISCONNECT) { connection.disconnect() }) failed = true
                }
                connections.clear()
            },
            FailureStage.ROUTE_CANCEL_REQUESTS to { client?.dispatcher?.cancelAll() },
            FailureStage.ROUTE_CONNECTION_POOL to { client?.connectionPool?.evictAll() },
            FailureStage.ROUTE_EXECUTOR to { client?.dispatcher?.executorService?.shutdownNow() },
            FailureStage.ROUTE_BACKEND_CLOSE to { backend?.close() },
        )) if (!diagnostics.cleanupAll(stage, cleanup)) failed = true
        if (failed) throw IOException("Route cleanup failed")
    }

    override fun toString() = "RouteSession(${if (isSystem) "system" else "imported"}, secrets hidden)"

    companion object {
        // This CONNECT layer only admits the union of already-reviewed native
        // and helper HTTPS hosts. Provider adapters still enforce exact URI,
        // source identity, request method and media-origin policies above it.
        private const val ALLOWED_HOSTS = "abema.tv,api.p-c3-e.abema-tv.com,api.abema.io," +
            "streaming-api-cf.p-c2-x.abema-tv.com,license.p-c3-e.abema-tv.com,*-abematv.akamaized.net," +
            "id.twitch.tv,gql.twitch.tv,ttvnw.net,*.ttvnw.net,twitchcdn.net,*.twitchcdn.net,dgeft87wbj63p.cloudfront.net"

        fun create(profile: ConnectionProfile?,
            preparation: RoutePreparation = RoutePreparation(),
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend = { selected, security ->
                selected.protocol.createBackend(selected, security, preparation)
            },
        ): RouteSession {
            preparation.checkActive()
            if (profile == null) return RouteSession(null, "", "", "", preparation.diagnostics)
            fun nonce() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })
            val username = nonce(); val password = nonce(); val realm = nonce()
            var backend: RouteBackend? = null
            return try {
                backend = createBackend(profile, RouteProxySecurity(username, password, realm, ALLOWED_HOSTS))
                preparation.checkActive()
                check(backend.proxyPort in 1..65535)
                RouteSession(backend, username, password, realm, preparation.diagnostics)
            } catch (error: Exception) {
                if (!preparation.isCancelled) preparation.diagnostics.report(FailureStage.ROUTE_CREATE, error)
                runCatching { backend?.close() }.onFailure { cleanupError ->
                    preparation.recordCleanupFailure(FailureStage.ROUTE_CREATE_ROLLBACK, cleanupError)
                }
                throw IOException("Imported route could not be initialized")
            }
        }
    }
}
