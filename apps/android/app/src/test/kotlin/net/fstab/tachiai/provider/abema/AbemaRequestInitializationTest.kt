package net.fstab.tachiai.provider.abema

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AbemaRequestInitializationTest {
    private val first = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val second = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa")

    private fun protection(ids: String = first.toString(), extra: String = "") =
        "<ContentProtection schemeIdUri='urn:mpeg:dash:mp4protection:2011' value='cenc' cenc:default_KID='$ids'>$extra</ContentProtection>"

    private fun mpd(content: String = "<AdaptationSet>${protection()}</AdaptationSet>") =
        "<MPD xmlns='urn:mpeg:dash:schema:mpd:2011' xmlns:cenc='urn:mpeg:cenc:2013'><Period>$content</Period></MPD>"

    @Test fun `adaptation and representation identifiers remain opaque and ordered`() {
        val document = mpd("<AdaptationSet>${protection()}<Representation>${protection(second.toString())}</Representation></AdaptationSet>")
        val result = parseAbemaRequestInitialization(document)
        assertNotNull(result)
        assertEquals(listOf(first, second), result!!.kids)
        assertFalse(result.toString().contains(first.toString()))
        assertFalse(result.toString().contains(second.toString()))
    }

    @Test fun `distinct audio and video scopes may have different identifiers`() {
        assertEquals(listOf(first, second), parseAbemaRequestInitialization(mpd(
            "<AdaptationSet contentType='audio'>${protection()}</AdaptationSet>" +
                "<AdaptationSet contentType='video'>${protection(second.toString())}</AdaptationSet>"))!!.kids)
    }

    @Test fun `namespace prefixes do not change accepted schema`() {
        val document = "<dash:MPD xmlns:dash='urn:mpeg:dash:schema:mpd:2011' xmlns:x='urn:mpeg:cenc:2013'>" +
            "<dash:Period><dash:AdaptationSet><dash:ContentProtection schemeIdUri='urn:mpeg:dash:mp4protection:2011' " +
            "value='cenc' x:default_KID='$first'/></dash:AdaptationSet></dash:Period></dash:MPD>"
        assertEquals(listOf(first), parseAbemaRequestInitialization(document)!!.kids)
    }

    @Test fun `whitespace uppercase and repeated identifiers normalize without values in output`() {
        val ids = " ${first.toString().uppercase()}  $second $first "
        assertEquals(listOf(first, second), parseAbemaRequestInitialization(mpd(
            "<AdaptationSet>${protection(ids)}</AdaptationSet>"))!!.kids)
    }

    @Test fun `same scope repeated declarations must name the same set`() {
        assertEquals(listOf(first, second), parseAbemaRequestInitialization(mpd(
            "<AdaptationSet>${protection("$first $second")}${protection("$second $first")}</AdaptationSet>"))!!.kids)
        assertNull(parseAbemaRequestInitialization(mpd(
            "<AdaptationSet>${protection()}${protection(second.toString())}</AdaptationSet>")))
        assertNull(parseAbemaRequestInitialization(mpd(
            "<AdaptationSet><Representation>${protection()}${protection(second.toString())}</Representation></AdaptationSet>")))
    }

    @Test fun `unknown scheme namespace cipher and scope fail closed`() {
        val original = mpd()
        listOf(
            original.replace("urn:mpeg:dash:schema:mpd:2011", "urn:other"),
            original.replace("urn:mpeg:cenc:2013", "urn:other"),
            original.replace("urn:mpeg:dash:mp4protection:2011", "urn:uuid:unsupported"),
            original.replace("value='cenc'", "value='cbcs'"),
            original.replace("value='cenc'", ""),
            original.replace("cenc:default_KID", "default_KID"),
            mpd(protection()),
            mpd("<AdaptationSet><SupplementalProperty>${protection()}</SupplementalProperty></AdaptationSet>"),
            mpd("<Representation>${protection()}</Representation>"),
        ).forEach { assertNull(parseAbemaRequestInitialization(it)) }
    }

    @Test fun `missing empty zero noncanonical and invalid identifiers fail closed`() {
        assertNull(parseAbemaRequestInitialization(mpd("<AdaptationSet/>")))
        listOf("", " ", "00000000-0000-0000-0000-000000000000", "1-2-3-4-5", "not-an-id",
            "{$first}", "$first,bad", "$first 00000000-0000-0000-0000-000000000000").forEach { id ->
            assertNull(parseAbemaRequestInitialization(mpd("<AdaptationSet>${protection(id)}</AdaptationSet>")))
        }
    }

    @Test fun `sixteen unique identifiers accepted but any seventeenth rejected`() {
        val ids = (1L..16L).map { UUID(1, it) }
        assertEquals(ids, parseAbemaRequestInitialization(mpd(
            "<AdaptationSet>${protection(ids.joinToString(" "))}</AdaptationSet>"))!!.kids)
        assertNull(parseAbemaRequestInitialization(mpd(
            "<AdaptationSet>${protection((ids + UUID(1, 17)).joinToString(" "))}</AdaptationSet>")))
        assertNull(parseAbemaRequestInitialization(mpd(ids.joinToString("") {
            "<AdaptationSet>${protection(it.toString())}</AdaptationSet>"
        } + "<AdaptationSet>${protection(UUID(1, 17).toString())}</AdaptationSet>")))
    }

    @Test fun `opaque protection payloads are not parsed for request initialization`() {
        val opaque = "<cenc:pssh>NOT_BASE64_DO_NOT_INSPECT</cenc:pssh><Opaque xmlns='urn:other'>not initialization</Opaque>"
        assertEquals(listOf(first), parseAbemaRequestInitialization(mpd(
            "<AdaptationSet>${protection(extra = opaque)}</AdaptationSet>"))!!.kids)
    }

    @Test fun `DTD entity and external declarations fail without parser output`() {
        val errors = ByteArrayOutputStream()
        val previous = System.err
        try {
            System.setErr(PrintStream(errors))
            listOf(
                "<!DOCTYPE MPD SYSTEM 'file:///must-not-open'>${mpd()}",
                "<!DOCTYPE MPD [<!ENTITY value 'expanded'>]>${mpd()}",
                "<!ENTITY value SYSTEM 'https://must-not-open.invalid/'>${mpd()}",
                mpd().replace(first.toString(), "&unknown;"),
                "<MPD><invalid></MPD>",
            ).forEach { assertNull(parseAbemaRequestInitialization(it)) }
        } finally { System.setErr(previous) }
        assertEquals("", errors.toString("UTF-8"))
    }

    @Test fun `predefined escapes remain ordinary XML`() {
        assertEquals(listOf(first), parseAbemaRequestInitialization(mpd(
            "<AdaptationSet label='audio &amp; video'>${protection()}</AdaptationSet>"))!!.kids)
    }

    @Test fun `byte depth and element budgets fail closed`() {
        val base = mpd()
        val size = 256 * 1024
        val padded = base.replace("</MPD>", " ".repeat(size - base.toByteArray().size) + "</MPD>")
        assertNotNull(parseAbemaRequestInitialization(padded))
        assertNull(parseAbemaRequestInitialization(padded + " "))
        assertNull(parseAbemaRequestInitialization(base.replace("</MPD>", "é".repeat(size / 2) + "</MPD>")))
        val deep = base.replace("</MPD>", "<x>".repeat(40) + "</x>".repeat(40) + "</MPD>")
        assertNull(parseAbemaRequestInitialization(deep))
        assertNull(parseAbemaRequestInitialization(base.replace("</MPD>", "<x/>".repeat(8192) + "</MPD>")))
    }

    private fun diagnostic(document: String): AbemaInitializationDiagnostic {
        val values = mutableListOf<AbemaInitializationDiagnostic>()
        val parsed = parseAbemaRequestInitialization(document, values::add)
        assertEquals(parseAbemaRequestInitialization(document)?.kids, parsed?.kids)
        assertEquals(1, values.size)
        return values.single()
    }

    @Test fun `diagnostics count only closed structural categories without tightening ignored protection`() {
        val document = mpd("<AdaptationSet>${protection()}" +
            "<ContentProtection schemeIdUri='urn:mpeg:dash:mp4protection:2011' value='cbcs'/>" +
            "<Representation><ContentProtection schemeIdUri='urn:unknown' value='private'/></Representation>" +
            "</AdaptationSet>")
        assertEquals(AbemaInitializationDiagnostic(AbemaInitializationReason.ACCEPTED,
            periods = 1, adaptationSets = 1, protections = 3, defaultIdDeclarations = 1,
            cencProtections = 1, cbcsProtections = 1, otherProtections = 1), diagnostic(document))
        assertEquals(listOf(first), parseAbemaRequestInitialization(document)!!.kids)
    }

    @Test fun `absent identifiers distinguish missing protection from incomplete protection`() {
        val clearLike = diagnostic(mpd("<AdaptationSet/>"))
        assertEquals(AbemaInitializationReason.NO_DEFAULT_ID, clearLike.reason)
        assertEquals(0, clearLike.protections)
        val incomplete = diagnostic(mpd("<AdaptationSet><ContentProtection " +
            "schemeIdUri='urn:mpeg:dash:mp4protection:2011' value='cenc'/></AdaptationSet>"))
        assertEquals(AbemaInitializationReason.NO_DEFAULT_ID, incomplete.reason)
        assertEquals(1, incomplete.protections)
        assertEquals(0, incomplete.defaultIdDeclarations)
        assertNull(parseAbemaRequestInitialization(mpd("<AdaptationSet/>")))
    }

    @Test fun `closed reasons separate root hierarchy and protection scope failures`() {
        assertEquals(AbemaInitializationReason.ROOT_OR_HIERARCHY,
            diagnostic(mpd().replace("urn:mpeg:dash:schema:mpd:2011", "urn:other")).reason)
        assertEquals(AbemaInitializationReason.ROOT_OR_HIERARCHY,
            diagnostic(mpd("<Representation>${protection()}</Representation>")).reason)
        assertEquals(AbemaInitializationReason.PROTECTION_NAMESPACE_OR_SCOPE, diagnostic(mpd(protection())).reason)
        assertEquals(AbemaInitializationReason.PROTECTION_NAMESPACE_OR_SCOPE,
            diagnostic(mpd().replace("urn:mpeg:cenc:2013", "urn:other")).reason)
        assertEquals(AbemaInitializationReason.PROTECTION_NAMESPACE_OR_SCOPE,
            diagnostic(mpd().replace("cenc:default_KID", "default_KID")).reason)
    }

    @Test fun `cipher and scheme refusals remain separate from namespace and identifier problems`() {
        val cbcs = diagnostic(mpd().replace("value='cenc'", "value='cbcs'"))
        assertEquals(AbemaInitializationReason.SCHEME_OR_CIPHER, cbcs.reason)
        assertEquals(1, cbcs.cbcsProtections)
        assertEquals(1, cbcs.defaultIdDeclarations)
        assertEquals(AbemaInitializationReason.SCHEME_OR_CIPHER,
            diagnostic(mpd().replace("urn:mpeg:dash:mp4protection:2011", "urn:unsupported")).reason)
        assertEquals(AbemaInitializationReason.SCHEME_OR_CIPHER,
            diagnostic(mpd().replace("value='cenc'", "")).reason)
    }

    @Test fun `identifier refusal reasons reveal no identifier values`() {
        val examples = listOf(
            protection("not-an-id") to AbemaInitializationReason.ID_FORMAT,
            protection("00000000-0000-0000-0000-000000000000") to AbemaInitializationReason.ZERO_ID,
            protection() + protection(second.toString()) to AbemaInitializationReason.SAME_SCOPE_CONFLICT,
            protection((1L..17L).joinToString(" ") { UUID(1, it).toString() }) to AbemaInitializationReason.ID_LIMIT,
        )
        examples.forEach { (content, reason) ->
            assertEquals(reason, diagnostic(mpd("<AdaptationSet>$content</AdaptationSet>")).reason)
        }
    }

    @Test fun `byte element and depth limits retain their distinct closed reasons`() {
        val base = mpd()
        assertEquals(AbemaInitializationReason.BYTE_LIMIT, diagnostic(base + " ".repeat(256 * 1024)).reason)
        assertEquals(AbemaInitializationReason.BYTE_LIMIT,
            diagnostic(base.replace("</MPD>", "é".repeat(128 * 1024) + "</MPD>")).reason)
        val elements = diagnostic(base.replace("</MPD>", "<x/>".repeat(8192) + "</MPD>"))
        assertEquals(AbemaInitializationReason.ELEMENT_LIMIT, elements.reason)
        val depth = diagnostic(base.replace("</MPD>", "<x>".repeat(40) + "</x>".repeat(40) + "</MPD>"))
        assertEquals(AbemaInitializationReason.DEPTH_LIMIT, depth.reason)
        listOf(elements, depth).forEach {
            listOf(it.periods, it.adaptationSets, it.protections, it.defaultIdDeclarations,
                it.cencProtections, it.cbcsProtections, it.otherProtections).forEach { count ->
                assertTrue(count in 0..8192)
            }
        }
    }

    @Test fun `structural counts stay bounded at the unchanged element ceiling`() {
        fun document(periods: Int) = "<MPD xmlns='urn:mpeg:dash:schema:mpd:2011'>" +
            "<Period/>".repeat(periods) + "</MPD>"
        val complete = diagnostic(document(8191))
        assertEquals(AbemaInitializationReason.NO_DEFAULT_ID, complete.reason)
        assertEquals(8191, complete.periods)
        val refused = diagnostic(document(8192))
        assertEquals(AbemaInitializationReason.ELEMENT_LIMIT, refused.reason)
        assertEquals(8191, refused.periods)
    }

    @Test fun `combined failures retain the first closed refusal reason`() {
        assertEquals(AbemaInitializationReason.PROTECTION_NAMESPACE_OR_SCOPE,
            diagnostic(mpd().replace("urn:mpeg:cenc:2013", "urn:other")
                .replace("value='cenc'", "value='cbcs'")).reason)
        assertEquals(AbemaInitializationReason.ZERO_ID, diagnostic(mpd(
            "<AdaptationSet>${protection()}${protection("00000000-0000-0000-0000-000000000000")}</AdaptationSet>")).reason)
    }

    @Test fun `declarations and malformed XML have closed reasons without parser output`() {
        val errors = ByteArrayOutputStream()
        val previous = System.err
        try {
            System.setErr(PrintStream(errors))
            assertEquals(AbemaInitializationReason.DECLARATION,
                diagnostic("<!DOCTYPE MPD SYSTEM 'file:///must-not-open'>${mpd()}").reason)
            assertEquals(AbemaInitializationReason.XML_MALFORMED,
                diagnostic(mpd().replace("</MPD>", "")).reason)
            assertEquals(AbemaInitializationReason.XML_MALFORMED,
                diagnostic(mpd().replace(first.toString(), "&unknown;")).reason)
        } finally { System.setErr(previous) }
        assertEquals("", errors.toString("UTF-8"))
    }

    @Test fun `diagnostic callback exceptions cannot change accepted or refused initialization`() {
        assertEquals(listOf(first), parseAbemaRequestInitialization(mpd()) {
            throw IllegalStateException("private callback failure")
        }!!.kids)
        assertNull(parseAbemaRequestInitialization(mpd("<AdaptationSet/>")) {
            throw IllegalStateException("private callback failure")
        })
        assertNull(parseAbemaRequestInitialization("<!DOCTYPE MPD>${mpd()}") {
            throw IllegalStateException("private callback failure")
        })
        assertNull(parseAbemaRequestInitialization(mpd(protection())) {
            throw IllegalStateException("private callback failure")
        })
    }

    @Test fun `diagnostic serialization contains no identifiers payload attributes or URLs`() {
        val secretFixture = "opaque-payload-do-not-output"
        val privateUrl = "https://private.invalid/manifest?token=private-fixture"
        val content = "<AdaptationSet label='$privateUrl'>${protection(extra =
            "<cenc:pssh>$secretFixture</cenc:pssh>")}</AdaptationSet>"
        val result = diagnostic(mpd(content))
        val output = result.toString() + result.closedSummary()
        listOf(first.toString(), second.toString(), secretFixture, privateUrl, "private-fixture").forEach {
            assertFalse(output.contains(it))
        }
        assertEquals(AbemaInitializationReason.ACCEPTED, result.reason)
    }

    @Test fun `common PSSH uses standard version one common system and no opaque payload`() {
        val bytes = commonPssh(listOf(first, second))
        val buffer = ByteBuffer.wrap(bytes)
        assertEquals(68, buffer.int)
        assertEquals(0x70737368, buffer.int)
        assertEquals(0x01000000, buffer.int)
        assertEquals(UUID.fromString("1077efec-c0b2-4d02-ace3-3c1e52e2fb4b"), UUID(buffer.long, buffer.long))
        assertEquals(2, buffer.int)
        assertEquals(first, UUID(buffer.long, buffer.long))
        assertEquals(second, UUID(buffer.long, buffer.long))
        assertEquals(0, buffer.int)
        assertEquals(0, buffer.remaining())
        assertArrayEquals(bytes, commonPssh(listOf(first, second)))
    }

    @Test fun `PSSH builder rejects absent repeated zero or excess identifiers`() {
        listOf(emptyList(), listOf(first, first), listOf(UUID(0, 0)), (1L..17L).map { UUID(1, it) }).forEach {
            assertThrows(IllegalArgumentException::class.java) { commonPssh(it) }
        }
    }
}
