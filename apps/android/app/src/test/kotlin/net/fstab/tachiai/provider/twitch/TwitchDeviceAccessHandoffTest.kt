package net.fstab.tachiai.provider.twitch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test

class TwitchDeviceAccessHandoffTest {
    private class Transport(val client: String = SMART_TV_TWITCH_CLIENT_ID, val user: String = "fixture-user") : TwitchDeviceTransport {
        var validated = false
        var closed = false
        var onValidate: () -> Unit = {}
        override fun device(clientId: String) = DeviceAuthResponse(200, mapOf(
            "device_code" to "fixture-device", "user_code" to "ABCDEFGH", "expires_in" to 60, "interval" to 1,
            "verification_uri" to "https://www.twitch.tv/activate?public=true&device-code=ABCDEFGH"))
        override fun poll(clientId: String, deviceCode: String) = DeviceAuthResponse(200, mapOf(
            "access_token" to "fixture-token", "expires_in" to 60, "token_type" to "bearer"))
        override fun validate(accessToken: String): DeviceAuthResponse {
            validated = true
            onValidate()
            return DeviceAuthResponse(200, mapOf("client_id" to client, "user_id" to user, "expires_in" to 50, "scopes" to null))
        }
        override fun close() { closed = true }
    }

    @Test fun `optional exact-profile callback follows validation and receives conservative deadline`() = runBlocking {
        val transport = Transport()
        var now = 1000L
        var calls = 0
        val result = authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID, transport, {}, {},
            clockMs = { now }, waitMs = { now += it }, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV, onProviderClientValidated = { token, deadline ->
                assertTrue(transport.validated)
                assertEquals("fixture-token", token)
                assertEquals(52000L, deadline)
                calls++
            })
        assertEquals(DeviceAuthPhase.SUCCEEDED, result)
        assertEquals(1, calls)
        assertTrue(transport.closed)
    }

    @Test fun `other clients and failed validation never hand off a token`() = runBlocking {
        val callback: suspend (String, Long) -> Unit = { _, _ -> throw AssertionError("unvalidated handoff") }
        val other = Transport()
        assertEquals(DeviceAuthPhase.INVALID_CLIENT_ID, authorizeTwitchDevice("anotherpublicclient", other, {}, {}, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV, onProviderClientValidated = callback))
        assertTrue(other.closed)
        val mismatch = Transport(client = "anotherpublicclient")
        assertEquals(DeviceAuthPhase.CLIENT_MISMATCH, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID, mismatch, {}, {},
            waitMs = {}, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV, onProviderClientValidated = callback))
        val noUser = Transport(user = "")
        assertEquals(DeviceAuthPhase.INVALID_RESPONSE, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID, noUser, {}, {},
            waitMs = {}, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV, onProviderClientValidated = callback))
    }

    @Test fun `expired validated token never reaches access check`() = runBlocking {
        var now = 0L
        val transport = Transport().apply { onValidate = { now = 100000L } }
        assertEquals(DeviceAuthPhase.EXPIRED, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID, transport, {}, {},
            clockMs = { now }, waitMs = { now += it },
            providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV, onProviderClientValidated = { _, _ -> throw AssertionError("expired handoff") }))
        assertTrue(transport.closed)
    }

    @Test fun `cancellation in access check propagates and closes OAuth transport`() {
        val transport = Transport()
        assertThrows(CancellationException::class.java) {
            runBlocking { authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID, transport, {}, {}, waitMs = {},
                providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV, onProviderClientValidated = { _, _ -> throw CancellationException() }) }
        }
        assertTrue(transport.closed)
    }

    @Test fun `foreground loss during validation defers optional access without repolling`() = runBlocking {
        val foreground = DeviceAuthorizationForeground()
        val validated = CompletableDeferred<Unit>()
        var handedOff = false
        val transport = Transport().apply { onValidate = {
            foreground.setForeground(false)
            validated.complete(Unit)
        } }
        val worker = async {
            authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID, transport, {}, {}, waitMs = {},
                foreground = foreground, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV, onProviderClientValidated = { _, _ -> handedOff = true })
        }
        validated.await()
        assertFalse(handedOff)
        foreground.setForeground(true)
        assertEquals(DeviceAuthPhase.SUCCEEDED, worker.await())
        assertTrue(handedOff)
        assertTrue(transport.closed)
    }
}
