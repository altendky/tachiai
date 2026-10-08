package net.fstab.tachiai.provider.twitch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import org.junit.Assert.*
import org.junit.Test

class TwitchProviderAuthorizationTest {
    private class Storage : PrivateSecretStore {
        private var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
    }

    private class Transport(private val client: String = PROVIDER_TWITCH_CLIENT_ID) : TwitchDeviceTransport {
        var deviceCalls = 0
        var pollCalls = 0
        var validationCalls = 0
        var closed = false
        var expectedToken = "synthetic-provider-session"
        var onValidate: () -> Unit = {}
        var grantFields: Map<String, Any?> = mapOf(
            "access_token" to expectedToken, "token_type" to "bearer", "expires_in" to 60,
        )
        var validation = DeviceAuthResponse(200, mapOf(
            "client_id" to client, "user_id" to "synthetic-viewer", "expires_in" to 50, "scopes" to null,
        ))

        override fun device(clientId: String): DeviceAuthResponse {
            assertEquals(client, clientId)
            deviceCalls++
            return DeviceAuthResponse(200, mapOf(
                "device_code" to "synthetic-device", "user_code" to "TESTCODE",
                "expires_in" to 60, "interval" to 1,
                "verification_uri" to "https://www.twitch.tv/activate?public=true&device-code=TESTCODE",
            ))
        }

        override fun poll(clientId: String, deviceCode: String): DeviceAuthResponse {
            assertEquals(client, clientId)
            assertEquals("synthetic-device", deviceCode)
            pollCalls++
            return DeviceAuthResponse(200, grantFields)
        }

        override fun validate(accessToken: String): DeviceAuthResponse {
            assertEquals(expectedToken, accessToken)
            validationCalls++
            onValidate()
            return validation
        }

        override fun close() { closed = true }
    }

    @Test fun `profiles bind separate identities and storage slots`() {
        assertEquals(TACHIAI_TWITCH_CLIENT_ID, TwitchAuthorizationProfile.TACHIAI.clientId)
        assertEquals(PROVIDER_TWITCH_CLIENT_ID, TwitchAuthorizationProfile.PROVIDER_PLAYBACK.clientId)
        assertNotEquals(TwitchAuthorizationProfile.TACHIAI.clientId, TwitchAuthorizationProfile.PROVIDER_PLAYBACK.clientId)
        assertNotEquals(TwitchAuthorizationProfile.TACHIAI.storageSlot, TwitchAuthorizationProfile.PROVIDER_PLAYBACK.storageSlot)
    }

    @Test fun `provider callback follows exact validation with a conservative deadline`() = runBlocking {
        val transport = Transport()
        var now = 1200L
        var callbacks = 0
        val phase = authorizeTwitchDevice(PROVIDER_TWITCH_CLIENT_ID, transport, {}, {},
            clockMs = { now }, waitMs = { now += it }, onProviderClientValidated = { token, deadline ->
                assertEquals(1, transport.validationCalls)
                assertEquals("synthetic-provider-session", token)
                assertEquals(52200L, deadline)
                callbacks++
            })
        assertEquals(DeviceAuthPhase.SUCCEEDED, phase)
        assertEquals(1, callbacks)
        assertEquals(1, transport.deviceCalls)
        assertEquals(1, transport.pollCalls)
        assertTrue(transport.closed)
    }

    @Test fun `crossed identities and simultaneous callbacks are rejected before network IO`() = runBlocking {
        val forbidden: suspend (String, Long) -> Unit = { _, _ -> fail("crossed authorization callback") }
        val ownUsingProviderCallback = Transport(TACHIAI_TWITCH_CLIENT_ID)
        assertEquals(DeviceAuthPhase.INVALID_CLIENT_ID, authorizeTwitchDevice(TACHIAI_TWITCH_CLIENT_ID,
            ownUsingProviderCallback, {}, {}, onProviderClientValidated = forbidden))
        val providerUsingOwnCallback = Transport()
        assertEquals(DeviceAuthPhase.INVALID_CLIENT_ID, authorizeTwitchDevice(PROVIDER_TWITCH_CLIENT_ID,
            providerUsingOwnCallback, {}, {}, onOwnClientValidated = forbidden))
        val bothCallbacks = Transport()
        assertEquals(DeviceAuthPhase.INVALID_CLIENT_ID, authorizeTwitchDevice(PROVIDER_TWITCH_CLIENT_ID,
            bothCallbacks, {}, {}, onOwnClientValidated = forbidden, onProviderClientValidated = forbidden))
        val unrelatedClient = Transport("differentpublicclient")
        assertEquals(DeviceAuthPhase.INVALID_CLIENT_ID, authorizeTwitchDevice("differentpublicclient",
            unrelatedClient, {}, {}, onProviderClientValidated = forbidden))
        listOf(ownUsingProviderCallback, providerUsingOwnCallback, bothCallbacks, unrelatedClient).forEach {
            assertEquals(0, it.deviceCalls)
            assertEquals(0, it.pollCalls)
            assertEquals(0, it.validationCalls)
            assertTrue(it.closed)
        }
    }

