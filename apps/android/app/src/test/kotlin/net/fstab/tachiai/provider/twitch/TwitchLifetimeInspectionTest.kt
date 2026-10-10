package net.fstab.tachiai.provider.twitch

import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TwitchLifetimeInspectionTest {
    private class Transport : TwitchDeviceTransport {
        var devices = 0
        var polls = 0
        var validations = 0
        var closed = false
        var onPoll: () -> Unit = {}
        var onValidate: () -> Unit = {}
        var grant: Map<String, Any?> = mapOf("access_token" to "inspection-fixture-session", "token_type" to "bearer")
        var validation: Map<String, Any?> = mapOf(
            "client_id" to SMART_TV_TWITCH_CLIENT_ID, "user_id" to "inspection-fixture-viewer",
            "expires_in" to 0, "scopes" to null,
        )

        override fun device(clientId: String): DeviceAuthResponse {
            assertEquals(SMART_TV_TWITCH_CLIENT_ID, clientId)
            devices++
            return DeviceAuthResponse(200, mapOf(
                "device_code" to "inspection-fixture-device", "user_code" to "INSPECT1",
                "expires_in" to 60, "interval" to 1,
                "verification_uri" to "https://www.twitch.tv/activate?public=true&device-code=INSPECT1",
            ))
        }

        override fun poll(clientId: String, deviceCode: String): DeviceAuthResponse {
            assertEquals(SMART_TV_TWITCH_CLIENT_ID, clientId)
            assertEquals("inspection-fixture-device", deviceCode)
            polls++
            onPoll()
            return DeviceAuthResponse(200, grant)
        }

        override fun validate(accessToken: String): DeviceAuthResponse {
            assertEquals("inspection-fixture-session", accessToken)
            validations++
            onValidate()
            return DeviceAuthResponse(200, validation)
        }

        override fun close() { closed = true }
    }

    @Test fun `lifetime shape preserves omission null signs bounds and strict integer types`() {
        assertEquals(DeviceLifetimeShape.OMITTED, deviceLifetimeShape(emptyMap()))
        assertEquals(DeviceLifetimeShape.OMITTED, deviceLifetimeShape(mapOf("expiresIn" to 0, "unknown" to 60)))
        val cases = listOf(
            null to DeviceLifetimeShape.NULL,
            0 to DeviceLifetimeShape.ZERO,
            0L to DeviceLifetimeShape.ZERO,
            1 to DeviceLifetimeShape.POSITIVE,
            1L to DeviceLifetimeShape.POSITIVE,
            Int.MAX_VALUE to DeviceLifetimeShape.POSITIVE,
            Int.MAX_VALUE.toLong() to DeviceLifetimeShape.POSITIVE,
            -1 to DeviceLifetimeShape.NEGATIVE,
            -1L to DeviceLifetimeShape.NEGATIVE,
            Long.MIN_VALUE to DeviceLifetimeShape.NEGATIVE,
            (Int.MAX_VALUE.toLong() + 1) to DeviceLifetimeShape.OUT_OF_RANGE,
            Long.MAX_VALUE to DeviceLifetimeShape.OUT_OF_RANGE,
            "0" to DeviceLifetimeShape.OTHER,
            0.0 to DeviceLifetimeShape.OTHER,
            Double.NaN to DeviceLifetimeShape.OTHER,
            true to DeviceLifetimeShape.OTHER,
            0.toShort() to DeviceLifetimeShape.OTHER,
            BigInteger.ZERO to DeviceLifetimeShape.OTHER,
            emptyList<Any?>() to DeviceLifetimeShape.OTHER,
        )
        cases.forEach { (value, expected) -> assertEquals(expected, deviceLifetimeShape(mapOf("expires_in" to value))) }
        assertEquals(30000L, SMART_TV_LIFETIME_INSPECTION_MS)
    }

    @Test fun `inspection accepts only omitted zero or ordinary positive grant with valid zero or positive validation`() = runBlocking {
        val grants = listOf(emptyMap<String, Any?>(), mapOf("expires_in" to 0), mapOf("expires_in" to 60L))
        for (grantLifetime in grants) {
            for (validationLifetime in listOf(0, 50)) {
                val transport = Transport().apply {
                    grant = grant + grantLifetime
                    validation = validation + ("expires_in" to validationLifetime)
                }
                val shapes = mutableListOf<Pair<DeviceAuthEndpoint, DeviceLifetimeShape>>()
                var now = 1000L
                assertEquals(DeviceAuthPhase.SUCCEEDED, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID,
                    transport, {}, {}, clockMs = { now }, waitMs = { now += it },
                    providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV, inspectSmartTvLifetime = true,
                    onLifetimeShape = { endpoint, shape -> shapes += endpoint to shape }))
                assertEquals(listOf(DeviceAuthEndpoint.TOKEN to deviceLifetimeShape(grantLifetime),
                    DeviceAuthEndpoint.VALIDATE to if (validationLifetime == 0) DeviceLifetimeShape.ZERO else DeviceLifetimeShape.POSITIVE), shapes)
                assertEquals(1, transport.devices)
                assertEquals(1, transport.polls)
                assertEquals(1, transport.validations)
                assertTrue(transport.closed)
            }
        }
    }

    @Test fun `strict default still rejects missing zero grant and zero validation`() = runBlocking {
        val missing = Transport()
        assertEquals(DeviceAuthPhase.INVALID_RESPONSE, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID,
            missing, {}, {}, waitMs = {}, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV))
        assertEquals(0, missing.validations)
        val zero = Transport().apply { grant = grant + ("expires_in" to 0) }
        assertEquals(DeviceAuthPhase.INVALID_RESPONSE, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID,
            zero, {}, {}, waitMs = {}, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV))
        assertEquals(0, zero.validations)
        val validationZero = Transport().apply { grant = grant + ("expires_in" to 60) }
        assertEquals(DeviceAuthPhase.INVALID_RESPONSE, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID,
            validationZero, {}, {}, waitMs = {}, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV))
        assertEquals(1, validationZero.validations)
        listOf(missing, zero, validationZero).forEach { assertTrue(it.closed) }
    }

    @Test fun `inspection refuses wrong clients profiles or a callback before IO`() = runBlocking {
        val forbidden: suspend (String, Long) -> Unit = { _, _ -> fail("inspection handed out a session") }
        val wrongClient = Transport()
        assertEquals(DeviceAuthPhase.INVALID_CLIENT_ID, authorizeTwitchDevice("anotherpublicclient",
            wrongClient, {}, {}, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV, inspectSmartTvLifetime = true))
        val webClient = Transport()
        assertEquals(DeviceAuthPhase.INVALID_CLIENT_ID, authorizeTwitchDevice(PROVIDER_TWITCH_CLIENT_ID,
            webClient, {}, {}, providerProfile = TwitchAuthorizationProfile.PROVIDER_PLAYBACK, inspectSmartTvLifetime = true))
        val defaultProfile = Transport()
        assertEquals(DeviceAuthPhase.INVALID_CLIENT_ID, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID,
            defaultProfile, {}, {}, inspectSmartTvLifetime = true))
        val providerCallback = Transport()
        assertEquals(DeviceAuthPhase.INVALID_CLIENT_ID, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID,
            providerCallback, {}, {}, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV,
            inspectSmartTvLifetime = true, onProviderClientValidated = forbidden))
        listOf(wrongClient, webClient, defaultProfile, providerCallback).forEach {
            assertEquals(0, it.devices)
            assertEquals(0, it.polls)
            assertEquals(0, it.validations)
            assertTrue(it.closed)
        }
    }

    @Test fun `inspection rejects null negative oversized and wrong-type grant lifetimes`() = runBlocking {
        listOf(null, -1, Long.MIN_VALUE, Int.MAX_VALUE.toLong() + 1, "0", 0.0, true).forEach { value ->
            val transport = Transport().apply { grant = grant + ("expires_in" to value) }
            assertEquals(DeviceAuthPhase.INVALID_RESPONSE, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID,
                transport, {}, {}, waitMs = {}, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV,
                inspectSmartTvLifetime = true))
            assertEquals(0, transport.validations)
            assertTrue(transport.closed)
        }
    }

    @Test fun `inspection never substitutes omitted null or malformed validation lifetime`() = runBlocking {
        val validationLifetimes = listOf(emptyMap<String, Any?>(), mapOf("expires_in" to null),
            mapOf("expires_in" to -1), mapOf("expires_in" to Long.MAX_VALUE),
            mapOf("expires_in" to "0"), mapOf("expires_in" to 0.0))
        validationLifetimes.forEach { fields ->
            val transport = Transport().apply { validation = (validation - "expires_in") + fields }
            assertEquals(DeviceAuthPhase.INVALID_RESPONSE, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID,
                transport, {}, {}, waitMs = {}, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV,
                inspectSmartTvLifetime = true))
            assertEquals(1, transport.validations)
            assertTrue(transport.closed)
        }
    }

    @Test fun `inspection retains exact identity user scope and grant-schema checks`() = runBlocking {
        val base = Transport().validation
        val cases = listOf(
            (base + ("client_id" to "anotherpublicclient")) to DeviceAuthPhase.CLIENT_MISMATCH,
            (base + ("client_id" to PROVIDER_TWITCH_CLIENT_ID)) to DeviceAuthPhase.CLIENT_MISMATCH,
            (base + ("user_id" to "")) to DeviceAuthPhase.INVALID_RESPONSE,
            (base + ("scopes" to listOf("chat:read"))) to DeviceAuthPhase.SCOPE_MISMATCH,
            (base - "scopes") to DeviceAuthPhase.INVALID_RESPONSE,
        )
        cases.forEach { (fields, expected) ->
            val transport = Transport().apply { validation = fields }
            assertEquals(expected, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID,
                transport, {}, {}, waitMs = {}, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV,
                inspectSmartTvLifetime = true))
            assertTrue(transport.closed)
        }
        val badGrants = listOf(mapOf("token_type" to "other"), mapOf("access_token" to "bad\nheader"),
            mapOf("scope" to listOf("chat:read")), mapOf("error" to "fixture-error"))
        badGrants.forEach { fields ->
            val transport = Transport().apply { grant = grant + fields }
            val result = authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID,
                transport, {}, {}, waitMs = {}, providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV,
                inspectSmartTvLifetime = true)
            assertEquals(if (fields.containsKey("scope")) DeviceAuthPhase.SCOPE_MISMATCH else DeviceAuthPhase.INVALID_RESPONSE, result)
            assertEquals(0, transport.validations)
            assertTrue(transport.closed)
        }
    }

    @Test fun `inspection budget starts before polling and caps known positive grants too`() = runBlocking {
        for (grantLifetime in listOf(emptyMap<String, Any?>(), mapOf("expires_in" to 0), mapOf("expires_in" to 60))) {
            var now = 1000L
            val transport = Transport().apply {
                grant = grant + grantLifetime
                onPoll = { now += SMART_TV_LIFETIME_INSPECTION_MS }
            }
            assertEquals(DeviceAuthPhase.EXPIRED, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID,
                transport, {}, {}, clockMs = { now }, waitMs = { now += it },
                providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV, inspectSmartTvLifetime = true))
            assertEquals(1, transport.polls)
            assertEquals(0, transport.validations)
            assertTrue(transport.closed)
        }
        var now = 1000L
        val shortGrant = Transport().apply {
            grant = grant + ("expires_in" to 1)
            onPoll = { now += 1000L }
        }
        assertEquals(DeviceAuthPhase.EXPIRED, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID,
            shortGrant, {}, {}, clockMs = { now }, waitMs = { now += it },
            providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV, inspectSmartTvLifetime = true))
        assertEquals(0, shortGrant.validations)
        assertTrue(shortGrant.closed)
    }

    @Test fun `validation reaching the inspection deadline cannot report success`() = runBlocking {
        var now = 1000L
        val transport = Transport().apply { onValidate = { now += SMART_TV_LIFETIME_INSPECTION_MS } }
        assertEquals(DeviceAuthPhase.EXPIRED, authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID,
            transport, {}, {}, clockMs = { now }, waitMs = { now += it },
            providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV, inspectSmartTvLifetime = true))
        assertEquals(1, transport.validations)
        assertTrue(transport.closed)
    }

    @Test fun `foreground return before the local deadline validates without requesting another grant`() = runBlocking {
        val foreground = DeviceAuthorizationForeground()
        val grantArrived = CompletableDeferred<Unit>()
        var now = 1000L
        val transport = Transport().apply { onPoll = {
            foreground.setForeground(false)
            grantArrived.complete(Unit)
        } }
        val worker = async {
            authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID, transport, {}, {},
                clockMs = { now }, waitMs = { now += it }, foreground = foreground,
                providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV, inspectSmartTvLifetime = true)
        }
        grantArrived.await()
        assertEquals(0, transport.validations)
        now = 31999L
        foreground.setForeground(true)
        assertEquals(DeviceAuthPhase.SUCCEEDED, worker.await())
        assertEquals(1, transport.devices)
        assertEquals(1, transport.polls)
        assertEquals(1, transport.validations)
        assertTrue(transport.closed)
    }

    @Test fun `foreground delay at the local deadline never validates or renews the grant`() = runBlocking {
        val foreground = DeviceAuthorizationForeground()
        val grantArrived = CompletableDeferred<Unit>()
        var now = 1000L
        val transport = Transport().apply { onPoll = {
            foreground.setForeground(false)
            grantArrived.complete(Unit)
        } }
        val worker = async {
            authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID, transport, {}, {},
                clockMs = { now }, waitMs = { now += it }, foreground = foreground,
                providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV, inspectSmartTvLifetime = true)
        }
        grantArrived.await()
        now = 32000L
        foreground.setForeground(true)
        assertEquals(DeviceAuthPhase.EXPIRED, worker.await())
        assertEquals(1, transport.devices)
        assertEquals(1, transport.polls)
        assertEquals(0, transport.validations)
        assertTrue(transport.closed)
    }

    @Test fun `cancellation while inspection waits for foreground closes and never validates`() = runBlocking {
        val foreground = DeviceAuthorizationForeground()
        val grantArrived = CompletableDeferred<Unit>()
        var now = 1000L
        val transport = Transport().apply { onPoll = {
            foreground.setForeground(false)
            grantArrived.complete(Unit)
        } }
        val worker = async {
            authorizeTwitchDevice(SMART_TV_TWITCH_CLIENT_ID, transport, {}, {},
                clockMs = { now }, waitMs = { now += it }, foreground = foreground,
                providerProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV, inspectSmartTvLifetime = true)
        }
        grantArrived.await()
        worker.cancel()
        try { worker.await(); fail("cancelled inspection returned success") } catch (_: CancellationException) { }
        assertEquals(1, transport.polls)
        assertEquals(0, transport.validations)
        assertTrue(transport.closed)
    }
}
