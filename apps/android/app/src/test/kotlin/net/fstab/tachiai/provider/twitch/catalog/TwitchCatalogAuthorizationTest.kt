package net.fstab.tachiai.provider.twitch.catalog

import net.fstab.tachiai.provider.twitch.DeviceAuthResponse
import net.fstab.tachiai.provider.twitch.SMART_TV_TWITCH_CLIENT_ID
import org.junit.Assert.*
import org.junit.Test

internal fun catalogTokenResponse(overrides: Map<String, Any?> = emptyMap()) = DeviceAuthResponse(200, mapOf(
    "access_token" to "fixture-access", "refresh_token" to "fixture-refresh%&/+", "expires_in" to 120,
    "scope" to listOf(TWITCH_CATALOG_SCOPE), "token_type" to "bearer",
) + overrides)

internal fun catalogValidationResponse(overrides: Map<String, Any?> = emptyMap()) = DeviceAuthResponse(200, mapOf(
    "client_id" to SMART_TV_TWITCH_CLIENT_ID, "user_id" to "123456", "login" to "fixture_user",
    "scopes" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to 100,
) + overrides)

class TwitchCatalogAuthorizationTest {
    private fun failure(expected: TwitchCatalogAuthFailure, action: () -> Unit) {
        val error = assertThrows(TwitchCatalogAuthException::class.java) { action() }
        assertEquals(expected, error.failure)
        assertFalse(error.toString().contains("private"))
        assertNull(error.cause)
    }

    @Test fun exactSmartTvGrantBindsAccountAndRefreshCredentialsWithoutExposingThem() {
        val grant = parseTwitchCatalogToken(catalogTokenResponse())
        val validated = parseTwitchCatalogValidation(catalogValidationResponse(), "123456")
        assertEquals("fixture-refresh%&/+", grant.refreshToken)
        assertEquals(120_000L, grant.expiresInMs)
        assertEquals(setOf(TWITCH_CATALOG_SCOPE), validated.scopes)
        assertEquals("123456", validated.userId)
        assertEquals("fixture_user", validated.login)
        val credentials = grant.validatedCredentials(validated)
        val result = TwitchCatalogAuthorizationResult.Approved(credentials, validated, 5000)
        listOf(grant, credentials, validated, result).forEach {
            assertTrue(it.toString().contains("redacted"))
            assertFalse(it.toString().contains("fixture"))
            assertFalse(it.toString().contains("123456"))
        }
    }

    @Test fun MissingMalformedOrBroaderScopesNeverBecomeCatalogAuthorization() {
        listOf(null, "user:read:follows", listOf(1)).forEach { value ->
            failure(TwitchCatalogAuthFailure.INVALID_RESPONSE) { parseTwitchCatalogToken(catalogTokenResponse(mapOf("scope" to value))) }
            failure(TwitchCatalogAuthFailure.INVALID_RESPONSE) { parseTwitchCatalogValidation(catalogValidationResponse(mapOf("scopes" to value))) }
        }
        listOf(emptyList(), listOf("user:read:email"), listOf(TWITCH_CATALOG_SCOPE, "chat:read"),
            listOf(TWITCH_CATALOG_SCOPE, TWITCH_CATALOG_SCOPE)).forEach { scopes ->
            failure(TwitchCatalogAuthFailure.SCOPE_MISMATCH) { parseTwitchCatalogToken(catalogTokenResponse(mapOf("scope" to scopes))) }
            failure(TwitchCatalogAuthFailure.SCOPE_MISMATCH) { parseTwitchCatalogValidation(catalogValidationResponse(mapOf("scopes" to scopes))) }
        }
        failure(TwitchCatalogAuthFailure.INVALID_RESPONSE) {
            parseTwitchCatalogToken(DeviceAuthResponse(200, catalogTokenResponse().fields - "scope"))
        }
        failure(TwitchCatalogAuthFailure.INVALID_RESPONSE) {
            parseTwitchCatalogValidation(DeviceAuthResponse(200, catalogValidationResponse().fields - "scopes"))
        }
    }