    @Test fun `own callback still succeeds only for the original own identity`() = runBlocking {
        val transport = Transport(TACHIAI_TWITCH_CLIENT_ID)
        var callbacks = 0
        val phase = authorizeTwitchDevice(TACHIAI_TWITCH_CLIENT_ID, transport, {}, {}, waitMs = {},
            onOwnClientValidated = { _, _ -> callbacks++ })
        assertEquals(DeviceAuthPhase.SUCCEEDED, phase)
        assertEquals(1, callbacks)
        assertTrue(transport.closed)
    }

    @Test fun `provider mismatched identity user and permissions never reach callback`() = runBlocking {
        val base = Transport().validation.fields
        val cases = listOf(
            DeviceAuthResponse(200, base + ("client_id" to TACHIAI_TWITCH_CLIENT_ID)) to DeviceAuthPhase.CLIENT_MISMATCH,
            DeviceAuthResponse(200, base + ("user_id" to "")) to DeviceAuthPhase.INVALID_RESPONSE,
            DeviceAuthResponse(200, base + ("scopes" to listOf("chat:read"))) to DeviceAuthPhase.SCOPE_MISMATCH,
            DeviceAuthResponse(200, base - "scopes") to DeviceAuthPhase.INVALID_RESPONSE,
        )
        cases.forEach { (response, expected) ->
            val transport = Transport().apply { validation = response }
            assertEquals(expected, authorizeTwitchDevice(PROVIDER_TWITCH_CLIENT_ID, transport, {}, {}, waitMs = {},
                onProviderClientValidated = { _, _ -> fail("invalid provider token handed onward") }))
            assertTrue(transport.closed)
        }
        val extraGrantScope = Transport().apply { grantFields = grantFields + ("scope" to listOf("chat:read")) }
        assertEquals(DeviceAuthPhase.SCOPE_MISMATCH, authorizeTwitchDevice(PROVIDER_TWITCH_CLIENT_ID,
            extraGrantScope, {}, {}, waitMs = {}, onProviderClientValidated = { _, _ -> fail("extra grant permissions") }))
        assertEquals(0, extraGrantScope.validationCalls)
        assertTrue(extraGrantScope.closed)
    }

    @Test fun `provider expiry and cancellation prevent a successful handoff`() = runBlocking {
        var now = 0L
        val expired = Transport().apply { onValidate = { now = 100000L } }
        assertEquals(DeviceAuthPhase.EXPIRED, authorizeTwitchDevice(PROVIDER_TWITCH_CLIENT_ID, expired, {}, {},
            clockMs = { now }, waitMs = { now += it },
            onProviderClientValidated = { _, _ -> fail("expired provider callback") }))
        assertTrue(expired.closed)
        val cancelled = Transport()
        try {
            authorizeTwitchDevice(PROVIDER_TWITCH_CLIENT_ID, cancelled, {}, {}, waitMs = {},
                onProviderClientValidated = { _, _ -> throw CancellationException() })
            fail("cancelled provider callback returned success")
        } catch (_: CancellationException) { }
        assertTrue(cancelled.closed)
    }

    @Test fun `provider foreground return defers handoff without requesting another grant`() = runBlocking {
        val foreground = DeviceAuthorizationForeground()
        val validated = CompletableDeferred<Unit>()
        var callbacks = 0
        val transport = Transport().apply { onValidate = {
            foreground.setForeground(false)
            validated.complete(Unit)
        } }
        val worker = async {
            authorizeTwitchDevice(PROVIDER_TWITCH_CLIENT_ID, transport, {}, {}, waitMs = {},
                foreground = foreground, onProviderClientValidated = { _, _ -> callbacks++ })
        }
        validated.await()
        assertEquals(0, callbacks)
        foreground.setForeground(true)
        assertEquals(DeviceAuthPhase.SUCCEEDED, worker.await())
        assertEquals(1, callbacks)
        assertEquals(1, transport.deviceCalls)
        assertEquals(1, transport.pollCalls)
        assertEquals(1, transport.validationCalls)
        assertTrue(transport.closed)
    }

    @Test fun `saved record codecs reject the opposite profile without changing own defaults`() {
        val record = SavedTwitchToken("synthetic-provider-session", 1000000L, 1100000L)
        val ownBytes = encodeSavedTwitchToken(record)
        val providerBytes = encodeSavedTwitchToken(record, TwitchAuthorizationProfile.PROVIDER_PLAYBACK)
        assertEquals(record.token, decodeSavedTwitchToken(ownBytes).token)
        assertEquals(record.token, decodeSavedTwitchToken(providerBytes, TwitchAuthorizationProfile.PROVIDER_PLAYBACK).token)
        assertThrows(IllegalArgumentException::class.java) { decodeSavedTwitchToken(providerBytes) }
        assertThrows(IllegalArgumentException::class.java) {
            decodeSavedTwitchToken(ownBytes, TwitchAuthorizationProfile.PROVIDER_PLAYBACK)
        }
        assertFalse(providerBytes.contentEquals(ownBytes))
    }

