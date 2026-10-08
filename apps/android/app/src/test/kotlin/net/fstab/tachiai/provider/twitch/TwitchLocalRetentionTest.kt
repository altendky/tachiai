package net.fstab.tachiai.provider.twitch

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import org.junit.Assert.*
import org.junit.Test

class TwitchLocalRetentionTest {
    private val local = TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL
    private class Storage : PrivateSecretStore {
        var bytes: ByteArray? = null
        var failWrites = false
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { if (failWrites) throw java.io.IOException(); bytes = plaintext.copyOf() }
    }
    private class Clock {
        var wall = 1_000_000L
        var monotonic = 1000L
        fun advance(ms: Long) { wall += ms; monotonic += ms }
    }
    private class Transport : TwitchDeviceTransport {
        var devices = 0
        var polls = 0
        var validations = 0
        var closed = false
        var onPoll: () -> Unit = {}
        var onValidate: () -> Unit = {}
        var grant = mapOf<String, Any?>("access_token" to "invented-local-token", "token_type" to "bearer")
        var response = DeviceAuthResponse(200, mapOf("client_id" to SMART_TV_TWITCH_CLIENT_ID,
            "user_id" to "invented-local-user", "expires_in" to 0, "scopes" to null))
        override fun device(clientId: String): DeviceAuthResponse {
            assertEquals(SMART_TV_TWITCH_CLIENT_ID, clientId)
            devices++
            return DeviceAuthResponse(200, mapOf("device_code" to "invented-local-code", "user_code" to "LOCAL123",
                "verification_uri" to "https://www.twitch.tv/activate?public=true&device-code=LOCAL123",
                "expires_in" to 60, "interval" to 1))
        }
        override fun poll(clientId: String, deviceCode: String): DeviceAuthResponse {
            assertEquals(SMART_TV_TWITCH_CLIENT_ID, clientId)
            assertEquals("invented-local-code", deviceCode)
            polls++
            onPoll()
            return DeviceAuthResponse(200, grant)
        }
        override fun validate(accessToken: String): DeviceAuthResponse {
            assertEquals("invented-local-token", accessToken)
            validations++
            onValidate()
            return response
        }
        override fun close() { closed = true }
    }
    private fun cache(storage: Storage, clock: Clock, profile: TwitchAuthorizationProfile = local) =
        TwitchSavedAuthorization(storage, { clock.wall }, { clock.monotonic }, profile)
    private suspend fun save(cache: TwitchSavedAuthorization, clock: Clock, remaining: Long = SMART_TV_LOCAL_RETENTION_MS) =
        cache.saveValidated("invented-local-token", clock.monotonic + remaining, cache.revision())
    private suspend fun authorize(transport: Transport, clock: Clock,
        callback: suspend (String, Long) -> Unit = { _, _ -> }) = authorizeTwitchDevice(
        SMART_TV_TWITCH_CLIENT_ID, transport, {}, {}, clockMs = { clock.monotonic }, waitMs = { clock.advance(it) },
        providerProfile = local, retainSmartTvLocally = true, onProviderClientValidated = callback)

    @Test fun `local identity shares only the public client ID and adds an isolated fourth slot`() {
        assertEquals(SMART_TV_TWITCH_CLIENT_ID, local.clientId)
        assertEquals(TwitchAuthorizationProfile.PROVIDER_SMART_TV.clientId, local.clientId)
        assertEquals(4, TwitchAuthorizationProfile.entries.size)
        assertEquals(3, TwitchAuthorizationProfile.entries.map { it.clientId }.toSet().size)
        assertEquals(4, TwitchAuthorizationProfile.entries.map { it.storageSlot.bindingName }.toSet().size)
        assertNotEquals(TwitchAuthorizationProfile.PROVIDER_SMART_TV.storageSlot, local.storageSlot)
        assertEquals(604_800_000L, SMART_TV_LOCAL_RETENTION_MS)
    }

