package net.fstab.tachiai.provider.twitch

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.PrototypeFailureReason
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

// Disposable device fixtures: real preparation and Media3 session construction,
// synthetic in-memory LOCAL grants, injected connections, no actual networking,
// provider media, account login or saved device authorization. Media responses
// remain blocked until cleanup; no content reaches a decoder.
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PrototypeTwitchSessionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private class Memory : PrivateSecretStore {
        private var bytes: ByteArray? = null
        @Synchronized override fun read() = bytes?.copyOf()
        @Synchronized override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
    }
    private fun authorization(saved: Boolean = true, token: String = "fixture-instance-token",
        profile: TwitchAuthorizationProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL): TwitchSavedAuthorization {
        val cache = TwitchSavedAuthorization(Memory(), profile = profile)
        if (saved) runBlocking {
            assertEquals(SavedAuthorizationState.SAVED, cache.saveValidated(token,
                System.nanoTime() / 1_000_000 + 3_600_000L, cache.revision()))
        }
        return cache
    }
    private class Connection(url: URL, private val response: String, private val hold: Boolean = false) : HttpsURLConnection(url) {
        val output = ByteArrayOutputStream()
        private val release = CountDownLatch(1)
        override fun getOutputStream() = output
        override fun getResponseCode(): Int {
            if (hold) {
                if (!release.await(10, TimeUnit.SECONDS)) throw IOException("Fixture media was not closed")
                throw IOException("Fixture media closed")
            }
            return 200
        }
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
        override fun getContentLengthLong() = response.toByteArray(Charsets.UTF_8).size.toLong()
        override fun disconnect() { release.countDown() }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private inner class Fixture(val replay: Boolean, val resource: String, initialPosition: Long = 0,
        val cache: TwitchSavedAuthorization = authorization()) {
        val active = AtomicBoolean(true)
        val events = CopyOnWriteArrayList<PrototypeFeedEvent>()
        val failed = CountDownLatch(1)
        val validation = CopyOnWriteArrayList<Connection>()
        val access = CopyOnWriteArrayList<Connection>()
        val media = CopyOnWriteArrayList<Connection>()
        val budget = NativePlaybackBudget(60_000L, { active.get() })
        lateinit var session: PrototypeTwitchSession
        init {
            instrumentation.runOnMainSync {
                session = PrototypeTwitchSession(instrumentation.targetContext, replay, resource, { active.get() }, {
                    events.add(it); if (it == PrototypeFeedEvent.FAILED) failed.countDown()
                }, openConnection = { url ->
                    when {
                        url.toExternalForm() == "https://id.twitch.tv/oauth2/validate" -> Connection(url,
                            """{"client_id":"$SMART_TV_TWITCH_CLIENT_ID","user_id":"fixture-user","expires_in":3600,"scopes":[]}""")
                            .also { validation.add(it) }
                        url.toExternalForm() == "https://gql.twitch.tv/gql" -> Connection(url,
                            """{"data":{"${if (replay) "videoPlaybackAccessToken" else "streamPlaybackAccessToken"}":{"signature":"fixture_sig","value":"fixture-value"}}}""")
                            .also { access.add(it) }
                        url.host == "usher.ttvnw.net" -> Connection(url, "", hold = true).also { media.add(it) }
                        else -> throw IOException("Unexpected fixture route")
                    }
                }, authorization = cache, initialPositionMs = initialPosition)
            }
        }
        fun prepare() = instrumentation.runOnMainSync { session.prepare(budget) }
        fun preparedWorkerBarrier() {
            val worker = PrototypeTwitchSession::class.java.getDeclaredField("worker").apply { isAccessible = true }
                .get(session) as ExecutorService
            worker.submit(Runnable {}).get(5, TimeUnit.SECONDS)
        }
        fun close() {
            instrumentation.runOnMainSync { session.close() }
            media.forEach { it.disconnect() }
        }
    }

    @Test fun exactConfiguredVideosReachPreparationWithOwnGrantAndStartAtZeroWhileExplicitSampleKeepsAnchor() {
        listOf("789" to 0L, "98765432101234567890" to 0L, "2080217716" to 4_200_000L,
            "2080217716" to 0L).forEachIndexed { index, (resource, position) ->
            val token = "fixture-instance-$index"
            val fixture = Fixture(true, resource, position, authorization(token = token))
            try {
                fixture.prepare(); fixture.preparedWorkerBarrier()
                instrumentation.runOnMainSync {
                    val player = checkNotNull(fixture.session.player)
                    assertEquals(position, player.currentPosition)
                    assertFalse(player.playWhenReady)
                    assertTrue(fixture.session.canContinue())
                }
                assertEquals(1, fixture.validation.size); assertEquals(1, fixture.access.size)
                assertEquals("OAuth $token", fixture.validation.single().getRequestProperty("Authorization"))
                assertEquals("OAuth $token", fixture.access.single().getRequestProperty("Authorization"))
                val request = JSONObject(fixture.access.single().output.toString("UTF-8"))
                assertEquals("VideoPlaybackAccessToken", request.getString("operationName"))
                assertEquals(resource, request.getJSONObject("variables").getString("id"))
                assertFalse(request.getJSONObject("variables").has("login"))
                assertEquals(listOf(PrototypeFeedEvent.PREPARING), fixture.events.toList())
            } finally { fixture.close() }
        }
    }

    @Test fun liveSessionKeepsItsLoginAndDefaultStartInsteadOfInheritingReplayPosition() {
        val fixture = Fixture(false, "fixture_channel")
        try {
            fixture.prepare(); fixture.preparedWorkerBarrier()
            instrumentation.runOnMainSync {
                assertEquals(0L, checkNotNull(fixture.session.player).currentPosition)
                assertFalse(checkNotNull(fixture.session.player).playWhenReady)
            }
            val request = JSONObject(fixture.access.single().output.toString("UTF-8"))
            assertEquals("StreamPlaybackAccessToken", request.getString("operationName"))
            assertEquals("fixture_channel", request.getJSONObject("variables").getString("login"))
            assertFalse(request.getJSONObject("variables").has("id"))
        } finally { fixture.close() }
    }

    @Test fun invalidResourcePositionAndWrongAuthorizationProfileFailBeforePreparationCanOpenAnyRoute() {
        val local = authorization()
        val other = authorization(profile = TwitchAuthorizationProfile.TACHIAI)
        instrumentation.runOnMainSync {
            fun create(replay: Boolean, resource: String, position: Long, cache: TwitchSavedAuthorization = local) =
                PrototypeTwitchSession(instrumentation.targetContext, replay, resource, { true }, {},
                    openConnection = { throw AssertionError("Invalid input cannot open a connection") },
                    authorization = cache, initialPositionMs = position)
            listOf("", "123456789012345678901", "video789", "https://www.twitch.tv/videos/789", "789?token=x").forEach {
                assertThrows(IllegalArgumentException::class.java) { create(true, it, 0) }
            }
            assertThrows(IllegalArgumentException::class.java) { create(true, "789", -1) }
            assertThrows(IllegalArgumentException::class.java) { create(false, "fixture_channel", 4_200_000L) }
            assertThrows(IllegalArgumentException::class.java) { create(false, "x", 0) }
            assertThrows(IllegalArgumentException::class.java) { create(true, "789", 0, other) }
        }
    }

    @Test fun missingOwningLocalGrantDoesNotBorrowAnotherGrantOrMakeCatalogOrPlaybackRequests() {
        val unrelated = authorization(token = "fixture-other-instance")
        val fixture = Fixture(true, "789", cache = authorization(saved = false))
        try {
            fixture.prepare()
            assertTrue(fixture.failed.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertNull(fixture.session.player); assertNull(fixture.session.member)
                assertEquals(PrototypeFailureReason.LOGIN_MISSING, fixture.session.failure!!.reason)
            }
            assertTrue(fixture.validation.isEmpty()); assertTrue(fixture.access.isEmpty()); assertTrue(fixture.media.isEmpty())
            assertEquals(SavedAuthorizationState.AVAILABLE, unrelated.read().state)
        } finally { fixture.close() }
    }

    @Test fun preparedCallbackCannotPublishAfterCloseBackgroundOrSameInstanceForget() {
        listOf("close", "background", "forget").forEach { boundary ->
            val fixture = Fixture(true, "789")
            try {
                // Keep the actual main-looper publication queued while the real
                // worker finishes its injected preparation. No extra test seam
                // changes production factories or session lifecycle behavior.
                instrumentation.runOnMainSync {
                    fixture.session.prepare(fixture.budget)
                    fixture.preparedWorkerBarrier()
                    when (boundary) {
                        "close" -> fixture.session.close()
                        "background" -> fixture.active.set(false)
                        "forget" -> assertEquals(SavedAuthorizationState.FORGOTTEN, fixture.cache.forget())
                    }
                }
                instrumentation.runOnMainSync {
                    assertNull(fixture.session.player); assertNull(fixture.session.member)
                    assertFalse(fixture.session.canContinue())
                }
                assertEquals(1, fixture.validation.size); assertEquals(1, fixture.access.size)
                assertTrue(fixture.media.isEmpty())
                if (boundary == "forget") {
                    assertEquals(listOf(PrototypeFeedEvent.PREPARING, PrototypeFeedEvent.FAILED), fixture.events.toList())
                    assertEquals(PrototypeFailureReason.LOGIN_EXPIRED, fixture.session.failure!!.reason)
                } else assertEquals(listOf(PrototypeFeedEvent.PREPARING), fixture.events.toList())
            } finally { fixture.close() }
        }
    }
}