    @Test fun `repository leases reject both opposite profiles and other same-profile instances`() = runBlocking {
        val ownStorage = Storage()
        val providerStorage = Storage()
        val own = TwitchSavedAuthorization(ownStorage, { 1000000L }, { 1000L })
        val provider = TwitchSavedAuthorization(providerStorage, { 1000000L }, { 1000L },
            profile = TwitchAuthorizationProfile.PROVIDER_PLAYBACK)
        assertEquals(SavedAuthorizationState.SAVED, own.saveValidated("synthetic-own-session", 101000L, own.revision()))
        assertEquals(SavedAuthorizationState.SAVED, provider.saveValidated("synthetic-provider-session", 101000L, provider.revision()))
        assertEquals(own.revision(), provider.revision())
        val ownLease = own.read().lease!!
        val providerLease = provider.read().lease!!
        assertTrue(own.isCurrent(ownLease))
        assertTrue(provider.isCurrent(providerLease))
        assertFalse(own.isCurrent(providerLease))
        assertFalse(provider.isCurrent(ownLease))
        val recreated = TwitchSavedAuthorization(providerStorage, { 1000000L }, { 1000L },
            profile = TwitchAuthorizationProfile.PROVIDER_PLAYBACK)
        assertEquals(SavedAuthorizationState.SAVED, recreated.saveValidated("synthetic-provider-session", 101000L, recreated.revision()))
        assertEquals(provider.revision(), recreated.revision())
        assertFalse(recreated.isCurrent(providerLease))
        assertFalse(provider.isCurrent(recreated.read().lease!!))
        val crossedStore = TwitchSavedAuthorization(ownStorage, { 1000000L }, { 1000L },
            profile = TwitchAuthorizationProfile.PROVIDER_PLAYBACK)
        assertEquals(SavedAuthorizationState.UNREADABLE, crossedStore.read().state)
    }

    @Test fun `Forget and stale saves are isolated between own and provider repositories`() = runBlocking {
        val own = TwitchSavedAuthorization(Storage(), { 1000000L }, { 1000L })
        val provider = TwitchSavedAuthorization(Storage(), { 1000000L }, { 1000L },
            profile = TwitchAuthorizationProfile.PROVIDER_PLAYBACK)
        own.saveValidated("synthetic-own-session", 101000L, own.revision())
        provider.saveValidated("synthetic-provider-session", 101000L, provider.revision())
        val ownAttempt = own.revision()
        val providerLease = provider.read().lease!!
        own.forget()
        assertEquals(SavedAuthorizationState.MISSING, own.read().state)
        assertEquals(SavedAuthorizationState.AVAILABLE, provider.read().state)
        assertTrue(provider.isCurrent(providerLease))
        assertEquals(SavedAuthorizationState.SUPERSEDED,
            own.saveValidated("synthetic-own-session", 101000L, ownAttempt))
        own.saveValidated("synthetic-own-session", 101000L, own.revision())
        val ownLease = own.read().lease!!
        val providerAttempt = provider.revision()
        provider.forget()
        assertEquals(SavedAuthorizationState.MISSING, provider.read().state)
        assertEquals(SavedAuthorizationState.AVAILABLE, own.read().state)
        assertTrue(own.isCurrent(ownLease))
        assertEquals(SavedAuthorizationState.SUPERSEDED,
            provider.saveValidated("synthetic-provider-session", 101000L, providerAttempt))
    }

    @Test fun `cached provider use validates its own binding and preserves both slots on rejection`() = runBlocking {
        val own = TwitchSavedAuthorization(Storage(), { 1000000L }, { 1000L })
        val provider = TwitchSavedAuthorization(Storage(), { 1000000L }, { 1000L },
            profile = TwitchAuthorizationProfile.PROVIDER_PLAYBACK)
        own.saveValidated("synthetic-own-session", 101000L, own.revision())
        provider.saveValidated("synthetic-provider-session", 101000L, provider.revision())
        val accepted = Transport()
        var uses = 0
        val success = useSavedTwitchAuthorization(provider, accepted, clockMs = { 1000L }, onUse = { token, deadline, lease ->
            assertEquals("synthetic-provider-session", token)
            assertEquals(51000L, deadline)
            assertTrue(provider.isCurrent(lease))
            assertFalse(own.isCurrent(lease))
            uses++
        })
        assertEquals(SavedTwitchUseOutcome.USED, success.outcome)
        assertEquals(1, uses)
        assertEquals(0, accepted.deviceCalls)
        assertEquals(0, accepted.pollCalls)
        assertEquals(1, accepted.validationCalls)
        assertTrue(accepted.closed)
        val wrongClient = Transport().apply {
            validation = DeviceAuthResponse(200, validation.fields + ("client_id" to TACHIAI_TWITCH_CLIENT_ID))
        }
        assertEquals(SavedTwitchUseOutcome.VALIDATION_REJECTED,
            useSavedTwitchAuthorization(provider, wrongClient, onUse = { _, _, _ -> fail("own-client validation accepted for provider slot") }).outcome)
        assertTrue(wrongClient.closed)
        assertEquals(SavedAuthorizationState.AVAILABLE, own.read().state)
        assertEquals(SavedAuthorizationState.AVAILABLE, provider.read().state)
    }
}
