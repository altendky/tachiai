package net.fstab.tachiai.provider.abema

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AbemaDeviceTokenObservationTest {
    private fun status(http: String = "HTTP_400", schema: String = "FLAT_CODE_MESSAGE_REASON",
        code: String = "AUTHORIZATION_PENDING", reason: String = "ERROR_REASON_UNSPECIFIED") =
        JSONObject().put("state", AbemaDeviceTokenObservation.STATE).put("failure", "NONE")
            .put("http", http).put("schema", schema).put("code", code).put("reason", reason)

    @Test fun reviewedVocabularyIsAnObservationIndependentOfHTTPAndExportsOnlyClosedComponents() {
        for (http in listOf("HTTP_400", "HTTP_401", "HTTP_403", "HTTP_404", "HTTP_410", "HTTP_429", "HTTP_4XX", "HTTP_5XX")) {
            val category = checkNotNull(AbemaDeviceTokenObservation.category(status(http)))
            assertEquals("TOKEN_OBSERVATION_${http}_FLAT_CODE_MESSAGE_REASON_CODE_AUTHORIZATION_PENDING_REASON_ERROR_REASON_UNSPECIFIED", category)
        }
        assertNotNull(AbemaDeviceTokenObservation.category(status(code = "DEVICE_AUTHORIZATION_EXPIRED", reason = "ERROR_REASON_ACCOUNT_DELETED")))
        assertNotNull(AbemaDeviceTokenObservation.category(status(code = "TOKEN_REVOKED", reason = "ERROR_REASON_LOGGED_OUT")))
        assertNotNull(AbemaDeviceTokenObservation.category(status(code = "UNRECOGNIZED", reason = "UNRECOGNIZED")))
    }

    @Test fun exactFieldSetAndEnumTypesRejectPrivateTextExtraFieldsUnknownValuesAndWrongOperationState() {
        for (key in listOf("state", "failure", "http", "schema", "code", "reason")) {
            for (wrong in listOf<Any>("PRIVATE_PROVIDER_TEXT", 16, true, JSONObject.NULL, JSONObject()))
                assertNull(AbemaDeviceTokenObservation.category(status().put(key, wrong)))
            assertNull(AbemaDeviceTokenObservation.category(status().apply { remove(key) }))
        }
        for (key in listOf("message", "device_code", "access_token", "verification_uri", "extra"))
            assertNull(AbemaDeviceTokenObservation.category(status().put(key, "PRIVATE_VALUE")))
        assertNull(AbemaDeviceTokenObservation.category(status().put("state", "CHALLENGE_VALIDATED_AND_DISCARDED")))
    }

    @Test fun unexpectedSuccessAndUnreadStatusesCannotPretendToHaveReadCodeOrBody() {
        for (http in listOf("HTTP_2XX_UNEXPECTED", "HTTP_3XX", "HTTP_OTHER")) {
            assertNotNull(AbemaDeviceTokenObservation.category(status(http, "NOT_READ", "NOT_OBSERVED", "NOT_OBSERVED")))
            assertNull(AbemaDeviceTokenObservation.category(status(http)))
            assertNull(AbemaDeviceTokenObservation.category(status(http, "NOT_READ", "AUTHORIZATION_PENDING", "NOT_OBSERVED")))
            assertNull(AbemaDeviceTokenObservation.category(status(http, "NOT_READ", "NOT_OBSERVED", "ERROR_REASON_UNSPECIFIED")))
        }
        assertNull(AbemaDeviceTokenObservation.category(status("HTTP_400", "NOT_READ", "NOT_OBSERVED", "NOT_OBSERVED")))
    }

    @Test fun malformedUnknownAndBoundedRefusalsCannotExportEvenARecognizedCode() {
        for (schema in listOf("UNVERIFIED", "MALFORMED", "INVALID_UTF8", "OVERSIZED")) {
            assertNotNull(AbemaDeviceTokenObservation.category(status(schema = schema, code = "UNVERIFIED", reason = "UNVERIFIED")))
            assertNull(AbemaDeviceTokenObservation.category(status(schema = schema, code = "AUTHORIZATION_PENDING", reason = "UNVERIFIED")))
            assertNull(AbemaDeviceTokenObservation.category(status(schema = schema, code = "UNVERIFIED", reason = "ERROR_REASON_UNSPECIFIED")))
        }
    }

    @Test fun flatFieldPresenceMustAgreeWithAbsentCodeAndReason() {
        for ((schema, code, reason) in listOf(Triple("EMPTY", "ABSENT", "ABSENT"), Triple("FLAT_MESSAGE", "ABSENT", "ABSENT"),
            Triple("FLAT_CODE", "AUTHORIZATION_PENDING", "ABSENT"), Triple("FLAT_REASON", "ABSENT", "ERROR_REASON_LOGGED_OUT"),
            Triple("FLAT_CODE_MESSAGE", "UNRECOGNIZED", "ABSENT"), Triple("FLAT_MESSAGE_REASON", "ABSENT", "UNRECOGNIZED"),
            Triple("FLAT_CODE_REASON", "TOKEN_REVOKED", "ERROR_REASON_UNSPECIFIED"))) {
            assertNotNull(AbemaDeviceTokenObservation.category(status(schema = schema, code = code, reason = reason)))
            assertNull(AbemaDeviceTokenObservation.category(status(schema = schema,
                code = if (code == "ABSENT") "AUTHORIZATION_PENDING" else "ABSENT", reason = reason)))
            assertNull(AbemaDeviceTokenObservation.category(status(schema = schema,
                code = code, reason = if (reason == "ABSENT") "ERROR_REASON_LOGGED_OUT" else "ABSENT")))
        }
    }
}
