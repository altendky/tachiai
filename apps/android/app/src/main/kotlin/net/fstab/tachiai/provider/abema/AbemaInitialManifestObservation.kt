package net.fstab.tachiai.provider.abema

import java.io.StringReader
import java.time.Duration
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

internal enum class AbemaInitialManifestReason {
    UNOBSERVED, OBSERVED, CHANNEL_MISMATCH, REPEATED_CAPTURE, BYTE_LIMIT, DECLARATION,
    ELEMENT_LIMIT, DEPTH_LIMIT, ATTRIBUTE_LIMIT, DUPLICATE_ATTRIBUTE, ROOT_OR_HIERARCHY,
    XML_MALFORMED, PARSER_FAILED,
}

// Shape observations are not occurrence coverage, entitlement or playback admission.
// Identifiers, XML, attribute values, durations, URIs and initialization never leave the parser.
internal data class AbemaInitialManifestSnapshot(
    val reason: AbemaInitialManifestReason,
    val channelMatches: Boolean = false,
    val periods: Int = 0,
    val explicitStarts: Int = 0,
    val validFiniteStarts: Int = 0,
    val explicitDurations: Int = 0,
    val validFiniteDurations: Int = 0,
    val finiteExplicitIntervals: Int = 0,
    val eventStreams: Int = 0,
    val inbandEventStreams: Int = 0,
    val periodIds: Int = 0,
    val matchingPeriodIds: Int = 0,
    val slotIdAttributes: Int = 0,
    val matchingSlotIdAttributes: Int = 0,
) {
    fun closedSummary() = "manifest=${reason.name} channelMatches=$channelMatches periods=$periods " +
        "explicitStarts=$explicitStarts validFiniteStarts=$validFiniteStarts " +
        "explicitDurations=$explicitDurations validFiniteDurations=$validFiniteDurations " +
        "finiteExplicitIntervals=$finiteExplicitIntervals eventStreams=$eventStreams " +
        "inbandEventStreams=$inbandEventStreams periodIds=$periodIds matchingPeriodIds=$matchingPeriodIds " +
        "slotIdAttributes=$slotIdAttributes matchingSlotIdAttributes=$matchingSlotIdAttributes"
    override fun toString() = closedSummary()
}

internal class AbemaInitialManifestObservation(private val expected: AbemaSlotRequest) {
    private var captured = false
    private var value = AbemaInitialManifestSnapshot(AbemaInitialManifestReason.UNOBSERVED)
    val snapshot: AbemaInitialManifestSnapshot @Synchronized get() = value

    @Synchronized fun capture(actualChannelId: String, mpd: String) {
        if (captured) {
            value = AbemaInitialManifestSnapshot(AbemaInitialManifestReason.REPEATED_CAPTURE)
            return
        }
        captured = true
        if (actualChannelId != expected.channelId) {
            value = AbemaInitialManifestSnapshot(AbemaInitialManifestReason.CHANNEL_MISMATCH)
            return
        }
        fun refused(reason: AbemaInitialManifestReason) = AbemaInitialManifestSnapshot(reason, channelMatches = true)
        if (mpd.length > MAX_BYTES || mpd.toByteArray(Charsets.UTF_8).let {
            try { it.size > MAX_BYTES } finally { it.fill(0) }
        }) {
            value = refused(AbemaInitialManifestReason.BYTE_LIMIT); return
        }
        if (DECLARATION.containsMatchIn(mpd)) {
            value = refused(AbemaInitialManifestReason.DECLARATION); return
        }
        val handler = ShapeHandler(expected.slotId)
        value = try {
            val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
            for (feature in listOf("http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd")) {
                try { reader.setFeature(feature, false) } catch (_: SAXException) { /* Declaration preflight remains mandatory. */ }
            }
            reader.contentHandler = handler; reader.errorHandler = handler; reader.entityResolver = handler
            reader.parse(InputSource(StringReader(mpd)))
            handler.snapshot()
        } catch (_: Throwable) {
            // A refused/partial document never exports positive prefix observations.
            refused(handler.refusal ?: AbemaInitialManifestReason.PARSER_FAILED)
        }
    }

    override fun toString() = "AbemaInitialManifestObservation(redacted)"

    private class ShapeHandler(private val slotId: String) : DefaultHandler() {
        private data class Frame(val uri: String, val name: String)
        private val frames = ArrayDeque<Frame>()
        private var elements = 0
        private var periods = 0
        private var starts = 0
        private var validStarts = 0
        private var durations = 0
        private var validDurations = 0
        private var intervals = 0
        private var events = 0
        private var inband = 0
        private var ids = 0
        private var matchingIds = 0
        private var slotAttrs = 0
        private var matchingSlotAttrs = 0
        var refusal: AbemaInitialManifestReason? = null
            private set