    @Test fun ValidationRejectsDifferentClientsUsersAndUnsafeIdentityShapes() {
        failure(TwitchCatalogAuthFailure.CLIENT_MISMATCH) {
            parseTwitchCatalogValidation(catalogValidationResponse(mapOf("client_id" to "another_client")))
        }
        failure(TwitchCatalogAuthFailure.USER_MISMATCH) { parseTwitchCatalogValidation(catalogValidationResponse(), "654321") }
        listOf(null, "", "private\nuser", "a".repeat(129), 123).forEach { user ->
            failure(TwitchCatalogAuthFailure.INVALID_RESPONSE) {
                parseTwitchCatalogValidation(catalogValidationResponse(mapOf("user_id" to user)))
            }
        }
        listOf("private\rlogin", "UPPERCASE", "a".repeat(65), 123).forEach { login ->
            failure(TwitchCatalogAuthFailure.INVALID_RESPONSE) {
                parseTwitchCatalogValidation(catalogValidationResponse(mapOf("login" to login)))
            }
        }
        assertNull(parseTwitchCatalogValidation(catalogValidationResponse(mapOf("login" to null))).login)
        assertNull(parseTwitchCatalogValidation(DeviceAuthResponse(200, catalogValidationResponse().fields - "login")).login)
    }

    @Test fun LifetimesAreIntegralPositiveAndBoundedRatherThanAnExperimentalZeroConvention() {
        listOf(null, -1, 1.5, "120", Int.MAX_VALUE.toLong() + 1).forEach { lifetime ->
            failure(TwitchCatalogAuthFailure.INVALID_RESPONSE) { parseTwitchCatalogToken(catalogTokenResponse(mapOf("expires_in" to lifetime))) }
            failure(TwitchCatalogAuthFailure.INVALID_RESPONSE) { parseTwitchCatalogValidation(catalogValidationResponse(mapOf("expires_in" to lifetime))) }
        }
        failure(TwitchCatalogAuthFailure.EXPIRED) { parseTwitchCatalogToken(catalogTokenResponse(mapOf("expires_in" to 0))) }
        failure(TwitchCatalogAuthFailure.EXPIRED) { parseTwitchCatalogValidation(catalogValidationResponse(mapOf("expires_in" to 0L))) }
        assertEquals(TWITCH_CATALOG_LIFETIME_LIMIT_MS,
            parseTwitchCatalogToken(catalogTokenResponse(mapOf("expires_in" to Int.MAX_VALUE.toLong()))).expiresInMs)
    }

    @Test fun omittedTokenLifetimeIsWorkerOnlyAndRequiresFinitePositiveValidationForStoredCredentials() {
        val grant = parseTwitchCatalogToken(DeviceAuthResponse(200, catalogTokenResponse().fields - "expires_in"))
        assertNull(grant.expiresInMs)
        val validation = parseTwitchCatalogValidation(catalogValidationResponse())
        val credentials = grant.validatedCredentials(validation)
        assertEquals(100_000L, credentials.expiresInMs)
        assertEquals(grant.accessToken, credentials.accessToken); assertEquals(grant.refreshToken, credentials.refreshToken)
        assertFalse(grant.toString().contains("fixture"))
        failure(TwitchCatalogAuthFailure.INVALID_RESPONSE) {
            parseTwitchCatalogValidation(DeviceAuthResponse(200, catalogValidationResponse().fields - "expires_in"))
        }
        assertThrows(IllegalArgumentException::class.java) { TwitchCatalogCredentials("access", "refresh", 0) }
        assertThrows(IllegalArgumentException::class.java) { TwitchCatalogValidation("123", setOf(TWITCH_CATALOG_SCOPE), 0) }
    }

    @Test fun TokenPairsRejectMissingOversizedAndControlValuesButRefreshEncodingCharactersRemainValid() {
        listOf(null, "", "private\r\nheader", "a".repeat(2049), "é").forEach { token ->
            for (key in listOf("access_token", "refresh_token")) failure(TwitchCatalogAuthFailure.INVALID_RESPONSE) {
                parseTwitchCatalogToken(catalogTokenResponse(mapOf(key to token)))
            }
        }
        failure(TwitchCatalogAuthFailure.INVALID_RESPONSE) { parseTwitchCatalogToken(catalogTokenResponse(mapOf("token_type" to "private"))) }
        failure(TwitchCatalogAuthFailure.INVALID_RESPONSE) { parseTwitchCatalogToken(catalogTokenResponse(mapOf("error" to "private provider text"))) }
        val grant = parseTwitchCatalogToken(catalogTokenResponse(mapOf("access_token" to "a".repeat(2048), "refresh_token" to "b".repeat(2048))))
        assertEquals(2048, grant.refreshToken.length)
    }

    @Test fun HttpRejectionsDoNotInterpretBodiesAsValidGrants() {
        for (status in listOf(302, 400, 401, 429, 503)) {
            failure(TwitchCatalogAuthFailure.REJECTED) { parseTwitchCatalogToken(DeviceAuthResponse(status, catalogTokenResponse().fields)) }
            failure(TwitchCatalogAuthFailure.REJECTED) { parseTwitchCatalogValidation(DeviceAuthResponse(status, catalogValidationResponse().fields)) }
        }
    }
}
