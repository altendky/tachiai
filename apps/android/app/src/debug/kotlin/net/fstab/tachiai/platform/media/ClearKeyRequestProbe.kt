package net.fstab.tachiai.platform.media

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID

internal enum class RequestProbeOutcome {
    REQUEST_PREPARED, CANCELLED, CDM_UNSUPPORTED, NOT_PROVISIONED,
    SESSION_FAILED, REQUEST_FAILED, CLEANUP_FAILED,
    INITIALIZATION_REJECTED, CDM_STATE_REJECTED, REQUEST_METADATA_UNAVAILABLE,
}
internal enum class RequestShape { STANDARD_KIDS_JSON, EMPTY, OTHER, OVERSIZED }
internal enum class RequestSessionType { TEMPORARY, ABSENT, UNAVAILABLE }
internal enum class RequestKind { INITIAL, RENEWAL, RELEASE, NONE, UPDATE, OTHER }
internal enum class RequestDestination { EMPTY, PRESENT }
internal enum class RequestKidMatch { SAME_SET, DIFFERENT_SET, UNAVAILABLE }

// Only closed metadata may cross the platform boundary. No challenge, IDs or URL.
internal data class RequestMetadata(
    val shape: RequestShape,
    val bytes: Int,
    val kidCount: Int = 0,
    val sessionType: RequestSessionType = RequestSessionType.UNAVAILABLE,
    val kidMatch: RequestKidMatch = RequestKidMatch.UNAVAILABLE,
    val kind: RequestKind = RequestKind.OTHER,
    val destination: RequestDestination = RequestDestination.EMPTY,
)
internal data class RequestProbeResult(val outcome: RequestProbeOutcome, val metadata: RequestMetadata? = null)

// Deliberately recognizes only the small, standard Clear Key request grammar.
// Nonstandard payloads remain OTHER; no generic JSON dump or error text fallback.
internal fun clearKeyRequestMetadata(data: ByteArray, expectedKids: List<UUID>): RequestMetadata {
    if (data.isEmpty()) return RequestMetadata(RequestShape.EMPTY, 0)
    if (data.size > 16 * 1024) return RequestMetadata(RequestShape.OVERSIZED, -1)
    val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
    } catch (_: Exception) { return RequestMetadata(RequestShape.OTHER, data.size) }
    val kid = "[A-Za-z0-9_-]{21}[AQgw]"
    val gap = "[ \\t\\r\\n]*" // JSON whitespace, not Android/JVM's broader Unicode \\s.
    val array = "\\[$gap\"$kid\"(?:$gap,$gap\"$kid\"){0,15}$gap\\]"
    val kidsField = "\"kids\"$gap:$gap($array)"
    val typeField = "\"type\"$gap:$gap\"temporary\""
    val normal = Regex("$gap\\{$gap$kidsField(?:$gap,$gap$typeField)?$gap\\}$gap").matchEntire(text)
    val reversed = Regex("$gap\\{$gap$typeField$gap,$gap$kidsField$gap\\}$gap").matchEntire(text)
    val matched = normal ?: reversed ?: return RequestMetadata(RequestShape.OTHER, data.size)
    val values = Regex("\"($kid)\"").findAll(matched.groupValues[1]).map { it.groupValues[1] }.toList()
    if (values.toSet().size != values.size) return RequestMetadata(RequestShape.OTHER, data.size)
    val expected = expectedKids.map { uuid ->
        val bytes = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }.toSet()
    return RequestMetadata(RequestShape.STANDARD_KIDS_JSON, data.size, values.size,
        if (Regex(typeField).containsMatchIn(text)) RequestSessionType.TEMPORARY else RequestSessionType.ABSENT,
        if (expected == values.toSet()) RequestKidMatch.SAME_SET else RequestKidMatch.DIFFERENT_SET)
}

internal class RequestProbeUnavailable(val outcome: RequestProbeOutcome) : Exception()

// No license/provisioning transport or response operation exists on this interface.
internal interface RequestOnlyCdm : AutoCloseable {
    fun openSession()
    fun describeRequest(initialization: ByteArray, expectedKids: List<UUID>): RequestMetadata
    fun closeSession()
}

internal fun prepareRequestOnly(
    initialization: ByteArray,
    kids: List<UUID>,
    canRun: () -> Boolean,
    create: () -> RequestOnlyCdm,
): RequestProbeResult {
    var cdm: RequestOnlyCdm? = null
    var opened = false
    var stage = RequestProbeOutcome.CDM_UNSUPPORTED
    var result = RequestProbeResult(RequestProbeOutcome.CANCELLED)
    var cleanupFailed = false
    try {
        if (canRun()) {
            cdm = create()
            stage = RequestProbeOutcome.SESSION_FAILED
            if (canRun()) {
                cdm.openSession()
                opened = true
                stage = RequestProbeOutcome.REQUEST_FAILED
                if (canRun()) {
                    val metadata = cdm.describeRequest(initialization, kids)
                    if (canRun()) result = RequestProbeResult(RequestProbeOutcome.REQUEST_PREPARED, metadata)
                }
            }
        }
    } catch (failure: RequestProbeUnavailable) {
        result = RequestProbeResult(failure.outcome)
    } catch (_: Exception) {
        result = RequestProbeResult(if (canRun()) stage else RequestProbeOutcome.CANCELLED)
    } finally {
        if (opened) try { cdm?.closeSession() } catch (_: Exception) { cleanupFailed = true }
        try { cdm?.close() } catch (_: Exception) { cleanupFailed = true }
        initialization.fill(0)
    }
    return when {
        !canRun() -> RequestProbeResult(RequestProbeOutcome.CANCELLED)
        cleanupFailed -> RequestProbeResult(RequestProbeOutcome.CLEANUP_FAILED)
        else -> result
    }
}
