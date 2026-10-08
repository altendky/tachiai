package net.fstab.tachiai.provider.twitch

internal const val SMART_TV_LIFETIME_INSPECTION_MS = 30_000L
internal const val SMART_TV_LOCAL_RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
internal enum class DeviceLifetimeShape { OMITTED, NULL, ZERO, POSITIVE, NEGATIVE, OUT_OF_RANGE, OTHER }

// Shape/sign only, never raw provider fields or token values. Zero is not
// labelled permanent validity; current provider lifetime semantics are unknown.
internal fun deviceLifetimeShape(fields: Map<String, Any?>): DeviceLifetimeShape {
    if (!fields.containsKey("expires_in")) return DeviceLifetimeShape.OMITTED
    val value = fields["expires_in"] ?: return DeviceLifetimeShape.NULL
    val seconds = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> return DeviceLifetimeShape.OTHER
    }
    return when {
        seconds == 0L -> DeviceLifetimeShape.ZERO
        seconds < 0 -> DeviceLifetimeShape.NEGATIVE
        seconds > Int.MAX_VALUE -> DeviceLifetimeShape.OUT_OF_RANGE
        else -> DeviceLifetimeShape.POSITIVE
    }
}
