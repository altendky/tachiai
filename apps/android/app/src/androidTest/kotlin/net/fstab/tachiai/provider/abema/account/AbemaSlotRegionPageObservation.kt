package net.fstab.tachiai.provider.abema.account

import org.junit.Assert.*
import org.junit.Test

// Public HTML data only. Never execute it or return its field strings/URLs.
internal object AbemaSlotRegionPageObservation {
    enum class Shape { MISSING, MULTIPLE, NOT_OBJECT, UNTERMINATED, INVALID_JSON, OBJECT }
    enum class Field { ABSENT, NULL, TRUE, FALSE, JP, NONE, OTHER, EXACT_GLOBAL, EXACT_LINEAR, VALID_STRING, INVALID }
    class Summary(val shape: Shape, val allowed: Field = Field.ABSENT, val status: Field = Field.ABSENT,
        val country: Field = Field.ABSENT, val division: Field = Field.ABSENT, val cdn: Field = Field.ABSENT,
        val canonicalFamily: Boolean = false, val timezone: Field = Field.ABSENT) {
        override fun toString() = "shape=${shape.name} isAllowed=${allowed.name} status=${status.name} " +
            "country=${country.name} division=${division.name} cdn=${cdn.name} canonicalFamily=$canonicalFamily timezone=${timezone.name}"
    }
    fun parse(text: String): Summary {
        val assignments = Regex("window\\.__CLIENT_REGION__\\s*=\\s*").findAll(text).take(2).toList()
        if (assignments.isEmpty()) return Summary(Shape.MISSING)
        if (assignments.size != 1) return Summary(Shape.MULTIPLE)
        val start = assignments.single().range.last + 1
        if (text.getOrNull(start) != '{') return Summary(Shape.NOT_OBJECT)
        var quoted = false; var escaped = false; var depth = 0; var end = -1
        for (index in start until minOf(text.length, start + 8192)) {
            val char = text[index]
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true; '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = index + 1; break } }
            }
        }
        if (end < 0 || !Regex("^\\s*;").containsMatchIn(text.substring(end))) return Summary(Shape.UNTERMINATED)
        val fields = try { AbemaAccountJson.objectValue(text.substring(start, end), 8192) }
        catch (_: Throwable) { return Summary(Shape.INVALID_JSON) }
        fun classify(key: String, project: (Any) -> Field): Field = when {
            !fields.containsKey(key) -> Field.ABSENT
            fields[key] == null -> Field.NULL
            else -> project(checkNotNull(fields[key]))
        }
        fun bool(value: Any) = when (value) { true -> Field.TRUE; false -> Field.FALSE; else -> Field.INVALID }
        val cdn = fields["cdnURL"] as? String
        return Summary(Shape.OBJECT, classify("isAllowed", ::bool), classify("status", ::bool),
            classify("isoCountryCode") { if (it is String) { if (it == "JP") Field.JP else Field.OTHER } else Field.INVALID },
            classify("divisionCode") { if (it is Long) { if (it == 0L) Field.NONE else Field.OTHER } else Field.INVALID },
            classify("cdnURL") { when (it) {
                "https://ds-glb-linear-abematv.akamaized.net/region" -> Field.EXACT_GLOBAL
                "https://ds-linear-abematv.akamaized.net/region" -> Field.EXACT_LINEAR
                is String -> Field.OTHER; else -> Field.INVALID
            } }, cdn != null && Regex("https://[a-z0-9-]{1,63}-abematv\\.akamaized\\.net/region").matches(cdn),
            classify("timezone") { if (it is String && it.length <= 64) Field.VALID_STRING else Field.INVALID })
    }
}

