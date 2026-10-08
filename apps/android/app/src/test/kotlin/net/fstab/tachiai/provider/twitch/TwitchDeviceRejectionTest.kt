package net.fstab.tachiai.provider.twitch

import org.junit.Assert.*
import org.junit.Test

class TwitchDeviceRejectionTest {
    @Test fun `known error codes map only to closed provider-reported categories`() {
        val cases = mapOf(
            "invalid_client" to DeviceRejection.CLIENT_REJECTION_REPORTED,
            "invalid scopes" to DeviceRejection.SCOPE_REJECTION_REPORTED,
            "unauthorized_client" to DeviceRejection.DEVICE_FLOW_UNAVAILABLE_REPORTED,
            "invalid_request" to DeviceRejection.REQUEST_REJECTION_REPORTED,
            "missing client secret" to DeviceRejection.SECRET_REQUIRED_REPORTED,
        )
        cases.forEach { (value, expected) -> assertEquals(expected, classifyDeviceRejection(mapOf("message" to value))) }
        assertEquals(DeviceRejection.CLIENT_REJECTION_REPORTED, classifyDeviceRejection(mapOf("error" to "INVALID_CLIENT")))
    }

    @Test fun `unknown private messages and extra fields are never echoed`() {
        val result = classifyDeviceRejection(mapOf("message" to "private account reference", "access_token" to "synthetic-session"))
        assertEquals(DeviceRejection.UNCLASSIFIED, result)
        assertFalse(result.toString().contains("private"))
        assertEquals(DeviceRejection.UNCLASSIFIED, classifyDeviceRejection(emptyMap()))
        assertEquals(DeviceRejection.UNCLASSIFIED, classifyDeviceRejection(mapOf("message" to "invalid client id: private account reference")))
    }

    @Test fun `malformed or unbounded fields fail closed`() {
        listOf(true, listOf("invalid_client"), "x".repeat(1025), "").forEach {
            assertEquals(DeviceRejection.INVALID_FIELDS, classifyDeviceRejection(mapOf("error" to it)))
        }
    }
}
