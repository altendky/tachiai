package net.fstab.tachiai.provider.twitch

import kotlinx.coroutines.runBlocking
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import org.junit.Assert.*
import org.junit.Test

class TwitchSmartTvAuthorizationTest {
    private class MemoryStore : PrivateSecretStore {
        private var content: ByteArray? = null
        override fun read(): ByteArray? = content?.copyOf()
        override fun write(plaintext: ByteArray) { content = plaintext.copyOf() }
    }

    private class GrantTransport(private val expectedClient: String = SMART_TV_TWITCH_CLIENT_ID) : TwitchDeviceTransport {
        var devices = 0
        var polls = 0
        var validations = 0
        var closed = false
        var validationFields: Map<String, Any?> = mapOf(
            "client_id" to expectedClient, "user_id" to "invented-tv-viewer", "expires_in" to 50, "scopes" to null,
        )

        override fun device(clientId: String): DeviceAuthResponse {
            assertEquals(expectedClient, clientId)
            devices++
            return DeviceAuthResponse(200, mapOf(
                "device_code" to "invented-tv-device", "user_code" to "TVTEST01", "interval" to 1, "expires_in" to 60,
                "verification_uri" to "https://www.twitch.tv/activate?public=true&device-code=TVTEST01",
            ))
        }

        override fun poll(clientId: String, deviceCode: String): DeviceAuthResponse {
            assertEquals(expectedClient, clientId)
            assertEquals("invented-tv-device", deviceCode)
            polls++
            return DeviceAuthResponse(200, mapOf(
                "access_token" to "invented-tv-session", "token_type" to "bearer", "expires_in" to 60,
            ))
        }

        override fun validate(accessToken: String): DeviceAuthResponse {
            assertEquals("invented-tv-session", accessToken)
            validations++
            return DeviceAuthResponse(200, validationFields)
        }

        override fun close() { closed = true }
    }

    @Test fun `remaining provider identities preserve their original storage slots`() {
        assertEquals(SMART_TV_TWITCH_CLIENT_ID, TwitchAuthorizationProfile.PROVIDER_SMART_TV.clientId)
        assertEquals(2, TwitchAuthorizationProfile.entries.map { it.clientId }.toSet().size)
        assertEquals(TwitchAuthorizationProfile.entries.size, TwitchAuthorizationProfile.entries.map { it.storageSlot }.toSet().size)
        assertEquals(TwitchAuthorizationProfile.entries.size, TwitchAuthorizationProfile.entries.map { it.storageSlot.bindingName }.toSet().size)
        assertEquals("twitch-provider-playback-authorization", TwitchAuthorizationProfile.PROVIDER_PLAYBACK.storageSlot.bindingName)
    }