class AbemaSlotRegionPageObservationTest {
    private fun page(json: String) = "<script>window.__CLIENT_REGION__ = $json;</script>"
    @Test fun classifiesBothExactResourcesWithoutExportingAnyProviderValues() {
        for ((host, category) in listOf("ds-glb-linear" to AbemaSlotRegionPageObservation.Field.EXACT_GLOBAL,
            "ds-linear" to AbemaSlotRegionPageObservation.Field.EXACT_LINEAR)) {
            val summary = AbemaSlotRegionPageObservation.parse(page("""{"isAllowed":true,"status":false,"isoCountryCode":"JP","divisionCode":0,"cdnURL":"https://$host-abematv.akamaized.net/region","timezone":"PRIVATE_TIMEZONE"}"""))
            assertEquals(AbemaSlotRegionPageObservation.Shape.OBJECT, summary.shape)
            assertEquals(category, summary.cdn); assertTrue(summary.canonicalFamily)
            assertEquals(AbemaSlotRegionPageObservation.Field.NONE, summary.division)
            assertEquals(AbemaSlotRegionPageObservation.Field.JP, summary.country)
            assertFalse(summary.toString().contains("PRIVATE")); assertFalse(summary.toString().contains("https"))
        }
    }
    @Test fun mismatchesMissingNullAndWrongTypesRemainClosedDiagnosticShapes() {
        val summary = AbemaSlotRegionPageObservation.parse(page("""{"isAllowed":false,"status":1,"isoCountryCode":"OTHER_PRIVATE","divisionCode":"0","cdnURL":"https://other-abematv.akamaized.net/region","timezone":null}"""))
        assertEquals(AbemaSlotRegionPageObservation.Field.FALSE, summary.allowed)
        assertEquals(AbemaSlotRegionPageObservation.Field.INVALID, summary.status)
        assertEquals(AbemaSlotRegionPageObservation.Field.INVALID, summary.division)
        assertEquals(AbemaSlotRegionPageObservation.Field.OTHER, summary.cdn); assertTrue(summary.canonicalFamily)
        assertEquals(AbemaSlotRegionPageObservation.Field.NULL, summary.timezone)
        assertEquals(AbemaSlotRegionPageObservation.Field.ABSENT, AbemaSlotRegionPageObservation.parse(page("{}")).cdn)
        assertFalse(summary.toString().contains("OTHER_PRIVATE"))
        for (url in listOf("https://ds-linear-abematv.akamaized.net/region?token=PRIVATE", "http://ds-linear-abematv.akamaized.net/region", "https://ds-linear-abematv.akamaized.net.evil/region"))
            assertFalse(AbemaSlotRegionPageObservation.parse(page("{\"cdnURL\":\"$url\"}")).canonicalFamily)
    }
    @Test fun refusesDuplicateEscapedKeysMalformedJsonMissingSemicolonAndAssignmentAmbiguity() {
        for (json in listOf("{\"status\":true,\"status\":false}", "{\"status\":true,\"sta\\u0074us\":false}",
            "{\"ignored\":{\"x\":1,\"x\":2}}", "{\"ignored\":1e999}", "{\"ignored\":\"\\ud800\"}"))
            assertEquals(AbemaSlotRegionPageObservation.Shape.INVALID_JSON, AbemaSlotRegionPageObservation.parse(page(json)).shape)
        assertEquals(AbemaSlotRegionPageObservation.Shape.MISSING, AbemaSlotRegionPageObservation.parse("PUBLIC_HTML").shape)
        assertEquals(AbemaSlotRegionPageObservation.Shape.MULTIPLE, AbemaSlotRegionPageObservation.parse(page("{}") + page("{}")).shape)
        assertEquals(AbemaSlotRegionPageObservation.Shape.NOT_OBJECT, AbemaSlotRegionPageObservation.parse(page("[]")).shape)
        assertEquals(AbemaSlotRegionPageObservation.Shape.UNTERMINATED, AbemaSlotRegionPageObservation.parse("window.__CLIENT_REGION__={}").shape)
        assertEquals(AbemaSlotRegionPageObservation.Shape.UNTERMINATED, AbemaSlotRegionPageObservation.parse(page("{\"ignored\":\"${"x".repeat(8192)}\"}")).shape)
    }
}
