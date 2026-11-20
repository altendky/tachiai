package net.fstab.tachiai.provider.abema

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import net.fstab.tachiai.presentation.FeedRecoveryAction
import net.fstab.tachiai.presentation.PrototypeFailureReason
import net.fstab.tachiai.presentation.PrototypeFeedFailure
import net.fstab.tachiai.presentation.channelNotAiringMessage
import net.fstab.tachiai.presentation.recoveryAction
import org.junit.Assert.*
import org.junit.Test

class AbemaChannelNotAiringTest {
    private val mpd = "urn:mpeg:dash:schema:mpd:2011"
    private fun manifest(adaptation: String = "", periods: Int = 10, root: String = "") =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><MPD xmlns=\"$mpd\" xmlns:cenc=\"urn:mpeg:cenc:2013\" type=\"dynamic\"$root>" +
            (0 until periods).joinToString("") { index ->
                "<Period id=\"p$index\"><AdaptationSet mimeType=\"video/mp4\">$adaptation<Representation id=\"v$index\" bandwidth=\"1\"/>" +
                    "</AdaptationSet><AdaptationSet mimeType=\"audio/mp4\"><Representation id=\"a$index\" bandwidth=\"1\"/></AdaptationSet></Period>"
            } + "</MPD>"
    private fun notAiring(text: String): Boolean {
        var diagnostic: AbemaInitializationDiagnostic? = null
        return parseAbemaRequestInitialization(text) { diagnostic = it } == null && abemaChannelManifestNotAiring(diagnostic, text)
    }

    @Test fun onlyAWellFormedManifestWithoutAnyProtectionDeclarationIsNotAiring() {
        // As observed: ten periods, twenty adaptation sets, no protection.
        var diagnostic: AbemaInitializationDiagnostic? = null
        assertNull(parseAbemaRequestInitialization(manifest()) { diagnostic = it })
        assertEquals("reason=NO_DEFAULT_ID periods=10 adaptations=20 protections=0 defaultIdDeclarations=0 cenc=0 cbcs=0 other=0",
            diagnostic!!.closedSummary())
        assertTrue(notAiring(manifest()))
        // Protected starts keep their normal acceptance.
        val kid = "<ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\" value=\"cenc\" " +
            "cenc:default_KID=\"11111111-2222-3333-4444-555555555555\"/>"
        assertNotNull(parseAbemaRequestInitialization(manifest(kid)))
        // Any protection declaration, DRM marker, malformed or empty document keeps the generic refusal.
        // These pass the initialization parse as NO_DEFAULT_ID; the counts or the marker audit refuse them.
        val audited = listOf(
            manifest("<ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\" value=\"cenc\"/>"),
            manifest("<ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"/>"),
            manifest("<pssh>AAAA</pssh>"),
            manifest("<cenc:pssh>AAAA</cenc:pssh>"),
            manifest("<Laurl>x</Laurl>"),
            manifest(periods = 0),
        )
        for (text in audited) {
            var diagnostic: AbemaInitializationDiagnostic? = null
            assertNull(parseAbemaRequestInitialization(text) { diagnostic = it })
            assertEquals(text, AbemaInitializationReason.NO_DEFAULT_ID, diagnostic!!.reason)
            assertFalse(text, notAiring(text))
        }
        // The marker audit alone separates these from the clear manifest.
        for (text in audited.subList(2, 5)) assertFalse(text, abemaManifestDeclaresNoProtection(text))
        assertTrue(abemaManifestDeclaresNoProtection(manifest()))
        for (text in listOf(manifest(root = " kid=\"x\""), manifest().dropLast(3), "<MPD xmlns=\"urn:other\"><Period/></MPD>"))
            assertFalse(text, notAiring(text))
        assertFalse(abemaChannelManifestNotAiring(null, manifest()))
    }

    // Automatic recovery is covered in FeedAutoRecoveryStateTest.
    @Test fun theRefusalHasItsOwnRetryableMessage() {
        val failure = PrototypeFeedFailure(PrototypeFailureReason.CHANNEL_NOT_AIRING)
        assertEquals(FeedRecoveryAction.RETRY, failure.recoveryAction())
        assertEquals("This channel isn't showing a program Tachiai can play right now.\n" +
            "It may be between broadcasts. Try again when the program starts.", failure.message)
    }

    @Test fun theMessageNamesWhenItWasLastCheckedToTheSecond() {
        val failure = PrototypeFeedFailure(PrototypeFailureReason.CHANNEL_NOT_AIRING)
        val tokyo = ZoneId.of("Asia/Tokyo")
        val checked = 1_763_600_436_000L
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(Locale.US)
            .format(Instant.ofEpochMilli(checked).atZone(tokyo))
        assertEquals("${failure.message}\nLast checked at $time.", channelNotAiringMessage(failure, checked, tokyo, Locale.US))
        assertNotEquals(channelNotAiringMessage(failure, checked, tokyo, Locale.US),
            channelNotAiringMessage(failure, checked + 1_000, tokyo, Locale.US))
    }
}