    @Test fun `explicit local grant accepts omitted or zero lifetime and bounds retention from poll start`() = runBlocking {
        listOf(emptyMap<String, Any?>(), mapOf("expires_in" to 0), mapOf("expires_in" to 0L)).forEach { lifetime ->
            val clock = Clock()
            val transport = Transport().apply {
                grant = grant + lifetime
                onPoll = { clock.advance(5000L) }
                onValidate = { clock.advance(2000L) }
            }
            var callbacks = 0
            assertEquals(DeviceAuthPhase.SUCCEEDED, authorize(transport, clock) { token, deadline ->
                assertEquals("invented-local-token", token)
                assertEquals(2000L + SMART_TV_LOCAL_RETENTION_MS, deadline)
                callbacks++
            })
            assertEquals(1, callbacks)
            assertEquals(1, transport.polls)
            assertEquals(1, transport.validations)
            assertTrue(transport.closed)
        }
    }

    @Test fun `positive grant or validation lifetime can shorten but never extend local retention`() = runBlocking {
        listOf(20 to 0, 0 to 15, 7200 to 7200).forEach { (grantSeconds, validationSeconds) ->
            val clock = Clock()
            val transport = Transport().apply {
                grant = grant + ("expires_in" to grantSeconds)
                response = DeviceAuthResponse(200, response.fields + ("expires_in" to validationSeconds))
            }
            var deadline = 0L
            assertEquals(DeviceAuthPhase.SUCCEEDED, authorize(transport, clock) { _, value -> deadline = value })
            val cap = minOf(SMART_TV_LOCAL_RETENTION_MS,
                if (grantSeconds == 0) SMART_TV_LOCAL_RETENTION_MS else grantSeconds * 1000L,
                if (validationSeconds == 0) SMART_TV_LOCAL_RETENTION_MS else validationSeconds * 1000L)
            assertEquals(2000L + cap, deadline)
        }
    }

    @Test fun `local retention requires explicit mode exact profile and only provider callback before IO`() = runBlocking {
        val forbidden: suspend (String, Long) -> Unit = { _, _ -> fail("invalid local handoff") }
        suspend fun rejected(client: String = SMART_TV_TWITCH_CLIENT_ID, profile: TwitchAuthorizationProfile = local,
            retain: Boolean = true, inspect: Boolean = false, own: (suspend (String, Long) -> Unit)? = null,
            provider: (suspend (String, Long) -> Unit)? = forbidden) {
            val transport = Transport()
            assertEquals(DeviceAuthPhase.INVALID_CLIENT_ID, authorizeTwitchDevice(client, transport, {}, {},
                providerProfile = profile, retainSmartTvLocally = retain, inspectSmartTvLifetime = inspect,
                onOwnClientValidated = own, onProviderClientValidated = provider))
            assertEquals(0, transport.devices)
            assertEquals(0, transport.polls)
            assertEquals(0, transport.validations)
            assertTrue(transport.closed)
        }
        rejected(retain = false)
        rejected(profile = TwitchAuthorizationProfile.PROVIDER_SMART_TV)
        rejected(profile = TwitchAuthorizationProfile.PROVIDER_PLAYBACK)
        rejected(profile = TwitchAuthorizationProfile.TACHIAI)
        rejected(client = TACHIAI_TWITCH_CLIENT_ID)
        rejected(client = PROVIDER_TWITCH_CLIENT_ID)
        rejected(inspect = true)
        rejected(provider = null)
        rejected(own = forbidden)
        rejected(own = forbidden, provider = null)
    }

