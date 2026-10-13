package net.fstab.tachiai.provider.abema.account

import org.junit.Assert.*
import org.junit.Test

class AbemaSubscriptionEntitlementTest {
    private val now = 1_000_000L
    private fun body(subscriptions: String? = null, extra: String = "", id: String = "fixture") =
        """{"profile":{"userId":"$id"}$extra""" + (subscriptions?.let { ",\"userSubscriptions\":$it" } ?: "") + "}"
    private fun parse(value: String, expected: String = "fixture", wall: Long = now) = parseAbemaSubscriptionProfile(value, expected, wall)
    private fun identity(value: String, state: AbemaSubscriptionFieldState, expiry: Long? = null): AbemaSubscriptionProfileResult.Identity {
        val result = parse(value)
        assertTrue(result is AbemaSubscriptionProfileResult.Identity)
        result as AbemaSubscriptionProfileResult.Identity
        assertEquals("fixture", result.id); assertEquals(state, result.fieldState); assertEquals(expiry, result.premiumExpiresAtMs)
        return result
    }
    private fun refused(value: String) = assertSame(AbemaSubscriptionProfileResult.Inconclusive, parse(value))
    @Test fun absentEmptyAndValidNoAuthorityRemainDistinctMatchingIdentityResults() {
        identity(body(), AbemaSubscriptionFieldState.ABSENT)
        identity(body("[]"), AbemaSubscriptionFieldState.EMPTY)
        for (row in listOf("""{"expire":2000}""", """{"expire":2000,"viewingAuthorities":[]}""",
            """{"expire":2000,"viewingAuthorities":[{}]}""", """{"expire":2000,"viewingAuthorities":[{"isPremium":false}]}"""))
            identity(body("[$row]"), AbemaSubscriptionFieldState.NO_AFFIRMATIVE_AUTHORITY)
    }
    @Test fun onlyExplicitRootModernPremiumAuthorityGrantsMaximumActiveExpiry() {
        identity(body("""[{"expire":2000,"viewingAuthorities":[{"isPremium":true}]},{"expire":3000,"viewingAuthorities":[{}, {"isPremium":true}]},{"expire":4000,"viewingAuthorities":[{"isPremium":false}],"isTrial":true},{"expire":500,"viewingAuthorities":[{"isPremium":true}]}]"""),
            AbemaSubscriptionFieldState.PREMIUM, 3_000_000L)
        for (expiry in listOf(0, 500, 1000)) identity(body("""[{"expire":$expiry,"viewingAuthorities":[{"isPremium":true}]}]"""),
            AbemaSubscriptionFieldState.NO_AFFIRMATIVE_AUTHORITY)
        identity(body("""[{"expire":1001,"viewingAuthorities":[{"isPremium":true}]}]"""), AbemaSubscriptionFieldState.PREMIUM, 1_001_000L)
    }
    @Test fun nestedLegacyPartnerTrialAndFeatureAuthorityNeverSupplyModernPremium() {
        val nested = """{"profile":{"userId":"fixture","userSubscriptions":[{"expire":2000,"viewingAuthorities":[{"isPremium":true}]}]},"subscriptions":[{"expire":2000,"isPremium":true,"isTrial":true}],"partnerServiceSubscriptions":[{"expire":2000,"isPremium":true}]}"""
        identity(nested, AbemaSubscriptionFieldState.ABSENT)
        identity(body("""[{"expire":2000,"isTrial":true,"planId":"premium","featureAuthorities":[{"type":2,"isPremium":true}]}]"""),
            AbemaSubscriptionFieldState.NO_AFFIRMATIVE_AUTHORITY)
    }
    @Test fun malformedPresentModernRowsAndAuthorityTypesAreInconclusiveInsteadOfFreeIdentity() {
        for (value in listOf("null", "{}", "true", "\"array\"", "[null]", "[1]", "[[]]", "[{}]",
            """[{"expire":2000,"viewingAuthorities":null}]""", """[{"expire":2000,"viewingAuthorities":{}}]""",
            """[{"expire":2000,"viewingAuthorities":[null]}]""", """[{"expire":2000,"viewingAuthorities":[true]}]""")) refused(body(value))
        for (premium in listOf("null", "1", "\"true\"", "[]", "{}"))
            refused(body("""[{"expire":2000,"viewingAuthorities":[{"isPremium":$premium}]}]"""))
        refused(body("""[{"expire":2000,"viewingAuthorities":[{"isPremium":true}]},{"expire":0,"viewingAuthorities":[{"isPremium":"false"}]}]"""))
    }
    @Test fun expiryMustBeExplicitNonnegativeIntegerSecondsWithCheckedSafeMilliseconds() {
        for (expiry in listOf("null", "true", "\"2000\"", "2000.0", "2000.5", "-1", "9223372036854775807", "9007199254741"))
            refused(body("""[{"expire":$expiry,"viewingAuthorities":[{"isPremium":true}]}]"""))
        identity(body("""[{"expire":9007199254740,"viewingAuthorities":[{"isPremium":true}]}]"""),
            AbemaSubscriptionFieldState.PREMIUM, 9_007_199_254_740_000L)
    }
    @Test fun wholeJsonIncludingIgnoredFieldsRejectsDuplicatesUnicodeAndRelaxedSyntax() {
        for (value in listOf(body("[]", ",\"ignored\":{\"x\":1,\"x\":2}"),
            body("[]", ",\"ignored\":\"\\ud800\""), body("[]", ",\"ignored\":1e999"),
            """{"profile":{"userId":"fixture","userId":"fixture"}}""",
            """{"profile":{"userId":"fixture"},"userSubscriptions":[],"userSubscriptions":[]}""",
            body("""[{"expire":2000,"expire":3000}]"""), body("""[{"expire":2000,"viewingAuthorities":[{"isPremium":false,"isPremium":true}]}]"""),
            body() + " true", body().dropLast(1) + ",}", "/*comment*/" + body())) refused(value)
    }
    @Test fun arrayAndByteBoundsApplyBeforeProjectionWithoutInventingSmallPlanCaps() {
        val thousand = List(1000) { "{\"expire\":0}" }.joinToString(",")
        identity(body("[$thousand]"), AbemaSubscriptionFieldState.NO_AFFIRMATIVE_AUTHORITY)
        refused(body("[$thousand,{\"expire\":0}]"))
        val authorities = List(1000) { "{\"isPremium\":false}" }.joinToString(",")
        identity(body("""[{"expire":2000,"viewingAuthorities":[$authorities]}]"""), AbemaSubscriptionFieldState.NO_AFFIRMATIVE_AUTHORITY)
        refused(body("""[{"expire":2000,"viewingAuthorities":[$authorities,{}]}]"""))
        val exact = body().padEnd(65536, ' ')
        identity(exact, AbemaSubscriptionFieldState.ABSENT); refused(exact + " ")
        refused(body(extra = ",\"ignored\":\"${"雪".repeat(22000)}\""))
    }
    @Test fun profileIdentityAndReadWallClockMustMatchBeforeAnyCapability() {
        assertSame(AbemaSubscriptionProfileResult.Inconclusive, parse(body(), expected = "other"))
        for (id in listOf("", "bad/id", "x".repeat(257))) {
            refused(body(id = id)); assertSame(AbemaSubscriptionProfileResult.Inconclusive, parse(body(), expected = id))
        }
        for (value in listOf("{}", """{"profile":null}""", """{"profile":{"userId":1}}""", """{"userId":"fixture"}""")) refused(value)
        for (wall in listOf(-1L, 9_007_199_254_740_992L)) assertSame(AbemaSubscriptionProfileResult.Inconclusive, parse(body(), wall = wall))
    }
    @Test fun identityResultDoesNotExposeAccountOrAuthorityExpiryInText() {
        val result = identity(body("""[{"expire":2000,"viewingAuthorities":[{"isPremium":true}]}]"""), AbemaSubscriptionFieldState.PREMIUM, 2_000_000L)
        assertFalse(result.toString().contains("fixture")); assertFalse(result.toString().contains("2000"))
        assertThrows(IllegalArgumentException::class.java) { AbemaSubscriptionProfileResult.Identity("fixture", AbemaSubscriptionFieldState.PREMIUM, null) }
        assertThrows(IllegalArgumentException::class.java) { AbemaSubscriptionProfileResult.Identity("fixture", AbemaSubscriptionFieldState.ABSENT, 1) }
    }
    @Test fun chaseMissingEmptyAndValidNonAuthorityFieldsStayDistinctFromPremium() {
        assertEquals(AbemaChaseFieldState.ABSENT, identity(body(), AbemaSubscriptionFieldState.ABSENT).chase.state)
        assertEquals(AbemaChaseFieldState.EMPTY, identity(body("[]"), AbemaSubscriptionFieldState.EMPTY).chase.state)
        assertEquals(AbemaChaseFieldState.ABSENT, identity(body("""[{"expire":2000}]"""), AbemaSubscriptionFieldState.NO_AFFIRMATIVE_AUTHORITY).chase.state)
        assertEquals(AbemaChaseFieldState.EMPTY, identity(body("""[{"expire":2000,"featureAuthorities":[]}]"""), AbemaSubscriptionFieldState.NO_AFFIRMATIVE_AUTHORITY).chase.state)
        for (expiry in listOf(0, 500, 1000)) {
            val result = identity(body("""[{"expire":$expiry,"featureAuthorities":[{"id":"authority","type":2}]}]"""), AbemaSubscriptionFieldState.NO_AFFIRMATIVE_AUTHORITY)
            assertEquals(AbemaChaseFieldState.NO_AFFIRMATIVE_AUTHORITY, result.chase.state); assertTrue(result.chase.claims.isEmpty())
        }
    }
    @Test fun chaseAcceptsOnlyExactActiveChaseTypesAndKeepsEachClaimExpiry() {
        val result = identity(body("""[{"expire":2000,"featureAuthorities":[{"id":"first","type":2}]},{"expire":3000,"featureAuthorities":[{"id":"second","type":"FEATURE_AUTHORITY_TYPE_CHASE_PLAY"},{"id":"first","type":2}]},{"expire":500,"featureAuthorities":[{"id":"expired","type":2}]}]"""), AbemaSubscriptionFieldState.NO_AFFIRMATIVE_AUTHORITY)
        assertEquals(AbemaChaseFieldState.VERIFIED, result.chase.state)
        assertEquals(listOf("first", "second", "first"), result.chase.claims.map { it.id })
        assertEquals(listOf(2_000_000L, 3_000_000L, 3_000_000L), result.chase.claims.map { it.expiresAtMs })
        assertNull(result.premiumExpiresAtMs)
        val names = listOf("UNSPECIFIED", "SIMULTANEOUS_VIEWING", "CHASE_PLAY", "NO_ADS", "DOWNLOAD", "OPENING_SKIP", "LIMITED_PPV")
        for (type in listOf(0, 1, 3, 4, 5, 6)) for (raw in listOf(type.toString(), "\"FEATURE_AUTHORITY_TYPE_${names[type]}\"")) {
            val other = identity(body("""[{"expire":2000,"featureAuthorities":[{"id":"other","type":$raw}]}]"""), AbemaSubscriptionFieldState.NO_AFFIRMATIVE_AUTHORITY)
            assertEquals(AbemaChaseFieldState.NO_AFFIRMATIVE_AUTHORITY, other.chase.state); assertTrue(other.chase.claims.isEmpty())
        }
    }
    @Test fun malformedChaseFieldsIndependentlyDiscardAllClaimsWithoutChangingPremium() {
        val malformed = listOf("null", "{}", "1", "[null]", "[{}]", "[[]]", "[true]",
            """[{"id":1,"type":2}]""", """[{"id":"","type":2}]""", """[{"id":null,"type":2}]""",
            """[{"type":2}]""", """[{"id":"id"}]""", """[{"id":"${"x".repeat(257)}","type":2}]""")
        for (features in malformed) {
            val result = identity(body("""[{"expire":2000,"viewingAuthorities":[{"isPremium":true}],"featureAuthorities":[{"id":"first","type":2}]},{"expire":0,"featureAuthorities":$features}]"""), AbemaSubscriptionFieldState.PREMIUM, 2_000_000L)
            assertEquals(AbemaChaseFieldState.UNVERIFIED, result.chase.state); assertTrue(result.chase.claims.isEmpty())
        }
        for (type in listOf("null", "true", "2.0", "\"2\"", "-1", "7", "\"FEATURE_AUTHORITY_TYPE_chase_PLAY\"", "\"UNRECOGNIZED\"")) {
            val result = identity(body("""[{"expire":2000,"viewingAuthorities":[{"isPremium":true}],"featureAuthorities":[{"id":"id","type":$type}]}]"""), AbemaSubscriptionFieldState.PREMIUM, 2_000_000L)
            assertEquals(AbemaChaseFieldState.UNVERIFIED, result.chase.state); assertTrue(result.chase.claims.isEmpty())
        }
    }
    @Test fun chaseIdsAreRawBoundedStringsWithoutTrimmingCoercionOrInventedGrammar() {
        val result = identity(body("""[{"expire":2000,"featureAuthorities":[{"id":" \n\t\u2603 / ","type":2},{"id":"${"x".repeat(256)}","type":2}]}]"""), AbemaSubscriptionFieldState.NO_AFFIRMATIVE_AUTHORITY)
        assertEquals(AbemaChaseFieldState.VERIFIED, result.chase.state)
        assertEquals(" \n\t☃ / ", result.chase.claims.first().id)
        assertEquals(256, result.chase.claims.last().id.length)
    }
    @Test fun chaseCannotBypassGlobalSubscriptionExpiryIdentityOrStrictJsonRefusal() {
        for (expire in listOf("null", "-1", "2000.0", "\"2000\"", "9223372036854775807", "9007199254741"))
            refused(body("""[{"expire":$expire,"featureAuthorities":[{"id":"id","type":2}]}]"""))
        refused(body("""[{"featureAuthorities":[{"id":"id","type":2}]}]"""))
        refused(body("""[{"expire":2000,"featureAuthorities":[{"id":"a","id":"b","type":2}]}]"""))
        refused(body("""[{"expire":2000,"featureAuthorities":[{"id":"a","type":1,"type":2}]}]"""))
        assertSame(AbemaSubscriptionProfileResult.Inconclusive, parse(body("""[{"expire":2000,"featureAuthorities":[{"id":"id","type":2}]}]"""), expected = "other"))
        val nested = identity("""{"profile":{"userId":"fixture","userSubscriptions":[{"expire":2000,"featureAuthorities":[{"id":"id","type":2}]}]},"partnerServiceSubscriptions":[{"expire":2000,"featureAuthorities":[{"id":"id","type":2}]}]}""", AbemaSubscriptionFieldState.ABSENT)
        assertEquals(AbemaChaseFieldState.ABSENT, nested.chase.state); assertTrue(nested.chase.claims.isEmpty())
    }
    @Test fun chaseClaimsAreImmutableAndAllTextFormsRemainRedacted() {
        val claim = AbemaChaseClaim("private-authority", 2_000_000)
        val source = mutableListOf(claim)
        val projection = AbemaChaseProjection(AbemaChaseFieldState.VERIFIED, source)
        source.clear(); assertEquals(1, projection.claims.size)
        assertThrows(UnsupportedOperationException::class.java) { (projection.claims as MutableList<AbemaChaseClaim>).clear() }
        for (text in listOf(claim.toString(), projection.toString(), projection.claims.toString())) {
            assertFalse(text.contains("private-authority")); assertFalse(text.contains("2000000"))
        }
        assertThrows(IllegalArgumentException::class.java) { AbemaChaseProjection(AbemaChaseFieldState.UNVERIFIED, listOf(claim)) }
        assertThrows(IllegalArgumentException::class.java) { AbemaChaseProjection(AbemaChaseFieldState.VERIFIED, emptyList()) }
    }
    @Test fun chaseArrayAndAggregateDistinctIdBoundsDoNotBlockOrdinaryPremium() {
        fun features(start: Int, size: Int) = List(size) { "{\"id\":\"authority${start + it}\",\"type\":2}" }.joinToString(",")
        val first = features(0, 1000)
        val exact = identity(body("""[{"expire":2000,"featureAuthorities":[$first]}]"""), AbemaSubscriptionFieldState.NO_AFFIRMATIVE_AUTHORITY)
        assertEquals(AbemaChaseFieldState.VERIFIED, exact.chase.state); assertEquals(1000, exact.chase.claims.size)
        refused(body("""[{"expire":2000,"featureAuthorities":[$first,{"id":"overflow","type":2}]}]"""))
        val aggregate = identity(body("""[{"expire":2000,"viewingAuthorities":[{"isPremium":true}],"featureAuthorities":[${features(0, 600)}]},{"expire":3000,"featureAuthorities":[${features(600, 401)}]}]"""), AbemaSubscriptionFieldState.PREMIUM, 2_000_000L)
        assertEquals(AbemaChaseFieldState.UNVERIFIED, aggregate.chase.state); assertTrue(aggregate.chase.claims.isEmpty())
    }
}
