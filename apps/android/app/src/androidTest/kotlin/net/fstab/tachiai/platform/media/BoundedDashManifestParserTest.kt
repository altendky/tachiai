package net.fstab.tachiai.platform.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.net.URI
import java.time.Instant
import net.fstab.tachiai.provider.abema.AbemaNewsMediaUriPolicy
import net.fstab.tachiai.provider.abema.abemaNewsCdnUriPolicy
import net.fstab.tachiai.provider.abema.parseAbemaRequestInitialization
import net.fstab.tachiai.provider.abema.AbemaManifestTransitionPolicy
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class BoundedDashManifestParserTest {
    private val manifest = URI("https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd")
    private val root = "https://linear-abematv.akamaized.net"
    private fun policy() = DeclaredDashMediaPolicy(manifest) { abemaNewsCdnUriPolicy(it) == AbemaNewsMediaUriPolicy.ALLOWED }
    private fun parser(policy: DeclaredDashMediaPolicy, active: () -> Boolean = { true }) =
        BoundedDashManifestParser(manifest, policy, { parseAbemaRequestInitialization(it) != null }, active, { _, _ -> })

    private fun mpd(segment: String = template(), duration: String = "PT6S", rootBase: String = "",
        periodBase: String = "", adaptationBase: String = "", representationBase: String = "", extra: String = "") = """
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013"
          mediaPresentationDuration="$duration" minBufferTime="PT1S" type="static">
          $rootBase $extra
          <Period start="PT0S">$periodBase
            <AdaptationSet mimeType="video/mp4" codecs="avc1.4d401f">$adaptationBase
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"
                cenc:default_KID="00112233-4455-6677-8899-aabbccddeeff"/>
              <Representation id="high" bandwidth="1000000">$representationBase $segment</Representation>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    private fun template(media: String = "segment-${'$'}Number%03d${'$'}.m4s", timeline: String = "") = """
        <SegmentTemplate timescale="1" duration="2" startNumber="1"
          initialization="init-${'$'}RepresentationID${'$'}.mp4" media="$media">$timeline</SegmentTemplate>
    """.trimIndent()

    private fun parse(body: String, policy: DeclaredDashMediaPolicy = policy()) =
        parser(policy).parse(Uri.parse(manifest.toString()), body.byteInputStream())

    @Test fun inheritedBaseUrlsAndFormattedNumbersProduceOnlyExactFiles() {
        val policy = policy()
        parse(mpd(rootBase = "<BaseURL>$root/declared/</BaseURL>", periodBase = "<BaseURL>news/</BaseURL>",
            adaptationBase = "<BaseURL>video/</BaseURL>", representationBase = "<BaseURL>high/</BaseURL>"), policy)
        val base = "$root/declared/news/video/high/"
        assertTrue(policy.allows(URI("${base}init-high.mp4")))
        for (number in 1..3) assertTrue(policy.allows(URI("${base}segment-00$number.m4s")))
        assertFalse(policy.allows(URI("${base}segment-004.m4s")))
        assertFalse(policy.allows(URI("${base}init-low.mp4")))
    }

    @Test fun acceptedNotificationRequiresCompleteModelAndMediaPathAcceptance() {
        val uri = Uri.parse(manifest.toString())
        for (kind in listOf("accepted", "foreign-path", "model", "preflight", "expired")) {
            var notifications = 0
            val policy = policy()
            val delegate = BoundedDashManifestParser(manifest, policy,
                { kind != "preflight" && parseAbemaRequestInitialization(it) != null },
                { true }, { _, _ -> }, validateModel = { kind != "model" })
            val wrapped = AcceptedManifestParser(delegate, {
                if (kind == "expired") throw IOException("fixture expired")
            }) { notifications++ }
            val body = mpd(rootBase = if (kind == "foreign-path") "<BaseURL>https://evil.invalid/</BaseURL>" else "")
            if (kind == "accepted") {
                wrapped.parse(uri, body.byteInputStream())
                assertEquals(1, notifications)
                assertTrue(policy.allows(URI("$root/channel/abema-news/init-high.mp4")))
            } else {
                assertThrows(IOException::class.java) { wrapped.parse(uri, body.byteInputStream()) }
                assertEquals(0, notifications)
            }
        }
    }

    @Test fun timeBandwidthAndEscapedDollarFollowSdkTemplateSemantics() {
        val policy = policy()
        val segment = template("${'$'}${'$'}-${'$'}Bandwidth${'$'}-${'$'}Time%03d${'$'}.m4s",
            "<SegmentTimeline><S t=\"20\" d=\"2\" r=\"2\"/></SegmentTimeline>")
        parse(mpd(segment, rootBase = "<BaseURL>$root/declared/news/</BaseURL>"), policy)
        for (time in listOf(20, 22, 24)) assertTrue(policy.allows(URI("$root/declared/news/${'$'}-1000000-0$time.m4s")))
        assertFalse(policy.allows(URI("$root/declared/news/${'$'}-1000000-026.m4s")))
    }

    @Test fun segmentBaseIndexAndInitializationStayBoundToOneDeclaredFile() {
        val policy = policy()
        parse(mpd("<SegmentBase indexRange=\"100-200\"><Initialization range=\"0-99\"/></SegmentBase>",
            representationBase = "<BaseURL>$root/declared/news/file.mp4</BaseURL>"), policy)
        assertTrue(policy.allows(URI("$root/declared/news/file.mp4")))
        assertFalse(policy.allows(URI("$root/declared/news/other.mp4")))
    }

    @Test fun foreignBaseQueryLocationAndEntityFailWithoutPublishingPaths() {
        for (body in listOf(mpd(rootBase = "<BaseURL>https://evil.invalid/</BaseURL>"),
            mpd(template("segment-${'$'}Number${'$'}.m4s?fixture=1")),
            mpd(extra = "<Location>https://evil.invalid/other.mpd</Location>"),
            "<!DOCTYPE MPD SYSTEM 'file:///must-not-open'>" + mpd())) {
            val policy = policy()
            assertThrows(IOException::class.java) { parse(body, policy) }
            assertFalse(policy.allows(URI("$root/channel/abema-news/init-high.mp4")))
        }
    }

    @Test fun positiveAndNegativeTimelineExpansionHaveARealSdkBound() {
        for ((repeat, duration) in listOf("2147483646" to "PT6S", "-1" to "PT1000000S")) {
            val segment = template(timeline = "<SegmentTimeline><S t=\"0\" d=\"1\" r=\"$repeat\"/></SegmentTimeline>")
            assertThrows(IOException::class.java) { parse(mpd(segment, duration)) }
        }
    }

    @Test fun clockNormalizationDoesNotReturnAnExternalTimingEndpoint() {
        val parsed = parse(mpd(extra = """<UTCTiming schemeIdUri="urn:mpeg:dash:utc:http-xsdate:2014"
            value="https://evil.invalid/clock"/>"""))
        assertEquals("urn:mpeg:dash:utc:direct:2014", parsed.utcTiming!!.schemeIdUri)
        assertFalse(parsed.utcTiming!!.value.contains("http"))
    }

    @Test fun cancellationCannotPublishOrResurrectAClosedPolicy() {
        val policy = policy()
        assertThrows(IOException::class.java) {
            parser(policy) { false }.parse(Uri.parse(manifest.toString()), mpd().byteInputStream())
        }
        policy.close()
        assertThrows(IOException::class.java) { parse(mpd(), policy) }
        assertFalse(policy.allows(manifest))
    }

    @Test fun policyRefusalIsNonRetryableAndExposesOnlyItsClosedStage() {
        val stages = mutableListOf<DashManifestPolicyStage>()
        val refused = BoundedDashManifestParser(manifest, policy(), { false }, { true }, { _, _ -> }, stages::add)
        val error = assertThrows(ParserException::class.java) {
            refused.parse(Uri.parse(manifest.toString()), mpd().byteInputStream())
        }
        assertEquals(listOf(DashManifestPolicyStage.PREFLIGHT), stages)
        assertEquals(C.DATA_TYPE_MANIFEST, error.dataType)
        assertFalse(error.contentIsMalformed)
        assertEquals("Native manifest policy refused {contentIsMalformed=false, dataType=4}", error.message)
        assertNull(error.cause)
    }

    @Test fun closingALongLivePeriodKeepsOnlyItsWindowAndLookahead() {
        val nowMs = Instant.parse("2026-01-01T10:00:10Z").toEpochMilli()
        fun period(start: String, prefix: String): String = """
            <Period start="$start"><AdaptationSet mimeType="video/mp4" codecs="avc1.4d401f">
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"
                cenc:default_KID="00112233-4455-6677-8899-aabbccddeeff"/>
              <Representation id="high" bandwidth="1000000"><SegmentTemplate timescale="1"
                duration="2" startNumber="1" initialization="$prefix-init.mp4"
                media="$prefix-${'$'}Number${'$'}.m4s"/></Representation>
            </AdaptationSet></Period>
        """.trimIndent()
        fun document(periods: String) = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013"
              type="dynamic" availabilityStartTime="2026-01-01T00:00:00Z"
              timeShiftBufferDepth="PT60S" minimumUpdatePeriod="PT5S" minBufferTime="PT1S">
              <BaseURL>$root/declared/news/</BaseURL>$periods
            </MPD>
        """.trimIndent()
        val open = document(period("PT0S", "old"))
        val closed = document(period("PT0S", "old") + period("PT10H", "new") + period("PT10H2M20S", "future"))
        val raw = DashManifestParser().parse(Uri.parse(manifest.toString()), closed.byteInputStream())
        val oldIndex = raw.getPeriod(0).adaptationSets.single().representations.single().index!!
        val oldDuration = raw.getPeriodDurationUs(0)
        assertEquals(18000L, oldIndex.getAvailableSegmentCount(oldDuration, nowMs * 1000))
        assertThrows(IOException::class.java) {
            declaredDashSegmentNumbers(oldIndex.getFirstAvailableSegmentNum(oldDuration, nowMs * 1000),
                oldIndex.getAvailableSegmentCount(oldDuration, (nowMs + 120_000) * 1000))
        }
        val policy = policy()
        val counts = mutableListOf<Int>()
        val bounded = BoundedDashManifestParser(manifest, policy, { parseAbemaRequestInitialization(it) != null },
            { true }, { event, count -> if (event == DashManifestPolicyEvent.DECLARED_PATHS) counts.add(count) },
            wallMs = { nowMs })
        bounded.parse(Uri.parse(manifest.toString()), open.byteInputStream())
        bounded.parse(Uri.parse(manifest.toString()), closed.byteInputStream())
        assertEquals(listOf(91, 93), counts) // 90 segments, plus one/three initialization files.
        for (number in 17976..18000) assertTrue(policy.allows(URI("$root/declared/news/old-$number.m4s")))
        for (number in 1..65) assertTrue(policy.allows(URI("$root/declared/news/new-$number.m4s")))
        assertFalse(policy.allows(URI("$root/declared/news/old-1.m4s")))
        assertFalse(policy.allows(URI("$root/declared/news/new-66.m4s")))
        assertFalse(policy.allows(URI("$root/declared/news/future-1.m4s")))
    }

    @Test fun finiteTimelinesOutsideHistoryAndLookaheadAdmitNoSegments() {
        val origin = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        for ((elapsedMs, start) in listOf(10_000L to 3600, 3_600_000L to 0)) {
            val body = mpd(template(timeline = "<SegmentTimeline><S t=\"$start\" d=\"2\" r=\"2\"/></SegmentTimeline>"))
                .replace("mediaPresentationDuration=\"PT6S\"", "")
                .replace("type=\"static\"", """type="dynamic" availabilityStartTime="2026-01-01T00:00:00Z"
                    timeShiftBufferDepth="PT60S"""")
            val policy = policy()
            val counts = mutableListOf<Int>()
            BoundedDashManifestParser(manifest, policy, { parseAbemaRequestInitialization(it) != null }, { true },
                { event, count -> if (event == DashManifestPolicyEvent.DECLARED_PATHS) counts.add(count) },
                wallMs = { origin + elapsedMs }).parse(Uri.parse(manifest.toString()), body.byteInputStream())
            assertEquals(listOf(1), counts) // Only the exact initialization file remains.
            for (number in 1..3) assertFalse(policy.allows(URI("$root/channel/abema-news/segment-00$number.m4s")))
        }
    }

    @Test fun transitionComparisonPreservesPeriodsAndExactClearPaths() {
        val transition = AbemaManifestTransitionPolicy(listOf(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")))
        val policy = policy()
        val modelShapes = mutableListOf<Boolean>()
        val bounded = BoundedDashManifestParser(manifest, policy, transition::accepts, { true }, { _, _ -> },
            validateModel = { parsed ->
                val hasDrm = (0 until parsed.periodCount).any { index ->
                    parsed.getPeriod(index).adaptationSets.any { it.representations.any { representation -> representation.format.drmInitData != null } }
                }
                modelShapes.add(hasDrm)
                !transition.requiresDeclarationFreeModel || !hasDrm
            })
        val protected = mpd(rootBase = "<BaseURL>$root/declared/news/</BaseURL>")
        val clear = protected.replace(Regex("<ContentProtection[^>]*/>"), "")
            .replace("init-", "clear-init-").replace("segment-", "clear-segment-")
        for (body in listOf(protected, clear, protected)) {
            val parsed = bounded.parse(Uri.parse(manifest.toString()), body.byteInputStream())
            assertEquals(1, parsed.periodCount)
        }
        assertEquals(listOf(true, false, true), modelShapes)
        assertTrue(policy.allows(URI("$root/declared/news/clear-segment-001.m4s")))
        assertFalse(policy.allows(URI("$root/declared/news/clear-segment-004.m4s")))
    }

    @Test fun modelPolicyRefusalCannotPublishNewPaths() {
        val policy = policy()
        val stages = mutableListOf<DashManifestPolicyStage>()
        val bounded = BoundedDashManifestParser(manifest, policy, { parseAbemaRequestInitialization(it) != null },
            { true }, { _, _ -> }, onRefusal = stages::add, validateModel = { false })
        assertThrows(ParserException::class.java) {
            bounded.parse(Uri.parse(manifest.toString()), mpd().byteInputStream())
        }
        assertEquals(listOf(DashManifestPolicyStage.MODEL_POLICY), stages)
        assertFalse(policy.allows(URI("$root/channel/abema-news/init-high.mp4")))
    }
}
