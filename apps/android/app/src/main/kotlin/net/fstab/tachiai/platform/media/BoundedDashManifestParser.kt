package net.fstab.tachiai.platform.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.dash.manifest.AdaptationSet
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.dash.manifest.UrlTemplate
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

// #568: how far beyond the provider's window a live rewind buffer may reach.
internal const val RETAINED_WINDOW_MS = 150_000L
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
    // #568: a live feed's in-memory rewind buffer. Segments and periods the
    // provider drops stay in this normalized manifest while they are cached.
    private val liveCache: NativeLiveSegmentCache? = null,
) : ParsingLoadable.Parser<DashManifest> {
    // Timeline entries seen in earlier refreshes, per period/adaptation/timescale.
    private val retainedTimelines = HashMap<String, java.util.TreeMap<Long, Long>>()
    private var lastPeriods: List<Period> = emptyList()

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
            val delegate = object : NullableTemplateDashManifestParser() {
                private var timelineElements = 0
                private var baseCalls = 0
                private val elementTimes = java.util.IdentityHashMap<SegmentBase.SegmentTimelineElement, LongArray>()
                private var periodKey: String? = null
                private var adaptationIndex = -1
                override fun buildSegmentTimelineElement(startTime: Long, duration: Long): SegmentBase.SegmentTimelineElement {
                    require(++timelineElements <= 32768 && duration > 0 && canRun())
                    return super.buildSegmentTimelineElement(startTime, duration)
                        .also { elementTimes[it] = longArrayOf(startTime, duration) }
                }
                override fun parsePeriod(xpp: XmlPullParser, parentBaseUrls: List<BaseUrl>, defaultStartMs: Long,
                    baseUrlAvailabilityTimeOffsetUs: Long, availabilityStartTimeMs: Long, timeShiftBufferDepthMs: Long,
                    dvbProfileDeclared: Boolean): android.util.Pair<Period, Long> {
                    periodKey = xpp.getAttributeValue(null, "id")?.takeIf { it.length in 1..256 }
                    adaptationIndex = -1
                    try { return super.parsePeriod(xpp, parentBaseUrls, defaultStartMs, baseUrlAvailabilityTimeOffsetUs,
                        availabilityStartTimeMs, timeShiftBufferDepthMs, dvbProfileDeclared) }
                    finally { periodKey = null }
                }
                override fun parseAdaptationSet(xpp: XmlPullParser, parentBaseUrls: List<BaseUrl>, segmentBase: SegmentBase?,
                    periodDurationMs: Long, baseUrlAvailabilityTimeOffsetUs: Long, segmentBaseAvailabilityTimeOffsetUs: Long,
                    periodStartUnixTimeMs: Long, timeShiftBufferDepthMs: Long, dvbProfileDeclared: Boolean): AdaptationSet {
                    adaptationIndex++
                    return super.parseAdaptationSet(xpp, parentBaseUrls, segmentBase, periodDurationMs,
                        baseUrlAvailabilityTimeOffsetUs, segmentBaseAvailabilityTimeOffsetUs, periodStartUnixTimeMs,
                        timeShiftBufferDepthMs, dvbProfileDeclared)
                }
                override fun buildTemplate(initialization: RangedUri?, timescale: Long, presentationTimeOffset: Long,
                    startNumber: Long, endNumber: Long, duration: Long, timeline: List<SegmentBase.SegmentTimelineElement>?,
                    availabilityTimeOffsetUs: Long, initializationTemplate: UrlTemplate?, mediaTemplate: UrlTemplate?,
                    timeShiftBufferDepthMs: Long, periodStartUnixTimeMs: Long): SegmentBase.SegmentTemplate {
                    val key = periodKey
                    val cache = liveCache
                    var merged = timeline
                    var start = startNumber
                    if (cache != null && key != null && !timeline.isNullOrEmpty() && timescale > 0 && mediaTemplate != null) {
                        val times = timeline.map { checkNotNull(elementTimes[it]) }
                        val retained = retainedTimelines.getOrPut("$key|$adaptationIndex|$timescale|$presentationTimeOffset") {
                            java.util.TreeMap() }
                        times.forEach { retained[it[0]] = it[1] }
                        val cached = cache.cachedStartsUs(key, adaptationIndex)
                        val prepend = ArrayDeque<LongArray>()
                        var expect = times.first()[0]
                        for (entry in retained.headMap(times.first()[0], false).descendingMap().entries) {
                            val time = entry.key; val length = entry.value
                            if (Math.addExact(time, length) != expect) break
                            val us = Util.scaleLargeTimestamp(time - presentationTimeOffset, C.MICROS_PER_SECOND, timescale)
                            if (cached.none { kotlin.math.abs(it - us) <= 1_000 }) break
                            prepend.addFirst(longArrayOf(time, length)); expect = time
                        }
                        // Keep the history bounded to what can still be retained.
                        while (retained.size > 1024 || (retained.isNotEmpty() && retained.firstKey() < expect &&
                            retained.size > times.size + prepend.size + 64)) retained.pollFirstEntry()
                        // Renumber only when segment URLs do not depend on $Number$.
                        val numbered = mediaTemplate.buildUri("r", 1, 0, 0) != mediaTemplate.buildUri("r", 2, 0, 0)
                        val count = if (numbered) minOf(prepend.size.toLong(), maxOf(0L, startNumber)).toInt() else prepend.size
                        if (count > 0) {
                            merged = prepend.toList().takeLast(count).map { buildSegmentTimelineElement(it[0], it[1]) } + timeline
                            if (numbered) start = startNumber - count
                        }
                    }
                    return defaultTemplate(initialization, timescale, presentationTimeOffset, start, endNumber,
                        duration, merged, availabilityTimeOffsetUs, initializationTemplate, mediaTemplate,
                        if (cache != null && timeShiftBufferDepthMs > 0) timeShiftBufferDepthMs + RETAINED_WINDOW_MS
                        else timeShiftBufferDepthMs, periodStartUnixTimeMs)
                }
                override fun parseBaseUrl(parser: XmlPullParser, parents: List<BaseUrl>, dvb: Boolean): List<BaseUrl> {
                    require(++baseCalls <= 64 && parents.size <= 16 && canRun())
                    return super.parseBaseUrl(parser, parents, dvb).also { require(it.size <= 16) }
                }
            }
            val parsed = delegate.parse(uri, ByteArrayInputStream(bytes))
            stage = DashManifestPolicyStage.MODEL_COUNTS
            require(parsed.periodCount in 1..16 && parsed.locations.all { URI(it.url.toString()) == manifestUri })
            val serverPeriods = (0 until parsed.periodCount).map(parsed::getPeriod)
            // Periods the provider dropped stay while everything needed from them is cached.
            val keptPeriods = if (liveCache != null && parsed.dynamic) lastPeriods.filter { old ->
                old.id != null && serverPeriods.none { it.id == old.id } && old.startMs < serverPeriods.first().startMs &&
                    liveCache.periodRetainable(checkNotNull(old.id))
            } else emptyList()
            val periods = (keptPeriods + serverPeriods).takeLast(16)
            // Retained periods were bounded when the provider declared them.
            val firstServerPeriod = periods.size - serverPeriods.size
            val windowMs = if (liveCache != null && parsed.dynamic && parsed.timeShiftBufferDepthMs > 0)
                parsed.timeShiftBufferDepthMs + RETAINED_WINDOW_MS else parsed.timeShiftBufferDepthMs
            val paths = linkedSetOf<URI>()
            val cacheSegments = HashMap<URI, NativeLiveSegmentCache.Segment>()
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
            // Media3 may otherwise perform SNTP outside the HTTP datasource.
            // This debug test explicitly assumes the phone's UTC clock is valid.
            val timing = UtcTimingElement("urn:mpeg:dash:utc:direct:2014", Instant.ofEpochMilli(nowMs).toString())
            val normalized = DashManifest(parsed.availabilityStartTimeMs, parsed.durationMs, parsed.minBufferTimeMs,
                parsed.dynamic, parsed.minUpdatePeriodMs, windowMs,
                parsed.suggestedPresentationDelayMs, parsed.publishTimeMs, parsed.programInformation,
                timing, parsed.serviceDescription, periods, parsed.locations)
            var representations = 0
            for (periodIndex in 0 until normalized.periodCount) {
                val period = normalized.getPeriod(periodIndex)
                val durationUs = normalized.getPeriodDurationUs(periodIndex)
                for ((adaptationIndex, adaptation) in period.adaptationSets.withIndex()) for (representation in adaptation.representations) {
                    stage = DashManifestPolicyStage.REPRESENTATIONS
                    if (periodIndex >= firstServerPeriod) require(++representations <= 64)
                    require(representation.baseUrls.size in 1..16)
                    stage = DashManifestPolicyStage.SEGMENT_INDEX
                    val index = representation.index
                    val numbers = if (index == null) {
                        require(representation.indexUri != null)
                        LongRange.EMPTY
                    } else {
                        stage = DashManifestPolicyStage.SEGMENT_RANGE
                        segmentNumbers(index, durationUs, normalized, period.startMs, nowUs, endUs)
                    }
                    for (base in representation.baseUrls) {
                        add(representation.initializationUri, base.url)
                        add(representation.indexUri, base.url)
                        for (number in numbers) add(checkNotNull(index).getSegmentUrl(number), base.url)
                    }
                    if (liveCache != null && parsed.dynamic && index != null && parsed.availabilityStartTimeMs >= 0) {
                        val periodStartMs = Math.addExact(parsed.availabilityStartTimeMs, period.startMs)
                        val key = period.id ?: "start:${period.startMs}"
                        fun segment(startUs: Long, endUs: Long, initialization: Boolean) = NativeLiveSegmentCache.Segment(
                            key, adaptationIndex, adaptation.type, representation.format.id ?: "", representation.format.bitrate,
                            startUs, periodStartMs + startUs / 1000, periodStartMs + endUs / 1000, initialization)
                        for (base in representation.baseUrls) {
                            representation.initializationUri?.let { cacheSegments[URI(it.resolveUriString(base.url))] = segment(0, 0, true) }
                            for (number in numbers) {
                                val startUs = index.getTimeUs(number)
                                cacheSegments[URI(index.getSegmentUrl(number).resolveUriString(base.url))] =
                                    segment(startUs, Math.addExact(startUs, index.getDurationUs(number, durationUs)), false)
                            }
                        }
                    }
                }
            }
            stage = DashManifestPolicyStage.MODEL_POLICY
            require(validateModel(parsed)) // Before publishing any newly declared files.
            require(canRun())
            stage = DashManifestPolicyStage.PUBLISH
            policy.publish(paths)
            if (liveCache != null && parsed.dynamic) {
                liveCache.publish(cacheSegments, parsed.timeShiftBufferDepthMs)
                lastPeriods = periods
            }
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
