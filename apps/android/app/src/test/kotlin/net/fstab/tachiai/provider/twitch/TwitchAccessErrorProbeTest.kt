package net.fstab.tachiai.provider.twitch

import org.junit.Assert.*
import org.junit.Test

class TwitchAccessErrorProbeTest {
    @Test fun `status alone and access fields do not diagnose invalid token or prove playback`() {
        listOf(200, 400, 401, 403).forEach { status ->
            val result = classifyTwitchErrorFields(status, mapOf("signature" to "private", "value" to "private"))
            assertEquals(status, result.http)
            assertEquals(TwitchErrorShape.NO_ERROR, result.shape)
            assertEquals(setOf(TwitchErrorCategory.UNCLASSIFIED), result.categories)
            assertFalse(result.toString().contains("private"))
        }
    }

    @Test fun `only explicit known vocabulary yields reported categories`() {
        val cases = mapOf(
            " Invalid Client ID " to TwitchErrorCategory.CLIENT_REJECTION_REPORTED,
            "Invalid OAuth token" to TwitchErrorCategory.TOKEN_REJECTION_REPORTED,
            "The \"Authorization\" token is invalid." to TwitchErrorCategory.TOKEN_REJECTION_REPORTED,
            "The \"Client-ID\" header is invalid." to TwitchErrorCategory.CLIENT_REJECTION_REPORTED,
            "Unauthorized" to TwitchErrorCategory.AUTHENTICATION_REQUIRED_REPORTED,
            "FORBIDDEN" to TwitchErrorCategory.PERMISSION_REJECTION_REPORTED,
            "failed integrity check" to TwitchErrorCategory.INTEGRITY_REJECTION_REPORTED,
            "GRAPHQL_VALIDATION_FAILED" to TwitchErrorCategory.QUERY_REJECTION_REPORTED,
        )
        cases.forEach { (message, category) ->
            assertEquals(setOf(category), classifyTwitchErrorFields(401, mapOf("message" to message)).categories)
        }
    }

    @Test fun `unknown private messages remain unclassified and never appear in safe output`() {
        val private = "fixture-account-or-token invalid oauth token"
        val result = classifyTwitchErrorFields(401, mapOf("message" to private, "ignored" to private))
        assertEquals(TwitchErrorShape.ERROR_FIELDS, result.shape)
        assertEquals(setOf(TwitchErrorCategory.UNCLASSIFIED), result.categories)
        assertFalse(result.toString().contains(private))
        assertFalse(result.safeSummary().contains(private))
    }

    @Test fun `combined generic specific and unknown errors are not hidden`() {
        val result = classifyTwitchErrorFields(401, mapOf("error" to "Unauthorized",
            "message" to "Invalid OAuth token", "messages" to listOf("private error"),
            "codes" to listOf("INTEGRITY_CHECK_FAILED")))
        assertEquals(setOf(TwitchErrorCategory.AUTHENTICATION_REQUIRED_REPORTED,
            TwitchErrorCategory.TOKEN_REJECTION_REPORTED, TwitchErrorCategory.UNCLASSIFIED,
            TwitchErrorCategory.INTEGRITY_REJECTION_REPORTED), result.categories)
        assertFalse(result.safeSummary().contains("private error"))
    }

    @Test fun `malformed or excessive error fields fail closed`() {
        listOf(mapOf("message" to true), mapOf("error" to ""), mapOf("messages" to true),
            mapOf("messages" to listOf(null)), mapOf("codes" to listOf(1)),
            mapOf("messages" to List(17) { "Unauthorized" }), mapOf("message" to "x".repeat(1025))).forEach {
            val result = classifyTwitchErrorFields(401, it)
            assertEquals(TwitchErrorShape.INVALID_FIELDS, result.shape)
            assertEquals(setOf(TwitchErrorCategory.UNCLASSIFIED), result.categories)
        }
    }

    @Test fun `null and empty normalized fields are not errors`() {
        val result = classifyTwitchErrorFields(401, mapOf("message" to null, "error" to null,
            "messages" to emptyList<String>(), "codes" to emptyList<String>()))
        assertEquals(TwitchErrorShape.NO_ERROR, result.shape)
        assertEquals(setOf(TwitchErrorCategory.UNCLASSIFIED), result.categories)
    }

    @Test fun `access field presence requires an error free successful bounded pair`() {
        val success = classifyTwitchErrorFields(200, emptyMap())
        assertEquals(TwitchAccessFields.PRESENT, twitchAccessFieldPresence(success, "fixture-signature", "fixture-value"))
        listOf(null, true, "", "x".repeat(32769)).forEach { value ->
            assertEquals(TwitchAccessFields.ABSENT, twitchAccessFieldPresence(success, value, "fixture-value"))
            assertEquals(TwitchAccessFields.ABSENT, twitchAccessFieldPresence(success, "fixture-signature", value))
        }
        listOf(classifyTwitchErrorFields(401, emptyMap()),
            classifyTwitchErrorFields(200, mapOf("message" to "private error")),
            classifyTwitchErrorFields(200, mapOf("message" to true))).forEach { rejected ->
            assertEquals(TwitchAccessFields.ABSENT, twitchAccessFieldPresence(rejected, "fixture-signature", "fixture-value"))
        }
        val result = success.copy(accessFields = twitchAccessFieldPresence(success, "fixture-signature", "fixture-value"))
        assertTrue(result.safeSummary().contains("accessFields=PRESENT"))
        assertFalse(result.toString().contains("fixture-signature"))
        assertFalse(result.safeSummary().contains("fixture-value"))
        assertFalse(success.safeSummary().contains("accessFields="))
    }
}
