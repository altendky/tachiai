package net.fstab.tachiai.provider.abema

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AbemaManifestTransitionPolicyTest {
    private val first = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val second = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa")
    private fun protection(id: UUID = first) = "<ContentProtection schemeIdUri='urn:mpeg:dash:mp4protection:2011' value='cenc' cenc:default_KID='$id'/>"
    private fun document(content: String = protection()) =
        "<MPD xmlns='urn:mpeg:dash:schema:mpd:2011' xmlns:cenc='urn:mpeg:cenc:2013'><Period><AdaptationSet>$content<Representation/></AdaptationSet></Period></MPD>"
    private fun started() = AbemaManifestTransitionPolicy(listOf(first)).also { assertTrue(it.accepts(document())) }

    @Test fun `protected clear and same protected transitions preserve initial binding`() {
        val policy = AbemaManifestTransitionPolicy(listOf(first))
        assertTrue(policy.accepts(document()))
        assertEquals(AbemaManifestTransitionVerdict.INITIAL_PROTECTED, policy.verdict)
        assertFalse(policy.requiresDeclarationFreeModel)
        repeat(3) {
            assertTrue(policy.accepts(document("")))
            assertEquals(AbemaManifestTransitionVerdict.NO_DECLARATIONS, policy.verdict)
            assertTrue(policy.requiresDeclarationFreeModel)
            assertTrue(policy.accepts(document()))
            assertEquals(AbemaManifestTransitionVerdict.SAME_PROTECTION, policy.verdict)
            assertFalse(policy.requiresDeclarationFreeModel)
        }
        assertNull(parseAbemaRequestInitialization(document(""))) // Old strict case unchanged.
    }

    @Test fun `clear start cannot establish or replace protected baseline`() {
        val policy = AbemaManifestTransitionPolicy(listOf(first))
        assertFalse(policy.accepts(document("")))
        assertEquals(AbemaManifestTransitionVerdict.PROTECTED_START_REQUIRED, policy.verdict)
        assertTrue(policy.accepts(document()))
        assertTrue(policy.accepts(document("")))
        assertFalse(policy.accepts(document(protection(second))))
        assertEquals(AbemaManifestTransitionVerdict.ID_SET_CHANGED, policy.verdict)
        assertTrue(policy.accepts(document()))
    }

    @Test fun `missing IDs with any protection remain refused`() {
        for (content in listOf("<ContentProtection schemeIdUri='urn:unknown'/>",
            "<ContentProtection schemeIdUri='urn:mpeg:dash:mp4protection:2011' value='cenc'/>",
            "<ContentProtection schemeIdUri='urn:mpeg:dash:mp4protection:2011' value='cbcs'/>")) {
            val policy = started()
            assertFalse(policy.accepts(document(content)))
            assertEquals(AbemaManifestTransitionVerdict.PROTECTION_REFUSED, policy.verdict)
        }
    }

    @Test fun `clear lookalikes foreign namespaces and DRM markers cannot pass`() {
        for (content in listOf("<ContentProtection xmlns='urn:other'/>", "<pssh xmlns='urn:other'/>",
            "<cenc:pssh>OPAQUE_NOT_READ</cenc:pssh>", "<pro/>", "<Laurl/>", "<ProtectionHeader/>",
            "<License/>", "<x xmlns='urn:other' default_KID='not-an-id'/>",
            "<x xmlns='urn:other' kid='not-an-id'/>", "<x cenc:other='opaque'/>",
            "<Representation xmlns='urn:other'/>", "<Period xmlns='urn:other'/>")) {
            assertFalse("fixture must refuse", started().accepts(document(content)))
        }
    }

    @Test fun `protected refresh rejects new scheme value or scope shape`() {
        val unknown = "<ContentProtection schemeIdUri='urn:unknown'/>"
        assertFalse(started().accepts(document(protection() + unknown)))
        assertFalse(started().accepts(document(protection() + "<ContentProtection schemeIdUri='urn:mpeg:dash:mp4protection:2011' value='cbcs'/>")))
        assertFalse(started().accepts(document("<Representation>${protection()}</Representation>")))
        assertFalse(started().accepts(document(protection() + "<ContentProtection xmlns='urn:other' schemeIdUri='urn:unknown'/>")))
        assertFalse(started().accepts(document(protection() + "<pssh xmlns='urn:other'/>")))
        assertFalse(started().accepts(document(protection() + "<License/>")))
        assertFalse(started().accepts(document(protection() + "<x cenc:other='opaque'/>")))
    }

    @Test fun `initial baseline shapes are transient and may disappear without new shapes`() {
        val companion = "<ContentProtection schemeIdUri='urn:uuid:fixture' value='fixture'/>"
        val policy = AbemaManifestTransitionPolicy(listOf(first))
        assertTrue(policy.accepts(document(protection() + companion)))
        assertTrue(policy.accepts(document()))
        assertTrue(policy.accepts(document("")))
        assertTrue(policy.accepts(document(protection() + companion)))
        assertFalse(policy.toString().contains(first.toString()))
        assertFalse(policy.toString().contains("urn:uuid:fixture"))
    }

    @Test fun `any new removed malformed zero or unsupported ID fails`() {
        val two = AbemaManifestTransitionPolicy(listOf(first, second))
        val both = document(protection() + "</AdaptationSet><AdaptationSet>${protection(second)}")
        assertTrue(two.accepts(both))
        assertFalse(two.accepts(document()))
        for (body in listOf(document(protection(second)), document().replace(first.toString(), "not-an-id"),
            document().replace(first.toString(), UUID(0, 0).toString()), document().replace("value='cenc'", "value='cbcs'"))) {
            assertFalse(started().accepts(body))
        }
    }

    @Test fun `full document validation limits and malformed input cannot become clear`() {
        for (body in listOf("<!DOCTYPE MPD SYSTEM 'file:///must-not-open'>" + document(""),
            document("").replace("</MPD>", "<x>".repeat(40) + "</x>".repeat(40) + "</MPD>"),
            document("<x/>".repeat(8192)), document(" ".repeat(256 * 1024)), "<MPD>",
            document("&unknown;"))) assertFalse(started().accepts(body))
    }

    @Test fun `shape count length and missing scheme stay bounded`() {
        for (content in listOf("<ContentProtection/>",
            "<ContentProtection schemeIdUri='${"x".repeat(257)}'/>",
            "<ContentProtection schemeIdUri='fixture' value='${"x".repeat(65)}'/>",
            (1..33).joinToString("") { "<ContentProtection schemeIdUri='fixture-$it'/>" })) {
            assertFalse(AbemaManifestTransitionPolicy(listOf(first)).accepts(document(protection() + content)))
        }
    }

    @Test fun `auditing never prints malformed provider data`() {
        val errors = ByteArrayOutputStream()
        val previous = System.err
        try {
            System.setErr(PrintStream(errors))
            assertFalse(started().accepts(document("<private>https://private.invalid/?token=DO_NOT_PRINT</broken>")))
        } finally { System.setErr(previous) }
        assertEquals("", errors.toString("UTF-8"))
    }
}
