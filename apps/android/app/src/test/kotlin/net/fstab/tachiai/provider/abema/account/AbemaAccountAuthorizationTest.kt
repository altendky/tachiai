package net.fstab.tachiai.provider.abema.account

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

internal fun fixtureAccountToken(id: String = "intended-account", expiry: String = "2000") = "e30." +
    Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"$id\",\"exp\":$expiry}".toByteArray()) + ".c2ln"
internal fun fixtureAccountChallenge(deadline: Long = 300000) = AbemaAccountChallenge("guest", "guest-bearer", "guest", "private-code",
    "{\"client_id\":\"guest\",\"device_code\":\"private-code\",\"grant_type\":\"urn:ietf:params:oauth:grant-type:device_code\",\"device_type\":3}",
    "https://abema.tv/account/transfer?code=private-code", deadline)

class AbemaAccountAuthorizationTest {
    private fun grantBody(token: String = fixtureAccountToken(), id: String = "intended-account", type: String = "Bearer") =
        "{\"access_token\":\"$token\",\"token_type\":\"$type\",\"refresh_token\":\"discarded\",\"user_id\":\"$id\"}"
    @Test fun positiveExpiryResponseAndDecodedIdentityAgreeWithoutSignatureClaim() {
        val result = parseAbemaAccountToken(200, grantBody(), "intended-account", "guest", 1000000) as AbemaAccountTokenResult.Granted
        assertEquals("intended-account", result.grant.userId); assertEquals(2000000, result.grant.expiresAtMs)
        assertFalse(result.toString().contains(result.grant.bearer)); assertFalse(result.grant.toString().contains("intended"))
    }
    @Test fun missingZeroExpiredNonIntegralAndOverflowExpiryNeverAdoptsGrant() {
        for (expiry in listOf("0", "-1", "1000", "2000.0", "null", "\"2000\"", "9223372036854775807", "1e300"))
            assertTrue(parseAbemaAccountToken(200, grantBody(fixtureAccountToken(expiry = expiry)), "intended-account", "guest", 1000000) is AbemaAccountTokenResult.Failed)
        val missing = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"intended-account\"}".toByteArray()) + ".c2ln"
        assertTrue(parseAbemaAccountToken(200, grantBody(missing), "intended-account", "guest", 1000000) is AbemaAccountTokenResult.Failed)
    }
    @Test fun responseSubjectExpectedAccountAndNonGuestIdentityMustAllAgree() {
        for (body in listOf(grantBody(id = "other"), grantBody(fixtureAccountToken("other")), grantBody(type = "Basic"), grantBody(fixtureAccountToken("guest"), "guest")))
            assertTrue(parseAbemaAccountToken(200, body, "intended-account", "guest", 1000000) is AbemaAccountTokenResult.Failed)
        assertTrue(parseAbemaAccountToken(200, grantBody(fixtureAccountToken("guest"), "guest"), "guest", "guest", 1000000) is AbemaAccountTokenResult.Failed)
    }
    @Test fun onlyObservedStrictPendingSchemaAllowsAnotherPoll() {
        for (code in listOf("16", "\"AUTHORIZATION_PENDING\""))
            assertSame(AbemaAccountTokenResult.Pending, parseAbemaAccountToken(400, "{\"code\":$code,\"message\":\"discarded\"}", "intended-account", "guest", 1000000))
        for (body in listOf("{}", "{\"code\":16}", "{\"code\":\"16\",\"message\":\"x\"}",
            "{\"code\":16,\"message\":\"x\",\"reason\":0}", "{\"code\":16,\"message\":null}",
            "{\"code\":16,\"message\":\"x\",\"code\":16}", "{\"code\":16,\"message\":\"x\"}{}"))
            assertTrue(parseAbemaAccountToken(400, body, "intended-account", "guest", 1000000) is AbemaAccountTokenResult.Failed)
        assertTrue(parseAbemaAccountToken(401, "{\"code\":16,\"message\":\"x\"}", "intended-account", "guest", 1000000) is AbemaAccountTokenResult.Failed)
    }
    @Test fun fullJsonValidationRejectsIgnoredDuplicatesBadUnicodeAndNesting() {
        for (body in listOf("{\"profile\":{\"userId\":\"good\"},\"ignored\":{\"x\":1,\"x\":2}}",
            "{\"profile\":{\"userId\":\"good\"},\"ignored\":\"\\ud800\"}",
            "{\"profile\":{\"userId\":\"good\"}} trailing", "{profile:{userId:'good'}}",
            "{\"profile\":{\"userId\":\"good\"},\"ignored\":" + "[".repeat(18) + "0" + "]".repeat(18) + "}")) {
            try { parseAbemaAccountProfile(body); fail() } catch (_: AbemaAccountException) { }
        }
        assertEquals("good", parseAbemaAccountProfile("{\"profile\":{\"userId\":\"good\",\"name\":\"discard\"},\"other\":[]}"))
    }
    @Test fun challengeBodyHasExactTypedWireBindingAndDestinationIsCanonical() {
        val challenge = fixtureAccountChallenge()
        assertTrue(validAbemaTokenBody(challenge.tokenBody, "guest", "private-code"))
        for (body in listOf(challenge.tokenBody.replace("\"device_type\":3", "\"device_type\":\"3\""),
            challenge.tokenBody.replace("\"client_id\":\"guest\"", "\"client_id\":\"other\""), challenge.tokenBody + "{}"))
            assertFalse(validAbemaTokenBody(body, "guest", "private-code"))
        for (uri in listOf("http://abema.tv/a", "https://abema.tv:443/a", "https://ABEMA.tv/a", "https://abema.tv/a#x",
            "https://abema.tv/%2e%2e/a", "https://abema.tv/a?x=%ff", "https://abema.tv/a?x=%F3%A0%80%81", "https://u@abema.tv/a"))
            assertFalse(uri, validAbemaVerificationUri(uri))
        assertTrue(validAbemaVerificationUri(challenge.verificationUri))
    }
}
