package net.fstab.tachiai.provider.twitch

import java.util.Locale

internal enum class DeviceRejection {
    UNCLASSIFIED, INVALID_FIELDS, CLIENT_REJECTION_REPORTED, SCOPE_REJECTION_REPORTED,
    DEVICE_FLOW_UNAVAILABLE_REPORTED, REQUEST_REJECTION_REPORTED, SECRET_REQUIRED_REPORTED,
}

// Exact known error vocabulary only. Provider-reported categories are not
// independently verified causes. Unknown text, IDs and extra fields never escape.
internal fun classifyDeviceRejection(fields: Map<String, Any?>): DeviceRejection {
    val values = listOf("error", "message").mapNotNull { key ->
        val value = fields[key] ?: return@mapNotNull null
        if (value !is String || value.length !in 1..1024) return DeviceRejection.INVALID_FIELDS
        value.trim().lowercase(Locale.ROOT)
    }
    return values.firstNotNullOfOrNull { value -> when (value) {
        "invalid_client", "invalid client", "invalid client id", "invalid client_id" -> DeviceRejection.CLIENT_REJECTION_REPORTED
        "invalid_scope", "invalid scope", "invalid scopes", "invalid scopes requested" -> DeviceRejection.SCOPE_REJECTION_REPORTED
        "unauthorized_client", "unsupported_grant_type", "invalid client type", "client is not public" -> DeviceRejection.DEVICE_FLOW_UNAVAILABLE_REPORTED
        "invalid_request", "invalid request", "missing required parameter" -> DeviceRejection.REQUEST_REJECTION_REPORTED
        "client secret is required", "missing client secret" -> DeviceRejection.SECRET_REQUIRED_REPORTED
        else -> null
    } } ?: DeviceRejection.UNCLASSIFIED
}
