package net.fstab.tachiai.provider.twitch

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.*
import java.net.URL
import java.security.cert.Certificate
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.twitch.catalog.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

// Actual Android factory/session/preparation with distinct synthetic catalog
// and LOCAL grants. No saved device data, consent, provider or media network.
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ConfiguredTwitchBroadcasterSessionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private class Memory(private val workerOnly: Boolean = false) : PrivateSecretStore {
        private var bytes: ByteArray? = null
        private fun checkThread() { if (workerOnly) check(Looper.myLooper() != Looper.getMainLooper()) }
        @Synchronized override fun read(): ByteArray? { checkThread(); return bytes?.copyOf() }
        @Synchronized override fun write(plaintext: ByteArray) { checkThread(); bytes = plaintext.copyOf() }
    }
    private class Connection(url: URL, private val body: String, private val hold: Boolean = false) : HttpsURLConnection(url) {
        val output = ByteArrayOutputStream()
        private val ended = CountDownLatch(1)
        override fun getOutputStream() = output
        override fun getResponseCode(): Int {
            if (hold) { check(ended.await(10, TimeUnit.SECONDS)); throw IOException("Fixture media ended") }
            return 200
        }
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun getContentLengthLong() = body.toByteArray(Charsets.UTF_8).size.toLong()
        override fun disconnect() { ended.countDown() }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private fun page(rows: List<Map<String, Any?>>) = TwitchHelixResponse(200,
        mapOf("data" to rows, "pagination" to emptyMap<String, Any?>()))
    private fun user(id: String = "123") = mapOf<String, Any?>("id" to id, "login" to "renamed_channel", "display_name" to "Renamed")
    private fun live() = mapOf<String, Any?>("id" to "456", "user_id" to "123", "user_login" to "renamed_channel",
        "user_name" to "Renamed", "type" to "live")
    private inner class Fixture(catalogSaved: Boolean = true, localSaved: Boolean = true) {
        val instanceId = UUID.randomUUID().toString()
        val resource = CatalogResource(ProviderId("twitch"), "broadcaster", "123", CatalogIntent.CHANNEL)
        val route = TwitchCatalogRouteOwner(ProviderInstance(instanceId, PrototypeService.TWITCH, "Fixture",
            setup = ProviderSetup(SourceRouteChoice.system)), null)
        val active = AtomicBoolean(true)
        val bindingOpen = AtomicBoolean(true)
        val budget = NativePlaybackBudget(60_000L, { active.get() })
        val catalogStore = TwitchCatalogGrantStore(Memory(workerOnly = true), instanceId)
        val local = TwitchSavedAuthorization(Memory(), profile = TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL)
        val order = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<PrototypeFeedEvent>()
        val nativeRequests = CopyOnWriteArrayList<Connection>()
        val nativeValidation = CopyOnWriteArrayList<Connection>()
        val media = CopyOnWriteArrayList<Connection>()
        var mono = 10_000L
        var confirmationOwner = "123"
        var offline = false
        var catalogStatus = 200
        var changedOwner = false
        var afterNativeAccess: () -> Unit = {}
        var failResolverCleanup = false
        lateinit var catalogSession: TwitchCatalogSession
        lateinit var session: PrototypeTwitchSession
        var resolverClosed = false
        init {
            if (catalogSaved) catalogStore.commitConnection(catalogStore.beginConnection(), TwitchCatalogReplacement(
                TwitchCatalogCredentials("fixture-catalog-access", "fixture-catalog-refresh", 7200_000L),
                TwitchCatalogValidation("catalog-user", setOf(TWITCH_CATALOG_SCOPE), 7200_000L), 7200_000L))!!
            if (localSaved) runBlocking { local.saveValidated("fixture-local-access",
                System.nanoTime() / 1_000_000 + 3_600_000L, local.revision()) }
            instrumentation.runOnMainSync {
                session = configuredTwitchBroadcasterSession(instrumentation.targetContext, resource, { active.get() },
                    { events.add(it) }, liveIdentityResolverFactory = {
                        check(Looper.myLooper() != Looper.getMainLooper())
                        val binding = object : TwitchCatalogConnectionBinding {
                            override fun owner(): TwitchCatalogConnectionOwner {
                                check(Looper.myLooper() != Looper.getMainLooper())
                                return if (changedOwner) TwitchCatalogConnectionOwner(instanceId, "Fixture", "System network", "CHANGED")
                                    else route.presentation
                            }
                            override fun canPublishLocally() = bindingOpen.get() && active.get()
                            override fun session(): TwitchCatalogSession = TwitchCatalogSession(catalogStore, { transport { true } },
                                monotonicMs = { mono }, canCommit = { canPublishLocally() && owner().sameOwnership(route.presentation) })
                                .also { catalogSession = it }
                            override fun transport(canRequest: () -> Boolean) = object : TwitchCatalogTransport {
                                override fun device(): DeviceAuthResponse = error("No consent")
                                override fun poll(deviceCode: String): DeviceAuthResponse = error("No polling")
                                override fun refresh(refreshToken: String): DeviceAuthResponse = error("No refresh expected")
                                override fun validate(accessToken: String): DeviceAuthResponse {
                                    check(canRequest()); assertEquals("fixture-catalog-access", accessToken)
                                    order.add("catalog-validate")
                                    return DeviceAuthResponse(200, mapOf("client_id" to SMART_TV_TWITCH_CLIENT_ID,
                                        "user_id" to "catalog-user", "scopes" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to 7200))
                                }
                                override fun close() = Unit
                            }
                            override fun close() {
                                bindingOpen.set(false); resolverClosed = true
                                if (failResolverCleanup) error("Synthetic cleanup failed")
                            }
                        }
                        ownedTwitchLiveIdentityResolver(instanceId, null, route, binding, { active.get() },
                            transportFactory = { profile, gate, cleanup ->
                                assertNull(profile)
                                OwnedTwitchHelixTransport(profile, gate, onCleanupFailure = cleanup,
                                    createTransport = { _, admitted -> object : TwitchHelixTransport {
                                        override fun execute(accessToken: String, request: TwitchHelixRequest): TwitchHelixResponse {
                                            check(Looper.myLooper() != Looper.getMainLooper()); check(admitted())
                                            assertEquals("fixture-catalog-access", accessToken)
                                            if (catalogStatus != 200) return TwitchHelixResponse(catalogStatus)
                                            return when (request.operation) {
                                                TwitchHelixOperation.USERS -> {
                                                    if (request.url.query == "id=123") { order.add("users-id"); page(listOf(user())) }
                                                    else { assertEquals("login=renamed_channel", request.url.query)
                                                        order.add("users-login"); page(listOf(user(confirmationOwner))) }
                                                }
                                                TwitchHelixOperation.STREAMS -> {
                                                    assertEquals("user_id=123&first=20", request.url.query)
                                                    order.add("streams-id"); page(if (offline) emptyList() else listOf(live()))
                                                }
                                                else -> error("No other endpoint")
                                            }
                                        }
                                        override fun close() = Unit
                                    } })
                            }, expectedRoute = SourceRouteChoice.system)
                    }, openConnection = { url ->
                        when {
                            url.toExternalForm() == "https://id.twitch.tv/oauth2/validate" -> {
                                order.add("local-validate")
                                Connection(url, """{"client_id":"$SMART_TV_TWITCH_CLIENT_ID","user_id":"local-user","expires_in":3600,"scopes":[]}""")
                                    .also { nativeValidation.add(it) }
                            }
                            url.toExternalForm() == "https://gql.twitch.tv/gql" -> {
                                order.add("native-access")
                                afterNativeAccess()
                                Connection(url, """{"data":{"streamPlaybackAccessToken":{"signature":"fixture_sig","value":"fixture-value"}}}""")
                                    .also { nativeRequests.add(it) }
                            }
                            url.host == "usher.ttvnw.net" -> Connection(url, "", true).also { media.add(it) }
                            else -> throw IOException("Unexpected fixture route")
                        }
                    }, authorization = local)
            }
        }
        fun barrier() {
            val worker = PrototypeTwitchSession::class.java.getDeclaredField("worker").apply { isAccessible = true }
                .get(session) as ExecutorService
            worker.submit(Runnable {}).get(5, TimeUnit.SECONDS)
        }
        fun prepareBeforePublication(action: () -> Unit = {}) {
            instrumentation.runOnMainSync { session.prepare(budget); barrier(); action() }
            instrumentation.runOnMainSync { }
        }
        fun assertFailed(reason: PrototypeFailureReason) = instrumentation.runOnMainSync {
            assertNull(session.player); assertNull(session.member)
            assertEquals(reason, session.failure!!.reason)
            assertEquals(listOf(PrototypeFeedEvent.PREPARING, PrototypeFeedEvent.FAILED), events.toList())
        }
        fun close() { instrumentation.runOnMainSync { session.close() }; media.forEach { it.disconnect() } }
    }

    @Test fun renamedExactBroadcasterUsesSeparateGrantsAndConfirmedLoginAndZeroPosition() {
        val fixture = Fixture()
        try {
            fixture.prepareBeforePublication()
            instrumentation.runOnMainSync {
                assertEquals(0L, checkNotNull(fixture.session.player).currentPosition)
                assertFalse(checkNotNull(fixture.session.player).playWhenReady)
                assertTrue(fixture.session.canContinue())
            }
            assertEquals(listOf("catalog-validate", "users-id", "streams-id", "local-validate", "native-access", "users-login"), fixture.order.toList())
            val request = JSONObject(fixture.nativeRequests.single().output.toString("UTF-8"))
            assertEquals("StreamPlaybackAccessToken", request.getString("operationName"))
            assertEquals("renamed_channel", request.getJSONObject("variables").getString("login"))
            assertFalse(request.getJSONObject("variables").has("id"))
            assertEquals("OAuth fixture-local-access", fixture.nativeValidation.single().getRequestProperty("Authorization"))
            assertEquals("OAuth fixture-local-access", fixture.nativeRequests.single().getRequestProperty("Authorization"))
            assertTrue(fixture.resolverClosed)
            // Catalog is identity authority at publication, not continuing
            // entitlement for the separately authorized native stream.
            fixture.catalogStore.invalidate()
            instrumentation.runOnMainSync { assertTrue(fixture.session.canContinue()) }
        } finally { fixture.close() }
    }

    @Test fun missingCatalogAndMissingLocalAreDistinctAndNeverSubstituteForEachOther() {
        listOf(false to true, true to false).forEach { (catalog, local) ->
            val fixture = Fixture(catalog, local)
            try {
                fixture.prepareBeforePublication()
                fixture.assertFailed(if (!catalog) PrototypeFailureReason.CATALOG_CONNECTION_REQUIRED else PrototypeFailureReason.LOGIN_MISSING)
                assertTrue(fixture.nativeRequests.isEmpty()); assertTrue(fixture.media.isEmpty())
                if (!catalog) assertTrue(fixture.order.isEmpty())
                else assertEquals(listOf("catalog-validate", "users-id", "streams-id"), fixture.order.toList())
            } finally { fixture.close() }
        }
    }

    @Test fun currentOfflineAndRecycledLoginConfirmationDiscardWithoutPlayerOrSampleFallback() {
        listOf("offline", "recycled").forEach { failure ->
            val fixture = Fixture()
            try {
                if (failure == "offline") fixture.offline = true else fixture.confirmationOwner = "999"
                fixture.prepareBeforePublication()
                fixture.assertFailed(PrototypeFailureReason.MEDIA_NOT_FOUND)
                assertTrue(fixture.media.isEmpty())
                assertEquals(if (failure == "offline") 0 else 1, fixture.nativeRequests.size)
                assertTrue(fixture.resolverClosed)
            } finally { fixture.close() }
        }
    }

    @Test fun queuedPublicationUsesNoProtectedIoAndRejectsLifecycleCatalogLocalAndDeadlineChanges() {
        listOf("close", "background", "catalog-forget", "local-forget", "deadline", "binding-close", "lease-replaced").forEach { boundary ->
            val fixture = Fixture()
            try {
                fixture.prepareBeforePublication {
                    when (boundary) {
                        "close" -> fixture.session.close()
                        "background" -> fixture.active.set(false)
                        "catalog-forget" -> fixture.catalogStore.invalidate()
                        "local-forget" -> fixture.local.forget()
                        "deadline" -> fixture.mono += TWITCH_CATALOG_VALIDATION_INTERVAL_MS
                        "binding-close" -> fixture.bindingOpen.set(false)
                        "lease-replaced" -> {
                            val replacement = Executors.newSingleThreadExecutor()
                            try { replacement.submit { fixture.catalogSession.validate(force = true) }.get(5, TimeUnit.SECONDS) }
                            finally { replacement.shutdownNow() }
                        }
                    }
                }
                instrumentation.runOnMainSync { assertNull(fixture.session.player); assertNull(fixture.session.member) }
                assertTrue(fixture.media.isEmpty()); assertEquals(1, fixture.nativeRequests.size)
                if (boundary in setOf("close", "background")) assertEquals(listOf(PrototypeFeedEvent.PREPARING), fixture.events.toList())
                else fixture.assertFailed(if (boundary == "local-forget") PrototypeFailureReason.LOGIN_EXPIRED
                    else PrototypeFailureReason.CATALOG_CONNECTION_REQUIRED)
            } finally { fixture.close() }
        }
    }

    @Test fun ownerChangeDuringNativePreparationFailsWorkerConfirmationBeforePublication() {
        val fixture = Fixture()
        try {
            // Change the durable owner after native source preparation but
            // before confirm's owner reread, using an actual injected native call.
            fixture.afterNativeAccess = { fixture.changedOwner = true }
            fixture.prepareBeforePublication()
            fixture.assertFailed(PrototypeFailureReason.CATALOG_CONNECTION_REQUIRED)
            assertEquals(1, fixture.nativeRequests.size); assertTrue(fixture.media.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun uncertainResolverCleanupStopsFeedBeforeAnyNativePlayerOrMediaRequestSurvives() {
        val fixture = Fixture()
        try {
            fixture.failResolverCleanup = true
            fixture.prepareBeforePublication()
            fixture.assertFailed(PrototypeFailureReason.CLEANUP_FAILED)
            assertTrue(fixture.session.cleanupFailed)
            assertEquals(1, fixture.nativeRequests.size); assertTrue(fixture.media.isEmpty())
            assertEquals("users-login", fixture.order.last())
        } finally { fixture.close() }
    }
}