    @Test fun `explicit Smart TV profile permits exactly one validated provider callback`() = runBlocking {
        val transport = GrantTransport()
        var now = 3000L
        var callbacks = 0
        val phase = authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID, transport, {}, {},
            clockMs = { now }, waitMs = { now += it }, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV,
            onProviderClientValidated = { token, deadline ->
                assertEquals("invented-tv-session", token)
                assertEquals(54000L, deadline)
                assertEquals(1, transport.validations)
                callbacks++
            })
        assertEquals(DeviceAuthPhase.SUCCEEDED, phase)
        assertEquals(1, callbacks)
        assertEquals(1, transport.devices)
        assertEquals(1, transport.polls)
        assertTrue(transport.closed)
    }

    @Test fun `omitted provider profile retains the original web callback behavior`() = runBlocking {
        val transport = GrantTransport(PROVIDER_TWITCH_CLIENT_ID)
        var callbacks = 0
        assertEquals(DeviceAuthPhase.SUCCEEDED, authorizeTwitchDevice(PROVIDER_TWITCH_CLIENT_ID,
            transport, {}, {}, waitMs = {}, onProviderClientValidated = { _, _ -> callbacks++ }))
        assertEquals(1, callbacks)
        assertTrue(transport.closed)
    }

    @Test fun `crossed default and explicit identities reject callbacks before IO`() = runBlocking {
        val forbidden: suspend (String, Long) -> Unit = { _, _ -> fail("cross-identity callback") }
        val defaultWebWithTv = GrantTransport()
        assertEquals(DeviceAuthPhase.INVALID_CLIENT_ID, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID,
            defaultWebWithTv, {}, {}, onProviderClientValidated = forbidden))
        val explicitWebWithTv = GrantTransport()
        assertEquals(DeviceAuthPhase.INVALID_CLIENT_ID, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID,
            explicitWebWithTv, {}, {}, providerProfile = TwitchAuthorizationProfile.PROVIDER_PLAYBACK,
            onProviderClientValidated = forbidden))
        val explicitTvWithWeb = GrantTransport(PROVIDER_TWITCH_CLIENT_ID)
        assertEquals(DeviceAuthPhase.INVALID_CLIENT_ID, authorizeTwitchDevice(PROVIDER_TWITCH_CLIENT_ID,
            explicitTvWithWeb, {}, {}, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV,
            onProviderClientValidated = forbidden))
        val unrelatedClient = GrantTransport("anotherpublicclient")
        assertEquals(DeviceAuthPhase.INVALID_CLIENT_ID, authorizeTwitchDevice("anotherpublicclient",
            unrelatedClient, {}, {}, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV,
            onProviderClientValidated = forbidden))
        listOf(defaultWebWithTv, explicitWebWithTv, explicitTvWithWeb, unrelatedClient).forEach {
            assertEquals(0, it.devices)
            assertEquals(0, it.polls)
            assertEquals(0, it.validations)
            assertTrue(it.closed)
        }
    }

    @Test fun `Smart TV identity mismatch and unexpected permissions cannot hand off`() = runBlocking {
        val base = GrantTransport().validationFields
        val responses = listOf(
            (base + ("client_id" to "anotherpublicclient")) to DeviceAuthPhase.CLIENT_MISMATCH,
            (base + ("client_id" to PROVIDER_TWITCH_CLIENT_ID)) to DeviceAuthPhase.CLIENT_MISMATCH,
            (base + ("scopes" to listOf("chat:read"))) to DeviceAuthPhase.SCOPE_MISMATCH,
            (base - "scopes") to DeviceAuthPhase.INVALID_RESPONSE,
        )
        responses.forEach { (fields, expected) ->
            val transport = GrantTransport().apply { validationFields = fields }
            assertEquals(expected, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID, transport, {}, {}, waitMs = {},
                providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV,
                onProviderClientValidated = { _, _ -> fail("unvalidated Smart TV session") }))
            assertTrue(transport.closed)
        }
    }

    @Test fun `every saved codec and repository lease rejects the other two profiles`() = runBlocking {
        val record = SavedTwitchToken("invented-tv-session", 1000000L, 1100000L)
        val stores = TwitchAuthorizationProfile.entries.associateWith { MemoryStore() }
        val caches = stores.mapValues { (profile, store) ->
            TwitchSavedAuthorization(store, { 1000000L }, { 1000L }, profile)
        }
        TwitchAuthorizationProfile.entries.forEach { origin ->
            val bytes = encodeSavedTwitchToken(record, origin)
            TwitchAuthorizationProfile.entries.forEach { target ->
                if (origin == target) assertEquals(record.token, decodeSavedTwitchToken(bytes, target).token)
                else assertThrows(IllegalArgumentException::class.java) { decodeSavedTwitchToken(bytes, target) }
            }
            val cache = caches.getValue(origin)
            assertEquals(SavedAuthorizationState.SAVED, cache.saveValidated(record.token, 101000L, cache.revision()))
        }
        caches.forEach { (origin, cache) ->
            val lease = cache.read().lease!!
            caches.forEach { (target, other) -> assertEquals(origin == target, other.isCurrent(lease)) }
            TwitchAuthorizationProfile.entries.filter { it != origin }.forEach { target ->
                val crossed = TwitchSavedAuthorization(stores.getValue(origin), { 1000000L }, { 1000L }, target)
                assertEquals(SavedAuthorizationState.UNREADABLE, crossed.read().state)
            }
        }
    }

    @Test fun `Forget invalidates only the selected profile and its pending save`() = runBlocking {
        TwitchAuthorizationProfile.entries.forEach { forgotten ->
            val caches = TwitchAuthorizationProfile.entries.associateWith { profile ->
                TwitchSavedAuthorization(MemoryStore(), { 1000000L }, { 1000L }, profile)
            }
            caches.values.forEach { cache -> cache.saveValidated("invented-tv-session", 101000L, cache.revision()) }
            val leases = caches.mapValues { (_, cache) -> cache.read().lease!! }
            val pending = caches.mapValues { (_, cache) -> cache.revision() }
            assertEquals(SavedAuthorizationState.FORGOTTEN, caches.getValue(forgotten).forget())
            caches.forEach { (profile, cache) ->
                assertEquals(if (profile == forgotten) SavedAuthorizationState.MISSING else SavedAuthorizationState.AVAILABLE,
                    cache.read().state)
                assertEquals(profile != forgotten, cache.isCurrent(leases.getValue(profile)))
                assertEquals(if (profile == forgotten) SavedAuthorizationState.SUPERSEDED else SavedAuthorizationState.SAVED,
                    cache.saveValidated("invented-tv-session", 101000L, pending.getValue(profile)))
            }
        }
    }

    @Test fun `cached Smart TV use rejects validation for either older identity without replacing records`() = runBlocking {
        val cache = TwitchSavedAuthorization(MemoryStore(), { 1000000L }, { 1000L },
            TwitchAuthorizationProfile.PROVIDER_SMART_TV)
        cache.saveValidated("invented-tv-session", 101000L, cache.revision())
        listOf("anotherpublicclient", PROVIDER_TWITCH_CLIENT_ID).forEach { wrongClient ->
            val transport = GrantTransport().apply { validationFields = validationFields + ("client_id" to wrongClient) }
            assertEquals(SavedTwitchUseOutcome.VALIDATION_REJECTED,
                useSavedTwitchAuthorization(cache, transport, onUse = { _, _, _ -> fail("older profile authorized TV use") }).outcome)
            assertEquals(0, transport.devices)
            assertEquals(0, transport.polls)
            assertEquals(1, transport.validations)
            assertTrue(transport.closed)
            assertEquals(SavedAuthorizationState.AVAILABLE, cache.read().state)
        }
        val accepted = GrantTransport()
        var uses = 0
        assertEquals(SavedTwitchUseOutcome.USED,
            useSavedTwitchAuthorization(cache, accepted, clockMs = { 1000L }, onUse = { _, _, lease ->
                assertTrue(cache.isCurrent(lease))
                uses++
            }).outcome)
        assertEquals(1, uses)
        assertEquals(1, accepted.validations)
        assertTrue(accepted.closed)
    }
}
