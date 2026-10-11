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
import net.fstab.tachiai.provider.abema.account.AbemaApprovalBrowserPolicy

private enum class RoutePurpose { PLAYBACK, TWITCH_CATALOG, TWITCH_CONTINUE_WATCHING, ABEMA_CATALOG, ABEMA_GUEST, ABEMA_GUEST_PROFILE, ABEMA_EPISODE_METADATA, ABEMA_GUEST_EPISODE, ABEMA_GUEST_DEVICE_AUTHORIZATION, ABEMA_GUEST_DEVICE_TOKEN_OBSERVATION, ABEMA_ACCOUNT_COLLECTION, ABEMA_ACCOUNT_BOOTSTRAP, ABEMA_ACCOUNT_SESSION, ABEMA_GUEST_SERIES, ABEMA_GUEST_SLOT, ABEMA_APPROVAL_BROWSER }

// Owned by a playback run, not a display position. The owner shares one instance
// for a repeated imported profile and closes it after every dependent feed stops.
// It never installs Android VpnService or changes the device's active VPN.
internal class RouteSession private constructor(
    private val backend: RouteBackend?,
    val proxyUsername: String,
    val proxyPassword: String,
    val proxyRealm: String,
    private val diagnostics: FailureReporter,
    private val purpose: RoutePurpose = RoutePurpose.PLAYBACK,
) : AutoCloseable {
    val isSystem get() = backend == null
    val proxyPort: Int = backend?.proxyPort ?: 0
    private val closed = AtomicBoolean()
    private val connections = Collections.newSetFromMap(ConcurrentHashMap<RoutedHttpsConnection, Boolean>())
    // Anonymous ABEMA metadata uses a private, credential-free client even on
    // System. Java's process-global authenticators/cookie handlers cannot attach
    // account state to that request; existing playback/Twitch openers are retained.
    private val client: OkHttpClient? = if (backend == null && purpose !in setOf(RoutePurpose.TWITCH_CONTINUE_WATCHING, RoutePurpose.ABEMA_CATALOG, RoutePurpose.ABEMA_GUEST, RoutePurpose.ABEMA_GUEST_PROFILE, RoutePurpose.ABEMA_EPISODE_METADATA, RoutePurpose.ABEMA_GUEST_EPISODE, RoutePurpose.ABEMA_GUEST_DEVICE_AUTHORIZATION, RoutePurpose.ABEMA_GUEST_DEVICE_TOKEN_OBSERVATION, RoutePurpose.ABEMA_ACCOUNT_COLLECTION, RoutePurpose.ABEMA_ACCOUNT_BOOTSTRAP, RoutePurpose.ABEMA_ACCOUNT_SESSION, RoutePurpose.ABEMA_GUEST_SERIES, RoutePurpose.ABEMA_GUEST_SLOT, RoutePurpose.ABEMA_APPROVAL_BROWSER)) null else OkHttpClient.Builder()
        .proxy(if (backend == null) Proxy.NO_PROXY else Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
        .proxyAuthenticator(if (backend == null) Authenticator.NONE else
            routeProxyAuthenticator(proxyUsername, proxyPassword, proxyRealm, proxyPort))
        .authenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES)
        .connectionPool(ConnectionPool())
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .build()

    fun open(url: URL): HttpsURLConnection {
        if (closed.get()) throw IOException("Route is closed")
        require(url.protocol == "https" && url.userInfo == null && url.ref == null && url.port in listOf(-1, 443))
        when (purpose) {
            RoutePurpose.TWITCH_CATALOG -> require(url.host in setOf("id.twitch.tv", "api.twitch.tv"))
            RoutePurpose.TWITCH_CONTINUE_WATCHING -> require(url.toExternalForm() == "https://gql.twitch.tv/gql")
            RoutePurpose.ABEMA_CATALOG -> require(url.host == "api.abema.io")
            RoutePurpose.ABEMA_GUEST -> require(url.host == "abema.tv")
            RoutePurpose.ABEMA_GUEST_PROFILE -> require(url.host in setOf("abema.tv", "api.p-c3-e.abema-tv.com"))
            RoutePurpose.ABEMA_EPISODE_METADATA -> require(url.host == "api.p-c3-e.abema-tv.com")
            RoutePurpose.ABEMA_GUEST_EPISODE -> require(url.host in setOf("abema.tv", "api.p-c3-e.abema-tv.com"))
            RoutePurpose.ABEMA_GUEST_SERIES, RoutePurpose.ABEMA_GUEST_SLOT -> require(url.host in setOf("abema.tv", "api.p-c3-e.abema-tv.com"))
            RoutePurpose.ABEMA_GUEST_DEVICE_AUTHORIZATION -> require(url.host in setOf("abema.tv", "api.p-c3-e.abema-tv.com"))
            RoutePurpose.ABEMA_GUEST_DEVICE_TOKEN_OBSERVATION -> require(url.host in setOf("abema.tv", "api.p-c3-e.abema-tv.com"))
            RoutePurpose.ABEMA_ACCOUNT_COLLECTION -> require(url.host == "api.p-c3-e.abema-tv.com")
            RoutePurpose.ABEMA_ACCOUNT_BOOTSTRAP -> require(url.host in setOf("abema.tv", "api.p-c3-e.abema-tv.com"))
            RoutePurpose.ABEMA_ACCOUNT_SESSION -> require(url.host in setOf("abema.tv", "api.p-c3-e.abema-tv.com"))
            RoutePurpose.ABEMA_APPROVAL_BROWSER -> require(AbemaApprovalBrowserPolicy.allowedResource(url.toExternalForm()))
            RoutePurpose.PLAYBACK -> Unit
        }
        if (isSystem && purpose !in setOf(RoutePurpose.TWITCH_CONTINUE_WATCHING, RoutePurpose.ABEMA_CATALOG, RoutePurpose.ABEMA_GUEST, RoutePurpose.ABEMA_GUEST_PROFILE, RoutePurpose.ABEMA_EPISODE_METADATA, RoutePurpose.ABEMA_GUEST_EPISODE, RoutePurpose.ABEMA_GUEST_DEVICE_AUTHORIZATION, RoutePurpose.ABEMA_GUEST_DEVICE_TOKEN_OBSERVATION, RoutePurpose.ABEMA_ACCOUNT_COLLECTION, RoutePurpose.ABEMA_ACCOUNT_BOOTSTRAP, RoutePurpose.ABEMA_ACCOUNT_SESSION, RoutePurpose.ABEMA_GUEST_SERIES, RoutePurpose.ABEMA_GUEST_SLOT, RoutePurpose.ABEMA_APPROVAL_BROWSER)) return url.openConnection() as HttpsURLConnection
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
        ): RouteSession = createOwned(profile, preparation, createBackend, RoutePurpose.PLAYBACK)

        // Cached viewer ownership is established before post-create validation
        // or cancellation rollback can fail. Existing factories are unchanged.
        fun createRetained(profile: ConnectionProfile?, preparation: RoutePreparation,
            ownBackend: (RouteBackend) -> RouteBackend,
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend = { selected, security ->
                selected.protocol.createBackend(selected, security, preparation)
            },
        ): RouteSession = createOwned(profile, preparation, createBackend, RoutePurpose.PLAYBACK, ownBackend)

        // Closed metadata/OAuth purpose. Both System and imported routes enforce
        // these hosts; playback's historical policy is unchanged.
        fun createTwitchCatalog(profile: ConnectionProfile?,
            preparation: RoutePreparation = RoutePreparation(),
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend = { selected, security ->
                selected.protocol.createBackend(selected, security, preparation)
            },
        ): RouteSession = createOwned(profile, preparation, createBackend, RoutePurpose.TWITCH_CATALOG)

        // Independent private collection transport. System also uses a private
        // no-cookie/no-ambient-auth client; the exact fixed gql path is closed.
        fun createTwitchContinueWatching(profile: ConnectionProfile?,
            preparation: RoutePreparation = RoutePreparation(),
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend = { selected, security ->
                selected.protocol.createBackend(selected, security, preparation)
            },
        ): RouteSession = createOwned(profile, preparation, createBackend, RoutePurpose.TWITCH_CONTINUE_WATCHING)

        fun createAbemaCatalog(profile: ConnectionProfile?,
            preparation: RoutePreparation = RoutePreparation(),
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend = { selected, security ->
                selected.protocol.createBackend(selected, security, preparation)
            },
        ): RouteSession = createOwned(profile, preparation, createBackend, RoutePurpose.ABEMA_CATALOG)

        // Anonymous modern episode metadata only. The consumer still enforces
        // its exact public resource/path/method; historical catalog stays closed.
        fun createAbemaEpisodeCatalog(profile: ConnectionProfile?,
            preparation: RoutePreparation = RoutePreparation(),
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend = { selected, security ->
                selected.protocol.createBackend(selected, security, preparation)
            },
        ): RouteSession = createOwned(profile, preparation, createBackend, RoutePurpose.ABEMA_EPISODE_METADATA)

        // Account collections use a closed modern API origin. The adapter owns
        // the exact read-only path/query and private, instance-bound grant.
        fun createAbemaAccountCollections(profile: ConnectionProfile?,
            preparation: RoutePreparation = RoutePreparation(),
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend = { selected, security ->
                selected.protocol.createBackend(selected, security, preparation)
            },
        ): RouteSession = createOwned(profile, preparation, createBackend, RoutePurpose.ABEMA_ACCOUNT_COLLECTION)

        // Public-code download and isolated guest purpose only. The runtime's
        // exact URI policy separately refuses navigation and other auth APIs.
        fun createAbemaGuest(profile: ConnectionProfile?,
            preparation: RoutePreparation = RoutePreparation(),
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend = { selected, security ->
                selected.protocol.createBackend(selected, security, preparation)
            },
        ): RouteSession = createOwned(profile, preparation, createBackend, RoutePurpose.ABEMA_GUEST)

        fun createAbemaGuestProfile(profile: ConnectionProfile?,
            preparation: RoutePreparation = RoutePreparation(),
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend = { selected, security ->
                selected.protocol.createBackend(selected, security, preparation)
            },
        ): RouteSession = createOwned(profile, preparation, createBackend, RoutePurpose.ABEMA_GUEST_PROFILE)

        fun createAbemaGuestSlot(profile: ConnectionProfile?,
            preparation: RoutePreparation = RoutePreparation(),
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend = { selected, security ->
                selected.protocol.createBackend(selected, security, preparation)
            },
        ): RouteSession = createOwned(profile, preparation, createBackend, RoutePurpose.ABEMA_GUEST_SLOT)

        fun createAbemaGuestSeries(profile: ConnectionProfile?,
            preparation: RoutePreparation = RoutePreparation(),
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend = { selected, security ->
                selected.protocol.createBackend(selected, security, preparation)
            },
        ): RouteSession = createOwned(profile, preparation, createBackend, RoutePurpose.ABEMA_GUEST_SERIES)

        fun createAbemaGuestEpisode(profile: ConnectionProfile?,
            preparation: RoutePreparation = RoutePreparation(),
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend = { selected, security ->
                selected.protocol.createBackend(selected, security, preparation)
            },
        ): RouteSession = createOwned(profile, preparation, createBackend, RoutePurpose.ABEMA_GUEST_EPISODE)

        // One challenge after a fresh matched guest profile. Exact URI/method
        // ordering is independently enforced by the isolated runtime policy.
        fun createAbemaGuestDeviceAuthorization(profile: ConnectionProfile?,
            preparation: RoutePreparation = RoutePreparation(),
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend = { selected, security ->
                selected.protocol.createBackend(selected, security, preparation)
            },
        ): RouteSession = createOwned(profile, preparation, createBackend, RoutePurpose.ABEMA_GUEST_DEVICE_AUTHORIZATION)

        // Explicit one-response observation only; the runtime admits one token
        // POST after its fresh matched guest/profile/challenge sequence.
        fun createAbemaGuestDeviceTokenObservation(profile: ConnectionProfile?,
            preparation: RoutePreparation = RoutePreparation(),
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend = { selected, security ->
                selected.protocol.createBackend(selected, security, preparation)
            },
        ): RouteSession = createOwned(profile, preparation, createBackend, RoutePurpose.ABEMA_GUEST_DEVICE_TOKEN_OBSERVATION)

        // Account bootstrap and transport each keep a closed host purpose. The
        // adapter still enforces its exact path, method and request ordering.
        fun createAbemaAccountBootstrap(profile: ConnectionProfile?,
            preparation: RoutePreparation = RoutePreparation(),
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend = { selected, security ->
                selected.protocol.createBackend(selected, security, preparation)
            },
        ): RouteSession = createOwned(profile, preparation, createBackend, RoutePurpose.ABEMA_ACCOUNT_BOOTSTRAP)

        fun createAbemaAccountSession(profile: ConnectionProfile?,
            preparation: RoutePreparation = RoutePreparation(),
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend = { selected, security ->
                selected.protocol.createBackend(selected, security, preparation)
            },
        ): RouteSession = createOwned(profile, preparation, createBackend, RoutePurpose.ABEMA_ACCOUNT_SESSION)

        // Visible provider-controlled approval in its own process/profile. Only
        // this purpose admits the verified auth origin; native account purposes
        // retain their narrower hosts. Ownership precedes cancellation rollback.
        // A saved route's CONNECT admission guards redirect destinations too.
        // System has no such bridge and is refused only for this browser.
        fun createAbemaApprovalBrowser(profile: ConnectionProfile?,
            preparation: RoutePreparation = RoutePreparation(),
            ownBackend: (RouteBackend) -> RouteBackend = { it },
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend = { selected, security ->
                selected.protocol.createBackend(selected, security, preparation)
            },
        ): RouteSession {
            require(profile != null) { "ABEMA approval browser requires a saved route" }
            return createOwned(profile, preparation, createBackend, RoutePurpose.ABEMA_APPROVAL_BROWSER, ownBackend)
        }

        private fun createOwned(profile: ConnectionProfile?, preparation: RoutePreparation,
            createBackend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend,
            purpose: RoutePurpose,
            ownBackend: (RouteBackend) -> RouteBackend = { it },
        ): RouteSession {
            preparation.checkActive()
            if (profile == null) return RouteSession(null, "", "", "", preparation.diagnostics, purpose)
            fun nonce() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })
            val username = nonce(); val password = nonce(); val realm = nonce()
            var backend: RouteBackend? = null
            return try {
                backend = createBackend(profile, RouteProxySecurity(username, password, realm,
                    when (purpose) {
                        RoutePurpose.TWITCH_CATALOG -> "id.twitch.tv,api.twitch.tv"
                        RoutePurpose.TWITCH_CONTINUE_WATCHING -> "gql.twitch.tv"
                        RoutePurpose.ABEMA_CATALOG -> "api.abema.io"
                        RoutePurpose.ABEMA_GUEST -> "abema.tv"
                        RoutePurpose.ABEMA_GUEST_PROFILE -> "abema.tv,api.p-c3-e.abema-tv.com"
                        RoutePurpose.ABEMA_EPISODE_METADATA -> "api.p-c3-e.abema-tv.com"
                        RoutePurpose.ABEMA_GUEST_EPISODE -> "abema.tv,api.p-c3-e.abema-tv.com"
                        RoutePurpose.ABEMA_GUEST_SERIES, RoutePurpose.ABEMA_GUEST_SLOT -> "abema.tv,api.p-c3-e.abema-tv.com"
                        RoutePurpose.ABEMA_GUEST_DEVICE_AUTHORIZATION -> "abema.tv,api.p-c3-e.abema-tv.com"
                        RoutePurpose.ABEMA_GUEST_DEVICE_TOKEN_OBSERVATION -> "abema.tv,api.p-c3-e.abema-tv.com"
                        RoutePurpose.ABEMA_ACCOUNT_COLLECTION -> "api.p-c3-e.abema-tv.com"
                        RoutePurpose.ABEMA_ACCOUNT_BOOTSTRAP -> "abema.tv,api.p-c3-e.abema-tv.com"
                        RoutePurpose.ABEMA_ACCOUNT_SESSION -> "abema.tv,api.p-c3-e.abema-tv.com"
                        RoutePurpose.ABEMA_APPROVAL_BROWSER -> AbemaApprovalBrowserPolicy.HOSTS
                        RoutePurpose.PLAYBACK -> ALLOWED_HOSTS
                    }))
                backend = ownBackend(checkNotNull(backend))
                preparation.checkActive()
                check(backend.proxyPort in 1..65535)
                RouteSession(backend, username, password, realm, preparation.diagnostics, purpose)
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