    @Test fun `strict TV behavior still rejects unknown grant lifetime and zero validation`() = runBlocking {
        listOf(emptyMap<String, Any?>(), mapOf("expires_in" to 0), mapOf("expires_in" to 60)).forEach { lifetime ->
            val transport = Transport().apply { grant = grant + lifetime }
            assertEquals(DeviceAuthPhase.INVALID_RESPONSE, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID,
                transport, {}, {}, waitMs = {}, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV,
                onProviderClientValidated = { _, _ -> fail("strict TV accepted zero expiry") }))
            assertTrue(transport.closed)
        }
    }

    @Test fun `local mode still rejects malformed grant lifetime and unvalidated identities or scopes`() = runBlocking {
        listOf<Any?>(null, -1, Int.MAX_VALUE.toLong() + 1, "0", 0.0).forEach { lifetime ->
            val transport = Transport().apply { grant = grant + ("expires_in" to lifetime) }
            assertEquals(DeviceAuthPhase.INVALID_RESPONSE, authorize(transport, Clock()) { _, _ -> fail("malformed grant") })
            assertEquals(0, transport.validations)
        }
        val base = Transport().response.fields
        val responses = listOf(
            DeviceAuthResponse(401, emptyMap()) to DeviceAuthPhase.REJECTED,
            DeviceAuthResponse(200, base + ("client_id" to TACHIAI_TWITCH_CLIENT_ID)) to DeviceAuthPhase.CLIENT_MISMATCH,
            DeviceAuthResponse(200, base + ("user_id" to "")) to DeviceAuthPhase.INVALID_RESPONSE,
            DeviceAuthResponse(200, base + ("scopes" to listOf("chat:read"))) to DeviceAuthPhase.SCOPE_MISMATCH,
            DeviceAuthResponse(200, base - "scopes") to DeviceAuthPhase.INVALID_RESPONSE,
            DeviceAuthResponse(200, base - "expires_in") to DeviceAuthPhase.INVALID_RESPONSE,
            DeviceAuthResponse(200, base + ("expires_in" to null)) to DeviceAuthPhase.INVALID_RESPONSE,
        )
        responses.forEach { (response, expected) ->
            val transport = Transport().apply { this.response = response }
            assertEquals(expected, authorize(transport, Clock()) { _, _ -> fail("unvalidated local grant") })
            assertTrue(transport.closed)
        }
    }

    @Test fun `new authorization validation must finish within thirty seconds despite seven day retention`() = runBlocking {
        listOf(30_000L, 30_001L).forEach { elapsed ->
            val clock = Clock()
            val transport = Transport().apply { onValidate = { clock.advance(elapsed) } }
            assertEquals(DeviceAuthPhase.EXPIRED, authorize(transport, clock) { _, _ -> fail("late validation") })
            assertEquals(1, transport.validations)
            assertTrue(transport.closed)
        }
    }

    @Test fun `local codec uses distinct version and binding without changing strict records`() {
        val record = SavedTwitchToken("invented-local-token", 1_000_000L, 1_100_000L)
        TwitchAuthorizationProfile.entries.forEach { source ->
            val bytes = encodeSavedTwitchToken(record, source)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                assertEquals(if (source == local) 2 else 1, input.readInt())
                if (source == local) assertEquals(local.name, input.readUTF())
                assertEquals(source.clientId, input.readUTF())
            }
            TwitchAuthorizationProfile.entries.forEach { target ->
                if (source == target) assertEquals(record.token, decodeSavedTwitchToken(bytes, target).token)
                else assertThrows(IllegalArgumentException::class.java) { decodeSavedTwitchToken(bytes, target) }
            }
        }
        val tooLong = SavedTwitchToken(record.token, record.savedAtMs, record.savedAtMs + SMART_TV_LOCAL_RETENTION_MS + 1)
        assertThrows(IllegalArgumentException::class.java) { encodeSavedTwitchToken(tooLong, local) }
    }

    @Test fun `seven day maximum save expires conservatively and cannot renew by cached use`() = runBlocking {
        val clock = Clock()
        val storage = Storage()
        val cache = cache(storage, clock)
        assertEquals(SavedAuthorizationState.EXPIRED, save(cache, clock, SMART_TV_LOCAL_RETENTION_MS + 1))
        assertEquals(SavedAuthorizationState.MISSING, cache.read().state)
        assertEquals(SavedAuthorizationState.SAVED, save(cache, clock))
        val original = storage.bytes!!.copyOf()
        repeat(2) {
            val transport = Transport()
            assertEquals(SavedTwitchUseOutcome.USED, useSavedTwitchAuthorization(cache, transport,
                clockMs = { clock.monotonic }, onUse = { _, deadline, _ ->
                    assertEquals(clock.monotonic + 30_000L, deadline)
                }).outcome)
            assertEquals(1, transport.validations)
            assertEquals(0, transport.devices)
            assertEquals(0, transport.polls)
            assertArrayEquals(original, storage.bytes)
            clock.advance(60_000L)
        }
        clock.advance(SMART_TV_LOCAL_RETENTION_MS - 120_000L - 10_000L)
        val expired = Transport()
        assertEquals(SavedTwitchUseOutcome.EXPIRED, useSavedTwitchAuthorization(cache, expired,
            onUse = { _, _, _ -> fail("expired local record") }).outcome)
        assertEquals(0, expired.validations)
        assertArrayEquals(original, storage.bytes)
    }

    @Test fun `remaining local retention checks owner revision rollback and margin`() = runBlocking {
        val clock = Clock()
        val storage = Storage()
        val cache = cache(storage, clock)
        save(cache, clock)
        val lease = cache.read().lease!!
        assertEquals(SMART_TV_LOCAL_RETENTION_MS - 10_000L, cache.remainingLocalMs(lease))
        assertEquals(0L, cache(storage, clock).remainingLocalMs(lease))
        clock.wall--
        assertEquals(0L, cache.remainingLocalMs(lease))
        assertEquals(SavedAuthorizationState.EXPIRED, cache.read().state)
        clock.wall = lease.record.expiresAtMs - 10_000L
        assertEquals(0L, cache.remainingLocalMs(lease))
        clock.wall = lease.record.savedAtMs
        cache.forget()
        assertEquals(0L, cache.remainingLocalMs(lease))
    }

    @Test fun `cached use caps thirty seconds by positive provider lifetime and remaining local retention`() = runBlocking {
        listOf(0 to 100_000L, 2 to 100_000L, 0 to 25_000L).forEach { (seconds, retention) ->
            val clock = Clock()
            val cache = cache(Storage(), clock)
            save(cache, clock, retention)
            val transport = Transport().apply { response = DeviceAuthResponse(200, response.fields + ("expires_in" to seconds)) }
            assertEquals(SavedTwitchUseOutcome.USED, useSavedTwitchAuthorization(cache, transport,
                clockMs = { clock.monotonic }, onUse = { _, deadline, _ ->
                    assertEquals(clock.monotonic + minOf(30_000L, retention - 10_000L,
                        if (seconds == 0) 30_000L else seconds * 1000L), deadline)
                }).outcome)
        }
    }

    @Test fun `slow validation and postvalidation wall movement cannot extend the initial use budget`() = runBlocking {
        val clock = Clock()
        val cache = cache(Storage(), clock)
        save(cache, clock, 50_000L)
        val transport = Transport().apply { onValidate = { clock.advance(5000L); clock.wall += 25_000L } }
        assertEquals(SavedTwitchUseOutcome.USED, useSavedTwitchAuthorization(cache, transport,
            clockMs = { clock.monotonic }, onUse = { _, deadline, _ -> assertEquals(16_000L, deadline) }).outcome)
        val expiredClock = Clock()
        val expiredCache = cache(Storage(), expiredClock)
        save(expiredCache, expiredClock)
        val late = Transport().apply { onValidate = { expiredClock.advance(30_000L) } }
        assertEquals(SavedTwitchUseOutcome.EXPIRED, useSavedTwitchAuthorization(expiredCache, late,
            clockMs = { expiredClock.monotonic }, onUse = { _, _, _ -> fail("late cached handoff") }).outcome)
        val rollbackClock = Clock()
        val rollbackCache = cache(Storage(), rollbackClock)
        save(rollbackCache, rollbackClock)
        val rollback = Transport().apply { onValidate = { rollbackClock.wall-- } }
        assertEquals(SavedTwitchUseOutcome.EXPIRED, useSavedTwitchAuthorization(rollbackCache, rollback,
            clockMs = { rollbackClock.monotonic }, onUse = { _, _, _ -> fail("rollback handoff") }).outcome)
    }

    @Test fun `revocation wrong identity scopes and malformed cached validation preserve the local record`() = runBlocking {
        val clock = Clock()
        val storage = Storage()
        val cache = cache(storage, clock)
        save(cache, clock)
        val original = storage.bytes!!.copyOf()
        val base = Transport().response.fields
        listOf(DeviceAuthResponse(401, emptyMap()),
            DeviceAuthResponse(200, base + ("client_id" to PROVIDER_TWITCH_CLIENT_ID)),
            DeviceAuthResponse(200, base + ("scopes" to listOf("chat:read"))),
            DeviceAuthResponse(200, base - "scopes"),
            DeviceAuthResponse(200, base - "expires_in"),
            DeviceAuthResponse(200, base + ("expires_in" to "0"))).forEach { response ->
            val transport = Transport().apply { this.response = response }
            assertEquals(SavedTwitchUseOutcome.VALIDATION_REJECTED, useSavedTwitchAuthorization(cache, transport,
                clockMs = { clock.monotonic }, onUse = { _, _, _ -> fail("rejected cached handoff") }).outcome)
            assertTrue(transport.closed)
            assertArrayEquals(original, storage.bytes)
        }
    }

    @Test fun `Forget background and cancellation during validation block local cached handoff`() = runBlocking {
        val clock = Clock()
        val cache = cache(Storage(), clock)
        save(cache, clock)
        val forgotten = Transport().apply { onValidate = { cache.forget() } }
        val forgottenOutcome = useSavedTwitchAuthorization(cache, forgotten, clockMs = { clock.monotonic },
            onUse = { _, _, _ -> fail("forgotten local handoff") }).outcome
        assertTrue(forgottenOutcome in setOf(SavedTwitchUseOutcome.EXPIRED, SavedTwitchUseOutcome.SUPERSEDED))
        assertTrue(forgotten.closed)
        save(cache, clock)
        val foreground = DeviceAuthorizationForeground()
        val background = Transport().apply { onValidate = { foreground.setForeground(false) } }
        assertEquals(SavedTwitchUseOutcome.SUPERSEDED, useSavedTwitchAuthorization(cache, background, foreground,
            clockMs = { clock.monotonic }, onUse = { _, _, _ -> fail("background local handoff") }).outcome)
        assertTrue(background.closed)
        val cancelled = Transport()
        val worker = async {
            val context = currentCoroutineContext()
            cancelled.onValidate = { context.cancel() }
            useSavedTwitchAuthorization(cache, cancelled, clockMs = { clock.monotonic },
                onUse = { _, _, _ -> fail("cancelled local handoff") })
        }
        try {
            worker.await()
            fail("cancelled cached request returned")
        } catch (_: CancellationException) { }
        assertTrue(cancelled.closed)
    }

    @Test fun `local storage leases and Forget never cross the strict TV slot with the same client ID`() = runBlocking {
        val clock = Clock()
        val localStorage = Storage()
        val strictStorage = Storage()
        val localCache = cache(localStorage, clock)
        val strictCache = cache(strictStorage, clock, TwitchAuthorizationProfile.PROVIDER_SMART_TV)
        save(localCache, clock)
        save(strictCache, clock, 100_000L)
        val localLease = localCache.read().lease!!
        val strictLease = strictCache.read().lease!!
        assertFalse(localCache.isCurrent(strictLease))
        assertFalse(strictCache.isCurrent(localLease))
        assertEquals(SavedAuthorizationState.UNREADABLE,
            cache(localStorage, clock, TwitchAuthorizationProfile.PROVIDER_SMART_TV).read().state)
        assertEquals(SavedAuthorizationState.UNREADABLE, cache(strictStorage, clock).read().state)
        localCache.forget()
        assertEquals(SavedAuthorizationState.MISSING, localCache.read().state)
        assertEquals(SavedAuthorizationState.AVAILABLE, strictCache.read().state)
        assertTrue(strictCache.isCurrent(strictLease))
    }

    @Test fun `old hour records keep their deadline until explicit freshly validated extension`() = runBlocking {
        val clock = Clock(); val storage = Storage(); val cache = cache(storage, clock)
        save(cache, clock, 3_600_000)
        val original = storage.bytes!!.copyOf()
        val originalExpiry = cache.read().lease!!.record.expiresAtMs
        clock.advance(60_000)
        assertEquals(SavedTwitchUseOutcome.USED, useSavedTwitchAuthorization(cache, Transport(),
            clockMs = { clock.monotonic }, onUse = { _, _, _ -> }).outcome)
        assertArrayEquals(original, storage.bytes)
        assertEquals(originalExpiry, cache.read().lease!!.record.expiresAtMs)
        val transport = Transport().apply { onValidate = { clock.advance(2_000) } }
        val validationStartWall = clock.wall
        assertEquals(LocalRetentionExtensionResult(LocalRetentionExtensionOutcome.SAVED, 200),
            extendSavedTwitchLocalRetention(cache, transport, DeviceAuthorizationForeground(), { clock.monotonic }))
        assertEquals(validationStartWall + SMART_TV_LOCAL_RETENTION_MS,
            cache(storage, clock).read().lease!!.record.expiresAtMs)
        assertEquals(0, transport.devices); assertEquals(0, transport.polls)
        assertEquals(1, transport.validations); assertTrue(transport.closed)
    }

    @Test fun `extension uses smaller positive validation lifetime rather than claiming permanent validity`() = runBlocking {
        for (seconds in listOf(15, 7200, 1_000_000)) {
            val clock = Clock(); val cache = cache(Storage(), clock)
            save(cache, clock, 3_600_000)
            val start = clock.wall
            val transport = Transport().apply {
                response = DeviceAuthResponse(200, response.fields + ("expires_in" to seconds))
            }
            assertEquals(LocalRetentionExtensionOutcome.SAVED,
                extendSavedTwitchLocalRetention(cache, transport, DeviceAuthorizationForeground(), { clock.monotonic }).outcome)
            assertEquals(start + minOf(SMART_TV_LOCAL_RETENTION_MS, seconds * 1000L), cache.read().lease!!.record.expiresAtMs)
        }
    }

    @Test fun `extension refuses missing expired malformed and wrong profile before validation`() = runBlocking {
        val clock = Clock(); val storage = Storage(); val cache = cache(storage, clock)
        suspend fun check(expected: LocalRetentionExtensionOutcome, target: TwitchSavedAuthorization = cache) {
            val transport = Transport()
            assertEquals(expected, extendSavedTwitchLocalRetention(target, transport,
                DeviceAuthorizationForeground(), { clock.monotonic }).outcome)
            assertEquals(0, transport.validations); assertEquals(0, transport.devices); assertTrue(transport.closed)
        }
        check(LocalRetentionExtensionOutcome.NO_TOKEN)
        storage.bytes = byteArrayOf(1, 2, 3)
        check(LocalRetentionExtensionOutcome.STORAGE_FAILED)
        save(cache, clock, 20_000); clock.advance(10_000)
        check(LocalRetentionExtensionOutcome.EXPIRED)
        for (profile in TwitchAuthorizationProfile.entries.filter { it != local })
            check(LocalRetentionExtensionOutcome.WRONG_PROFILE, cache(Storage(), clock, profile))
    }

    @Test fun `extension validation rejection never rewrites existing bytes`() = runBlocking {
        val clock = Clock(); val storage = Storage(); val cache = cache(storage, clock)
        save(cache, clock, 3_600_000)
        val original = storage.bytes!!.copyOf()
        val base = Transport().response
        for (response in listOf(DeviceAuthResponse(401, emptyMap()),
            DeviceAuthResponse(200, base.fields + ("client_id" to TACHIAI_TWITCH_CLIENT_ID)),
            DeviceAuthResponse(200, base.fields + ("user_id" to "")),
            DeviceAuthResponse(200, base.fields + ("scopes" to listOf("chat:read"))),
            DeviceAuthResponse(200, base.fields - "expires_in"),
            DeviceAuthResponse(200, base.fields + ("expires_in" to null)),
            DeviceAuthResponse(200, base.fields + ("expires_in" to "0")))) {
            val transport = Transport().apply { this.response = response }
            assertEquals(LocalRetentionExtensionOutcome.VALIDATION_REJECTED,
                extendSavedTwitchLocalRetention(cache, transport, DeviceAuthorizationForeground(), { clock.monotonic }).outcome)
            assertArrayEquals(original, storage.bytes); assertTrue(transport.closed)
        }
    }

    @Test fun `extension cannot resurrect original expiry or exceed acceptance budget`() = runBlocking {
        for ((retention, elapsed) in listOf(20_000L to 10_000L, 3_600_000L to 30_000L)) {
            val clock = Clock(); val storage = Storage(); val cache = cache(storage, clock)
            save(cache, clock, retention)
            val original = storage.bytes!!.copyOf()
            val transport = Transport().apply { onValidate = { clock.advance(elapsed) } }
            assertEquals(LocalRetentionExtensionOutcome.EXPIRED,
                extendSavedTwitchLocalRetention(cache, transport, DeviceAuthorizationForeground(), { clock.monotonic }).outcome)
            assertArrayEquals(original, storage.bytes); assertTrue(transport.closed)
        }
    }

    @Test fun `extension anchors initial remaining retention to monotonic time despite wall rollback`() = runBlocking {
        val clock = Clock(); val storage = Storage(); val cache = cache(storage, clock)
        save(cache, clock, 20_000)
        clock.advance(5_000) // 5 usable seconds after the safety margin.
        val original = storage.bytes!!.copyOf()
        val transport = Transport().apply { onValidate = {
            clock.monotonic += 6_000
            clock.wall -= 1_000 // Still later than savedAt, but cannot extend acceptance.
        } }
        assertEquals(LocalRetentionExtensionOutcome.EXPIRED,
            extendSavedTwitchLocalRetention(cache, transport, DeviceAuthorizationForeground(), { clock.monotonic }).outcome)
        assertArrayEquals(original, storage.bytes); assertTrue(transport.closed)
    }

    @Test fun `extension respects foreground Forget replacement and cancelled validation`() = runBlocking {
        for (action in listOf("BACKGROUND", "FORGET", "REPLACE")) {
            val clock = Clock(); val storage = Storage(); val cache = cache(storage, clock)
            save(cache, clock, 3_600_000)
            val foreground = DeviceAuthorizationForeground()
            val transport = Transport().apply { onValidate = {
                when (action) {
                    "BACKGROUND" -> foreground.setForeground(false)
                    "FORGET" -> cache.forget()
                    else -> runBlocking { save(cache, clock, 100_000) }
                }
            } }
            assertEquals(LocalRetentionExtensionOutcome.SUPERSEDED,
                extendSavedTwitchLocalRetention(cache, transport, foreground, { clock.monotonic }).outcome)
            if (action == "REPLACE") assertEquals(clock.wall + 100_000, cache.read().lease!!.record.expiresAtMs)
            if (action == "FORGET") assertEquals(SavedAuthorizationState.MISSING, cache.read().state)
            assertTrue(transport.closed)
        }
        val clock = Clock(); val storage = Storage(); val cache = cache(storage, clock)
        save(cache, clock, 3_600_000)
        val original = storage.bytes!!.copyOf()
        val transport = Transport()
        val worker = async {
            val coroutine = currentCoroutineContext()
            transport.onValidate = { coroutine.cancel() }
            extendSavedTwitchLocalRetention(cache, transport, DeviceAuthorizationForeground(), { clock.monotonic })
        }
        try { worker.await(); fail("cancelled extension returned") } catch (_: CancellationException) { }
        assertArrayEquals(original, storage.bytes); assertTrue(transport.closed)
    }

    @Test fun `extension storage and network errors retain prior record`() = runBlocking {
        val clock = Clock(); val storage = Storage(); val cache = cache(storage, clock)
        save(cache, clock, 3_600_000)
        val original = storage.bytes!!.copyOf()
        storage.failWrites = true
        assertEquals(LocalRetentionExtensionOutcome.STORAGE_FAILED, extendSavedTwitchLocalRetention(cache,
            Transport(), DeviceAuthorizationForeground(), { clock.monotonic }).outcome)
        val transport = Transport().apply { onValidate = { throw java.io.IOException("invented-sensitive-error") } }
        assertEquals(LocalRetentionExtensionOutcome.NETWORK_FAILED,
            extendSavedTwitchLocalRetention(cache, transport, DeviceAuthorizationForeground(), { clock.monotonic }).outcome)
        assertArrayEquals(original, storage.bytes); assertTrue(transport.closed)
    }
}
