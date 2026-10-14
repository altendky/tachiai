package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.platform.diagnostics.FailureCategory

internal enum class AbemaCachedHelperFailure {
    NONE, TIMER_EXPIRED, OWNED_CANCELLED, FETCH_REJECTED_BEFORE_HTTP,
    HTTP_REFUSED, RESPONSE_VALIDATION_REFUSED, UNKNOWN,
}

internal fun AbemaCachedHelperFailure.diagnosticCategory(): FailureCategory = when (this) {
    AbemaCachedHelperFailure.NONE -> FailureCategory.NONE
    AbemaCachedHelperFailure.TIMER_EXPIRED -> FailureCategory.ABEMA_CACHED_TIMER_EXPIRED
    AbemaCachedHelperFailure.OWNED_CANCELLED -> FailureCategory.ABEMA_CACHED_OWNED_CANCELLED
    AbemaCachedHelperFailure.FETCH_REJECTED_BEFORE_HTTP -> FailureCategory.ABEMA_CACHED_FETCH_REJECTED_BEFORE_HTTP
    AbemaCachedHelperFailure.HTTP_REFUSED -> FailureCategory.ABEMA_CACHED_HTTP_REFUSED
    AbemaCachedHelperFailure.RESPONSE_VALIDATION_REFUSED -> FailureCategory.ABEMA_CACHED_RESPONSE_VALIDATION_REFUSED
    AbemaCachedHelperFailure.UNKNOWN -> FailureCategory.ABEMA_CACHED_UNKNOWN
}

internal data class AbemaCachedHelperMetadata(val phase: String, val modules: Int, val lastModule: Int,
    val http: Int, val failure: AbemaCachedHelperFailure) {
    fun closedSummary() = "phase=$phase modules=$modules lastModule=$lastModule http=$http failure=${failure.name}"
}

private val helperPhases = setOf("MODULES", "GUEST", "ACCOUNT", "MEDIA_TOKEN", "DEVICE", "CONTENT",
    "REPLAY_FREE", "REPLAY_WINDOW", "REPLAY_RESTRICTIONS", "REPLAY_TERMS", "REPLAY_RENTAL",
    "REPLAY_PRECEDENCE", "REPLAY_DEVICE", "REPLAY_TRIAL", "REPLAY_PARTNER", "REPLAY_EXTERNAL_CONTENT",
    "REPLAY_EXTERNAL_PROVIDER", "REPLAY_AUTHORITIES", "SOURCE_SESSION_REQUIRED", "SOURCE_MODE_UNSUPPORTED",
    "SLOT_REGION", "SLOT_FREE", "SLOT_RESTRICTIONS", "SLOT_WINDOW",
    "CHANNEL_REQUEST", "CHANNEL_RESPONSE", "CHANNEL_ENVELOPE_REFUSED", "CHANNEL_SELECTION_REFUSED",
    "CHANNEL_UNAVAILABLE", "CHANNEL_DRM_REFUSED", "CHANNEL_PLAYLIST_REFUSED", "CHANNEL_ALTERNATE_REFUSED",
    "SOURCE", "CONFIG", "LICENSE", "READY", "FAILED")
private const val DECIMAL = "(0|[1-9][0-9]{0,6})"
private val helperMetadataShape = Regex("""\{"phase":"([A-Z_]{1,40})","modules":$DECIMAL,"lastModule":$DECIMAL,"http":$DECIMAL(?:,"failure":"([A-Z_]{1,40})")?\}""")

// Exactly the canonical Stringify projection, including the old four-field
// version. No duplicate/extra fields, coercion, escapes or provider values.
internal fun parseAbemaCachedHelperMetadata(text: String?): AbemaCachedHelperMetadata? {
    if (text == null || text.length !in 1..512) return null
    val values = helperMetadataShape.matchEntire(text)?.groupValues ?: return null
    val phase = values[1].takeIf { it in helperPhases } ?: return null
    val modules = values[2].toIntOrNull()?.takeIf { it in 0..4096 } ?: return null
    val lastModule = values[3].toIntOrNull()?.takeIf { it in 0..1_000_000 } ?: return null
    val http = values[4].toIntOrNull()?.takeIf { it == 0 || it in 100..599 } ?: return null
    val name = values[5]
    val failure = if (name.isEmpty()) AbemaCachedHelperFailure.NONE
        else AbemaCachedHelperFailure.entries.firstOrNull { it.name == name } ?: return null
    val coherentStatus = when (failure) {
        AbemaCachedHelperFailure.FETCH_REJECTED_BEFORE_HTTP -> http == 0
        AbemaCachedHelperFailure.HTTP_REFUSED -> http in 100..599 && http != 200
        AbemaCachedHelperFailure.RESPONSE_VALIDATION_REFUSED -> http == 200
        else -> http == 0 || http == 200
    }
    if (name.isNotEmpty() && !coherentStatus) return null
    return AbemaCachedHelperMetadata(phase, modules, lastModule, http, failure)
}

// evaluateJavascript returns one JSON string. Its projection is ASCII and has
// no backslashes, so require the exact outer encoding as well as the inner one.
internal fun parseAbemaCachedHelperMetadataCallback(raw: String?): AbemaCachedHelperMetadata? {
    if (raw == null || raw.length !in 2..1024 || raw.first() != '"' || raw.last() != '"') return null
    val text = raw.substring(1, raw.lastIndex).replace("\\\"", "\"")
    if (raw != "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"") return null
    return parseAbemaCachedHelperMetadata(text)
}

// Independent per helper. A stale READY observation cannot erase a proved
// failure and trigger preparation; cleanup cannot replace the first category.
internal class AbemaCachedHelperEvidence {
    var firstFailure = AbemaCachedHelperFailure.NONE
        private set
    fun observe(value: AbemaCachedHelperMetadata): Boolean = remember(value.failure)
    fun remember(value: AbemaCachedHelperFailure): Boolean {
        if (firstFailure != AbemaCachedHelperFailure.NONE || value == AbemaCachedHelperFailure.NONE) return false
        firstFailure = value
        return true
    }
    fun permitsPreparation(state: String, value: AbemaCachedHelperMetadata?): Boolean =
        state == "READY" && firstFailure == AbemaCachedHelperFailure.NONE && value?.phase == "READY" &&
            value.failure == AbemaCachedHelperFailure.NONE && value.http == 200
}

// Observation only; the caller supplies already-parsed closed metadata. An
// invalid projection destroys diagnostic confidence, never source admission.
internal class AbemaChannelPreparationEvidence {
    private var value: AbemaCachedHelperMetadata? = null
    private var failed = false
    private var refused = false
    @get:Synchronized val snapshot: AbemaCachedHelperMetadata? get() = value
    @Synchronized fun observe(value: AbemaCachedHelperMetadata) {
        if (refused || failed) return
        this.value = value
        failed = value.failure != AbemaCachedHelperFailure.NONE
    }
    @Synchronized fun refuse() { refused = true; value = null }
}