        private fun reject(reason: AbemaInitialManifestReason): Nothing {
            if (refusal == null) refusal = reason
            throw SAXException("Initial manifest shape refused")
        }
        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            if (++elements > MAX_ELEMENTS) reject(AbemaInitialManifestReason.ELEMENT_LIMIT)
            if (frames.size >= MAX_DEPTH) reject(AbemaInitialManifestReason.DEPTH_LIMIT)
            if (attributes.length > MAX_ATTRIBUTES || (0 until attributes.length).any {
                attributes.getValue(it).length > MAX_ATTRIBUTE_CHARS
            }) reject(AbemaInitialManifestReason.ATTRIBUTE_LIMIT)
            val names = (0 until attributes.length).map { attributes.getURI(it) to attributes.getLocalName(it) }
            if (names.distinct().size != names.size) reject(AbemaInitialManifestReason.DUPLICATE_ATTRIBUTE)
            val parent = frames.lastOrNull()
            if (parent == null) {
                if (uri != MPD_NAMESPACE || localName != "MPD") reject(AbemaInitialManifestReason.ROOT_OR_HIERARCHY)
            } else if (uri == MPD_NAMESPACE && (localName == "MPD" ||
                localName == "Period" && (parent.uri != MPD_NAMESPACE || parent.name != "MPD")))
                reject(AbemaInitialManifestReason.ROOT_OR_HIERARCHY)
            if (uri == MPD_NAMESPACE) {
                attributes.getValue("", "slotId")?.let { slotAttrs++; if (it == slotId) matchingSlotAttrs++ }
                when (localName) {
                    "Period" -> {
                        periods++
                        val start = attributes.getValue("", "start")
                        val duration = attributes.getValue("", "duration")
                        val validStart = finiteDuration(start, positive = false)
                        val validDuration = finiteDuration(duration, positive = true)
                        if (start != null) starts++
                        if (validStart) validStarts++
                        if (duration != null) durations++
                        if (validDuration) validDurations++
                        if (validStart && validDuration && finiteInterval(checkNotNull(start), checkNotNull(duration))) intervals++
                        attributes.getValue("", "id")?.let { ids++; if (it == slotId) matchingIds++ }
                    }
                    "EventStream" -> events++
                    "InbandEventStream" -> inband++
                }
            }
            frames.addLast(Frame(uri, localName))
        }
        override fun endElement(uri: String, localName: String, qName: String) { frames.removeLast() }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = reject(AbemaInitialManifestReason.DECLARATION)
        override fun error(error: SAXParseException) = reject(AbemaInitialManifestReason.XML_MALFORMED)
        override fun fatalError(error: SAXParseException) = reject(AbemaInitialManifestReason.XML_MALFORMED)
        fun snapshot(): AbemaInitialManifestSnapshot {
            if (frames.isNotEmpty()) reject(AbemaInitialManifestReason.XML_MALFORMED)
            return AbemaInitialManifestSnapshot(AbemaInitialManifestReason.OBSERVED, true,
                periods, starts, validStarts, durations, validDurations, intervals, events, inband,
                ids, matchingIds, slotAttrs, matchingSlotAttrs)
        }
    }

    private companion object {
        const val MPD_NAMESPACE = "urn:mpeg:dash:schema:mpd:2011"
        const val MAX_BYTES = 256 * 1024
        const val MAX_ELEMENTS = 8192
        const val MAX_DEPTH = 32
        const val MAX_ATTRIBUTES = 64
        const val MAX_ATTRIBUTE_CHARS = 4096
        val DECLARATION = Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)
        val DURATION = Regex("P(?:(?:[0-9]+)D)?(?:T(?:(?:[0-9]+)H)?(?:(?:[0-9]+)M)?(?:(?:[0-9]+(?:\\.[0-9]{1,9})?)S)?)?")
        fun finiteDuration(raw: String?, positive: Boolean): Boolean {
            if (raw == null || !DURATION.matches(raw)) return false
            return try {
                val duration = Duration.parse(raw)
                duration.toNanos() // Reject arithmetic overflow; no approximate/infinite conversion.
                !duration.isNegative && (!positive || !duration.isZero)
            } catch (_: Exception) { false }
        }
        fun finiteInterval(start: String, duration: String): Boolean = try {
            Duration.parse(start).plus(Duration.parse(duration)).toNanos()
            true
        } catch (_: Exception) { false }
    }
}
