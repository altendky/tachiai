package net.fstab.tachiai.provider.twitch.catalog

import kotlinx.coroutines.runBlocking
import net.fstab.tachiai.provider.twitch.*
import org.junit.Assert.*
import org.junit.Test

class TwitchCatalogAuthResponseShapeTest {
    @Test fun shapeContainsOnlyClosedCategoriesEvenForPrivateOrMalformedValues() {
        val fields = mapOf("access_token" to "private-access", "refresh_token" to "private-refresh",
            "user_id" to "private-account", "scope" to listOf("private-scope"), "expires_in" to 0)
        val shape = twitchCatalogAuthResponseShape(DeviceAuthEndpoint.TOKEN, fields)
        assertEquals(DeviceLifetimeShape.ZERO, shape.lifetime)
        assertEquals(TwitchCatalogScopeShape.OTHER, shape.scopes)
        assertEquals(TwitchCatalogRefreshShape.PRESENT, shape.refresh)
        assertFalse(shape.toString().contains("private"))
        val omitted = twitchCatalogAuthResponseShape(DeviceAuthEndpoint.TOKEN, emptyMap())
        assertEquals(DeviceLifetimeShape.OMITTED, omitted.lifetime)
        assertEquals(TwitchCatalogScopeShape.INVALID, omitted.scopes)
        assertEquals(TwitchCatalogRefreshShape.OMITTED, omitted.refresh)
        val malformed = twitchCatalogAuthResponseShape(DeviceAuthEndpoint.TOKEN,
            mapOf("refresh_token" to "private\nrefresh", "scope" to listOf(123)))
        assertEquals(TwitchCatalogScopeShape.INVALID, malformed.scopes)
        assertEquals(TwitchCatalogRefreshShape.INVALID, malformed.refresh)
        val validation = twitchCatalogAuthResponseShape(DeviceAuthEndpoint.VALIDATE,
            mapOf("scopes" to emptyList<String>(), "expires_in" to 60))
        assertEquals(TwitchCatalogScopeShape.EMPTY, validation.scopes)
        assertEquals(DeviceLifetimeShape.POSITIVE, validation.lifetime)
        assertEquals(TwitchCatalogRefreshShape.NOT_APPLICABLE, validation.refresh)
    }

    @Test fun rejectedGrantReportsShapeWithoutBroadeningTheExistingLifetimePolicy() = runBlocking {
        var closed = false
        var now = 1000L
        val transport = object : TwitchCatalogTransport {
            override fun device() = DeviceAuthResponse(200, mapOf("device_code" to "fixture-code", "user_code" to "ABCD1234",
                "verification_uri" to "https://www.twitch.tv/activate", "expires_in" to 60, "interval" to 5))
            override fun poll(deviceCode: String) = DeviceAuthResponse(200, mapOf("access_token" to "private-access",
                "refresh_token" to "private-refresh", "token_type" to "bearer", "scope" to listOf(TWITCH_CATALOG_SCOPE),
                "expires_in" to 0))
            override fun validate(accessToken: String): DeviceAuthResponse = error("Expired grant must not validate")
            override fun refresh(refreshToken: String): DeviceAuthResponse = error("Device flow never refreshes")
            override fun close() { closed = true }
        }
        val shapes = mutableListOf<TwitchCatalogAuthResponseShape>()
        val result = requestTwitchCatalogAuthorization(transport, clockMs = { now }, waitMs = { now += it },
            onResponseShape = { shapes += it }) as TwitchCatalogAuthorizationResult.Failed
        assertEquals(TwitchCatalogAuthFailure.EXPIRED, result.failure)
        assertEquals(DeviceAuthPhase.EXPIRED, result.phase)
        assertEquals(listOf(DeviceLifetimeShape.ZERO), shapes.map { it.lifetime })
        assertFalse(shapes.toString().contains("private"))
        assertTrue(closed)
    }
}
