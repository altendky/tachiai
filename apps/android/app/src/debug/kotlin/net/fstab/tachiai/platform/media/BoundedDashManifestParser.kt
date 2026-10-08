package net.fstab.tachiai.platform.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.BaseUrl
import androidx.media3.exoplayer.dash.DashSegmentIndex
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.RangedUri
import androidx.media3.exoplayer.dash.manifest.SegmentBase
import androidx.media3.exoplayer.dash.manifest.UtcTimingElement
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.time.Instant
import org.xmlpull.v1.XmlPullParser

internal enum class DashManifestPolicyEvent { DECLARED_PATHS, REFUSED }
internal enum class DashManifestPolicyStage {
    INPUT, PREFLIGHT, SDK_PARSE, MODEL_COUNTS, REPRESENTATIONS, SEGMENT_INDEX, SEGMENT_RANGE, RANGED_URI, MODEL_POLICY, PUBLISH,
}

// Delegate DASH inheritance/template semantics to the same SDK as playback.
// Preflight is provider supplied; it must reject DTDs and bound XML structure.
@UnstableApi
internal class BoundedDashManifestParser(
    private val manifestUri: URI,
    private val policy: DeclaredDashMediaPolicy,
    private val preflight: (String) -> Boolean,
    private val canRun: () -> Boolean,
    private val onEvent: (DashManifestPolicyEvent, Int) -> Unit,
    private val onRefusal: (DashManifestPolicyStage) -> Unit = {},
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val validateModel: (DashManifest) -> Boolean = { true },
) : ParsingLoadable.Parser<DashManifest> {
    override fun parse(uri: Uri, input: InputStream): DashManifest {
        var stage = DashManifestPolicyStage.INPUT
        try {
            require(canRun() && URI(uri.toString()) == manifestUri)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                require(canRun())
                val size = input.read(buffer)
                if (size < 0) break
                require(output.size() + size <= 256 * 1024)
                output.write(buffer, 0, size)
            }
            val bytes = output.toByteArray()
            stage = DashManifestPolicyStage.PREFLIGHT
            require(preflight(bytes.toString(Charsets.UTF_8)))
            stage = DashManifestPolicyStage.SDK_PARSE
            val delegate = object : DashManifestParser() {
                private var timelineElements = 0
                private var baseCalls = 0
                override fun buildSegmentTimelineElement(startTime: Long, duration: Long): SegmentBase.SegmentTimelineElement {
                    require(++timelineElements <= 32768 && duration > 0 && canRun())
                    return super.buildSegmentTimelineElement(startTime, duration)
                }
                override fun parseBaseUrl(parser: XmlPullParser, parents: List<BaseUrl>, dvb: Boolean): List<BaseUrl> {
                    require(++baseCalls <= 64 && parents.size <= 16 && canRun())
                    return super.parseBaseUrl(parser, parents, dvb).also { require(it.size <= 16) }
                }
            }
            val parsed = delegate.parse(uri, ByteArrayInputStream(bytes))
            stage = DashManifestPolicyStage.MODEL_COUNTS
            require(parsed.periodCount in 1..16 && parsed.locations.all { URI(it.url.toString()) == manifestUri })
            val paths = linkedSetOf<URI>()
            var generated = 0
            fun add(reference: RangedUri?, base: String) {
                if (reference == null) return
                stage = DashManifestPolicyStage.RANGED_URI
                require(canRun() && ++generated <= MAX_DECLARED_DASH_URIS * 2 && paths.size < MAX_DECLARED_DASH_URIS)
                paths.add(URI(reference.resolveUriString(base)))
            }
            val nowMs = wallMs()
            val nowUs = Math.multiplyExact(nowMs, 1000)
            val endUs = Math.addExact(nowUs, 120_000_000)
            var representations = 0
            for (periodIndex in 0 until parsed.periodCount) {
                val period = parsed.getPeriod(periodIndex)
                val durationUs = parsed.getPeriodDurationUs(periodIndex)
                for (adaptation in period.adaptationSets) for (representation in adaptation.representations) {
                    stage = DashManifestPolicyStage.REPRESENTATIONS
                    require(++representations <= 64 && representation.baseUrls.size in 1..16)
                    stage = DashManifestPolicyStage.SEGMENT_INDEX
                    val index = representation.index
                    val numbers = if (index == null) {
                        require(representation.indexUri != null)
                        LongRange.EMPTY
                    } else {
                        stage = DashManifestPolicyStage.SEGMENT_RANGE
                        segmentNumbers(index, durationUs, parsed, period.startMs, nowUs, endUs)
                    }
                    for (base in representation.baseUrls) {
                        add(representation.initializationUri, base.url)
                        add(representation.indexUri, base.url)
                        for (number in numbers) add(checkNotNull(index).getSegmentUrl(number), base.url)
                    }
                }
            }
            stage = DashManifestPolicyStage.MODEL_POLICY
            require(validateModel(parsed)) // Before publishing any newly declared files.
            // Media3 otherwise may perform SNTP outside the HTTP datasource.
            // This debug test explicitly assumes the phone's UTC clock is valid.
            val timing = UtcTimingElement("urn:mpeg:dash:utc:direct:2014", Instant.ofEpochMilli(nowMs).toString())
            val normalized = DashManifest(parsed.availabilityStartTimeMs, parsed.durationMs, parsed.minBufferTimeMs,
                parsed.dynamic, parsed.minUpdatePeriodMs, parsed.timeShiftBufferDepthMs,
                parsed.suggestedPresentationDelayMs, parsed.publishTimeMs, parsed.programInformation,
                timing, parsed.serviceDescription, (0 until parsed.periodCount).map(parsed::getPeriod), parsed.locations)
            require(canRun())
            stage = DashManifestPolicyStage.PUBLISH
            policy.publish(paths)
            onEvent(DashManifestPolicyEvent.DECLARED_PATHS, paths.size)
            return normalized
        } catch (_: Exception) {
            onRefusal(stage)
            onEvent(DashManifestPolicyEvent.REFUSED, 0)
            // A generic IOException is retryable even with minimum retry count
            // zero. Policy refusal is terminal, not a transient network error.
            throw ParserException.createForManifestWithUnsupportedFeature("Native manifest policy refused", null)
        }
    }

    private fun segmentNumbers(index: DashSegmentIndex, durationUs: Long, manifest: DashManifest,
        periodStartMs: Long, nowUs: Long, endUs: Long): LongRange {
        require(durationUs == C.TIME_UNSET || durationUs >= 0)
        val count = index.getSegmentCount(durationUs)
        // Finite indexes return their whole history. A closed long live period
        // must not turn a short live-window admission into thousands of URLs.
        if (manifest.dynamic && count >= 0 && manifest.availabilityStartTimeMs >= 0 &&
            manifest.timeShiftBufferDepthMs > 0) {
            val origin = Math.multiplyExact(Math.addExact(manifest.availabilityStartTimeMs, periodStartMs), 1000)
            val lower = maxOf(0L, Math.subtractExact(Math.subtractExact(nowUs, origin),
                Math.multiplyExact(manifest.timeShiftBufferDepthMs, 1000)))
            val upper = Math.subtractExact(endUs, origin)
            if (count == 0L || upper <= lower || (durationUs != C.TIME_UNSET && lower >= durationUs))
                return LongRange.EMPTY
            var first = maxOf(index.firstSegmentNum, index.getSegmentNum(lower, durationUs))
            var endExclusive = minOf(Math.addExact(index.firstSegmentNum, count),
                Math.addExact(index.getSegmentNum(Math.subtractExact(upper, 1), durationUs), 1))
            // getSegmentNum clamps at explicit timeline ends, even when that
            // entire timeline is outside the requested temporal interval.
            if (first < endExclusive) {
                val segmentDuration = index.getDurationUs(first, durationUs)
                require(segmentDuration > 0)
                if (Math.addExact(index.getTimeUs(first), segmentDuration) <= lower) first = Math.addExact(first, 1)
                if (first < endExclusive && index.getTimeUs(endExclusive - 1) >= upper) endExclusive--
            }
            return declaredDashSegmentNumbers(first, maxOf(0L, Math.subtractExact(endExclusive, first)))
        }
        val first = index.getFirstAvailableSegmentNum(durationUs, nowUs)
        val futureFirst = index.getFirstAvailableSegmentNum(durationUs, endUs)
        val futureCount = index.getAvailableSegmentCount(durationUs, endUs)
        val endExclusive = Math.addExact(futureFirst, futureCount)
        return declaredDashSegmentNumbers(first, Math.subtractExact(endExclusive, first))
    }
}
