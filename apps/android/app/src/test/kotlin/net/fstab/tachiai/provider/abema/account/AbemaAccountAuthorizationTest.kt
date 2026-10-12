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
    @Test fun emailCandidateRequiresExactTrustedGetAndOneMatchingBearerWithFutureExpiry() {
        val url = "https://api.p-c3-e.abema-tv.com/v1/users/intended-account/email"
        val token = fixtureAccountToken()
        for (name in listOf("Authorization", "authorization", "AUTHORIZATION")) {
            val grant = parseAbemaEmailCandidate(url, "GET", mapOf(name to "bEaReR $token"), 1000000)!!
            assertEquals("intended-account", grant.userId); assertEquals(token, grant.bearer)
            assertEquals(2000000, grant.expiresAtMs)
        }
        for (invalid in listOf(url.replace("https:", "http:"), url.replace("api.p-c3-e.abema-tv.com", "abema.tv"),
            url.replace(".com/", ".com:443/"), url.replace("https://", "https://user@"),
            url.replace("intended-account", "%69ntended-account"), url.replace("/email", "/email/"),
            url + "?query", url + "#fragment", url.replace(".com", ".com.evil.invalid"), url.replace("api.", "API.")))
            assertNull(parseAbemaEmailCandidate(invalid, "GET", mapOf("Authorization" to "Bearer $token"), 1000000))
        for (method in listOf("POST", "get", "HEAD"))
            assertNull(parseAbemaEmailCandidate(url, method, mapOf("Authorization" to "Bearer $token"), 1000000))
        for (headers in listOf(emptyMap(), mapOf("Authorization" to "Basic $token"),
            mapOf("Authorization" to "Bearer $token", "authorization" to "Bearer $token"),
            mapOf("Authorization" to "Bearer  $token"), mapOf("Authorization" to "Bearer $token\r\n"),
            mapOf("Authorization" to "Bearer ${fixtureAccountToken("other")}"),
            mapOf("Authorization" to "Bearer ${fixtureAccountToken(expiry = "1000")}"),
            mapOf("Authorization" to "Bearer ${fixtureAccountToken(expiry = "9223372036854775807")}")))
            assertNull(parseAbemaEmailCandidate(url, "GET", headers, 1000000))
        assertNull(parseAbemaEmailCandidate(url, "GET", mapOf("Authorization" to "Bearer $token"), -1))
    }
    @Test fun emailRegistrationProjectsOnlyBoundedStatusWithoutRequiringALegacyPassword() {
        for (body in listOf("""{"email":"fixture@example.test","hasPassword":false}""",
            """{"email":"fixture@example.test","hasPassword":true}""", """{"email":"fixture@example.test"}"""))
            assertEquals(AbemaAccountEmailResult.REGISTERED, parseAbemaAccountEmailRegistration(body))
        for (body in listOf("""{"email":null,"hasPassword":false}""", """{"email":""}"""))
            assertEquals(AbemaAccountEmailResult.UNREGISTERED, parseAbemaAccountEmailRegistration(body))
        for (body in listOf("{}", """{"email":1}""", """{"email":"fixture@example.test","hasPassword":null}""",
            """{"email":"fixture@example.test","hasPassword":"false"}""", """{"email":" fixture@example.test"}""",
            """{"email":"fixture\u200e@example.test"}""", """{"email":"fixture@example.test","other":1}""",
            """{"email":"fixture@example.test","email":"other@example.test"}""",
            """{"email":"fixture\ud800@example.test"}""", """{"email":"fixture@example.test"}{}""",
            "{\"email\":\"${"x".repeat(321)}\"}", " ".repeat(65537)))
            assertEquals(AbemaAccountEmailResult.INCONCLUSIVE, parseAbemaAccountEmailRegistration(body))
    }
    @Test fun internalProviderIdentitiesRemainExactAndSeparateFromEmailSignIn() {
        for (id in listOf("A", "fixture-account_123", "a".repeat(256))) {
            assertTrue(accountId(id)); assertFalse(accountId(" $id "))
        }
        for (invalid in listOf("", " \t\r\n", "invalid account", "fixture/account", "fixture@example.test",
            "fixture.account", "fixture\u200e", "ｆｉｘｔｕｒｅ", "a".repeat(257)))
            assertFalse(accountId(invalid))
        assertThrows(AbemaAccountException::class.java) { parseAbemaAccountProfile("""{"profile":{"userId":" fixture-account "}}""") }
    }
    private fun grantBody(token: String = fixtureAccountToken(), id: String = "intended-account", type: String = "Bearer") =
        "{\"access_token\":\"$token\",\"token_type\":\"$type\",\"refresh_token\":\"discarded\",\"user_id\":\"$id\"}"
    @Test fun positiveExpiryResponseAndDecodedIdentityAgreeWithoutSignatureClaim() {
        val result = parseAbemaAccountToken(200, grantBody(), "guest", 1000000) as AbemaAccountTokenResult.Granted
        assertEquals("intended-account", result.grant.userId); assertEquals(2000000, result.grant.expiresAtMs)
        assertFalse(result.toString().contains(result.grant.bearer)); assertFalse(result.grant.toString().contains("intended"))
    }
    @Test fun missingZeroExpiredNonIntegralAndOverflowExpiryNeverAdoptsGrant() {
        for (expiry in listOf("0", "-1", "1000", "2000.0", "null", "\"2000\"", "9223372036854775807", "1e300"))
            assertTrue(parseAbemaAccountToken(200, grantBody(fixtureAccountToken(expiry = expiry)), "guest", 1000000) is AbemaAccountTokenResult.Failed)
        val missing = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"intended-account\"}".toByteArray()) + ".c2ln"
        assertTrue(parseAbemaAccountToken(200, grantBody(missing), "guest", 1000000) is AbemaAccountTokenResult.Failed)
    }
    @Test fun responseSubjectAndNonGuestIdentityMustAgree() {
        for (body in listOf(grantBody(id = "other"), grantBody(fixtureAccountToken("other")), grantBody(type = "Basic"), grantBody(fixtureAccountToken("guest"), "guest")))
            assertTrue(parseAbemaAccountToken(200, body, "guest", 1000000) is AbemaAccountTokenResult.Failed)
        for (invalidId in listOf("", "fixture@example.test", " account ", "a".repeat(257)))
            assertTrue(parseAbemaAccountToken(200, grantBody(fixtureAccountToken(invalidId), invalidId), "guest", 1000000) is AbemaAccountTokenResult.Failed)
    }
    @Test fun approvedResponseDefinesTheAccountWithoutManualIdentityInput() {
        for (id in listOf("first-approved-account", "second-approved-account")) {
            val result = parseAbemaAccountToken(200, grantBody(fixtureAccountToken(id), id), "guest", 1000000) as AbemaAccountTokenResult.Granted
            assertEquals(id, result.grant.userId)
        }
    }
    @Test fun onlyObservedStrictPendingSchemaAllowsAnotherPoll() {
        for (code in listOf("16", "\"AUTHORIZATION_PENDING\""))
            assertSame(AbemaAccountTokenResult.Pending, parseAbemaAccountToken(400, "{\"code\":$code,\"message\":\"discarded\"}", "guest", 1000000))
        for (body in listOf("{}", "{\"code\":16}", "{\"code\":\"16\",\"message\":\"x\"}",
            "{\"code\":16,\"message\":\"x\",\"reason\":0}", "{\"code\":16,\"message\":null}",
            "{\"code\":16,\"message\":\"x\",\"code\":16}", "{\"code\":16,\"message\":\"x\"}{}"))
            assertTrue(parseAbemaAccountToken(400, body, "guest", 1000000) is AbemaAccountTokenResult.Failed)
        assertTrue(parseAbemaAccountToken(401, "{\"code\":16,\"message\":\"x\"}", "guest", 1000000) is AbemaAccountTokenResult.Failed)
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
