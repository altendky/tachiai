package net.fstab.tachiai.provider.abema

import java.io.StringReader
import java.nio.ByteBuffer
import java.util.UUID
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

private const val MPD_NAMESPACE = "urn:mpeg:dash:schema:mpd:2011"
private const val CENC_NAMESPACE = "urn:mpeg:cenc:2013"
private const val COMMON_SCHEME = "urn:mpeg:dash:mp4protection:2011"
private const val MAX_MPD_BYTES = 256 * 1024
private const val MAX_ELEMENTS = 8192
private const val MAX_DEPTH = 32
private const val MAX_KIDS = 16
private val ZERO_UUID = UUID(0, 0)
private val CANONICAL_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val DECLARATION = Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)

// Opaque transient initialization only. Never print this object or its fields.
// The explicit diagnostic selects ClearKey; these IDs do not identify a DRM system.
internal class AbemaRequestInitialization internal constructor(kids: List<UUID>) {
    val kids: List<UUID> = java.util.Collections.unmodifiableList(ArrayList(kids))
}

internal enum class AbemaInitializationReason {
    ACCEPTED, NO_DEFAULT_ID, DECLARATION, BYTE_LIMIT, ELEMENT_LIMIT, DEPTH_LIMIT,
    ROOT_OR_HIERARCHY, PROTECTION_NAMESPACE_OR_SCOPE, SCHEME_OR_CIPHER,
    ID_FORMAT, ZERO_ID, SAME_SCOPE_CONFLICT, ID_LIMIT, XML_MALFORMED,
    PARSER_SETUP_FAILED, PARSER_FAILED,
}

// Only closed categories and saturated structural counts from the visited prefix.
// Never attach initialization objects, identifiers, attributes or exception text.
internal data class AbemaInitializationDiagnostic(
    val reason: AbemaInitializationReason,
    val periods: Int = 0,
    val adaptationSets: Int = 0,
    val protections: Int = 0,
    val defaultIdDeclarations: Int = 0,
    val cencProtections: Int = 0,
    val cbcsProtections: Int = 0,
    val otherProtections: Int = 0,
) {
    fun closedSummary() = "reason=${reason.name} periods=$periods adaptations=$adaptationSets " +
        "protections=$protections defaultIdDeclarations=$defaultIdDeclarations " +
        "cenc=$cencProtections cbcs=$cbcsProtections other=$otherProtections"
}

internal fun parseAbemaRequestInitialization(
    mpd: String,
    onDiagnostic: ((AbemaInitializationDiagnostic) -> Unit)? = null,
): AbemaRequestInitialization? {
    fun publish(value: AbemaInitializationDiagnostic) {
        // Diagnostic failures cannot change initialization acceptance.
        try { onDiagnostic?.invoke(value) } catch (_: Exception) { }
    }
    // Reject declarations before parsing, including on Android SAX implementations
    // that do not support Xerces-specific DOCTYPE feature switches. No DTD or
    // declared entity may reach the parser; predefined XML escapes remain valid.
    if (mpd.length > MAX_MPD_BYTES || mpd.toByteArray(Charsets.UTF_8).size > MAX_MPD_BYTES) {
        publish(AbemaInitializationDiagnostic(AbemaInitializationReason.BYTE_LIMIT))
        return null
    }
    if (DECLARATION.containsMatchIn(mpd)) {
        publish(AbemaInitializationDiagnostic(AbemaInitializationReason.DECLARATION))
        return null
    }
    val handler = InitializationHandler()
    var parserReady = false
    var reason = AbemaInitializationReason.ACCEPTED
    val result = try {
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        for (feature in listOf("http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd")) {
            try { reader.setFeature(feature, false) } catch (_: SAXException) { /* Preflight still rejects all declarations. */ }
        }
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.entityResolver = handler
        parserReady = true
        reader.parse(InputSource(StringReader(mpd)))
        handler.result().also { if (it == null) reason = AbemaInitializationReason.NO_DEFAULT_ID }
    } catch (_: Exception) {
        reason = handler.refusal ?: if (parserReady) AbemaInitializationReason.PARSER_FAILED
            else AbemaInitializationReason.PARSER_SETUP_FAILED
        null
    }
    publish(handler.diagnostic(reason))
    return result
}

private class InitializationHandler : DefaultHandler() {
    private class Frame(val uri: String, val name: String) {
        var declaredKids: Set<UUID>? = null
    }
    private val frames = ArrayDeque<Frame>()
    private val kids = linkedSetOf<UUID>()
    private var elements = 0
    private var periods = 0
    private var adaptationSets = 0
    private var protections = 0
    private var defaultIdDeclarations = 0
    private var cencProtections = 0
    private var cbcsProtections = 0
    private var otherProtections = 0
    var refusal: AbemaInitializationReason? = null
        private set

    private fun reject(reason: AbemaInitializationReason): Nothing {
        if (refusal == null) refusal = reason
        throw SAXException("Unsupported initialization")
    }

    fun diagnostic(reason: AbemaInitializationReason) = AbemaInitializationDiagnostic(
        reason, periods, adaptationSets, protections, defaultIdDeclarations,
        cencProtections, cbcsProtections, otherProtections,
    )

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        elements += 1
        if (elements > MAX_ELEMENTS) reject(AbemaInitializationReason.ELEMENT_LIMIT)
        if (frames.size >= MAX_DEPTH) reject(AbemaInitializationReason.DEPTH_LIMIT)
        if (uri == MPD_NAMESPACE) when (localName) {
            "Period" -> periods += 1
            "AdaptationSet" -> adaptationSets += 1
            "ContentProtection" -> {
                protections += 1
                if (attributes.getValue("", "schemeIdUri") == COMMON_SCHEME) when (attributes.getValue("", "value")) {
                    "cenc" -> cencProtections += 1
                    "cbcs" -> cbcsProtections += 1
                    else -> otherProtections += 1
                } else otherProtections += 1
            }
        }
        val parent = frames.lastOrNull()
        if (parent == null) {
            if (uri != MPD_NAMESPACE || localName != "MPD") reject(AbemaInitializationReason.ROOT_OR_HIERARCHY)
        } else if (uri == MPD_NAMESPACE) {
            when (localName) {
                "MPD" -> reject(AbemaInitializationReason.ROOT_OR_HIERARCHY)
                "Period" -> if (parent.uri != MPD_NAMESPACE || parent.name != "MPD") reject(AbemaInitializationReason.ROOT_OR_HIERARCHY)
                "AdaptationSet" -> if (parent.uri != MPD_NAMESPACE || parent.name != "Period") reject(AbemaInitializationReason.ROOT_OR_HIERARCHY)
                "Representation" -> if (parent.uri != MPD_NAMESPACE || parent.name != "AdaptationSet") reject(AbemaInitializationReason.ROOT_OR_HIERARCHY)
                "ContentProtection" -> if (parent.uri != MPD_NAMESPACE ||
                    parent.name !in listOf("AdaptationSet", "Representation")) reject(AbemaInitializationReason.PROTECTION_NAMESPACE_OR_SCOPE)
            }
        }
        val kidAttributes = (0 until attributes.length).filter { attributes.getLocalName(it) == "default_KID" }
        if (kidAttributes.isNotEmpty()) {
            defaultIdDeclarations = (defaultIdDeclarations + kidAttributes.size).coerceAtMost(MAX_ELEMENTS)
            if (kidAttributes.size != 1 || attributes.getURI(kidAttributes.single()) != CENC_NAMESPACE ||
                uri != MPD_NAMESPACE || localName != "ContentProtection" || parent == null ||
                parent.uri != MPD_NAMESPACE || parent.name !in listOf("AdaptationSet", "Representation"))
                reject(AbemaInitializationReason.PROTECTION_NAMESPACE_OR_SCOPE)
            if (attributes.getValue("", "schemeIdUri") != COMMON_SCHEME || attributes.getValue("", "value") != "cenc")
                reject(AbemaInitializationReason.SCHEME_OR_CIPHER)
            val raw = attributes.getValue(kidAttributes.single()).trim()
            val values = raw.split(Regex("\\s+"))
            // Cap even repeated inputs before allocating/parsing identifier objects.
            if (values.size !in 1..MAX_KIDS) reject(AbemaInitializationReason.ID_LIMIT)
            if (values.any { !CANONICAL_UUID.matches(it) }) reject(AbemaInitializationReason.ID_FORMAT)
            val declared = values.map { UUID.fromString(it) }.toSet()
            if (ZERO_UUID in declared) reject(AbemaInitializationReason.ZERO_ID)
            if (parent.declaredKids != null && parent.declaredKids != declared) reject(AbemaInitializationReason.SAME_SCOPE_CONFLICT)
            parent.declaredKids = declared
            kids.addAll(declared)
            if (kids.size > MAX_KIDS) reject(AbemaInitializationReason.ID_LIMIT)
        }
        frames.addLast(Frame(uri, localName))
    }

    override fun endElement(uri: String, localName: String, qName: String) { frames.removeLast() }
    override fun resolveEntity(publicId: String?, systemId: String?): InputSource = reject(AbemaInitializationReason.DECLARATION)
    override fun error(error: SAXParseException) = reject(AbemaInitializationReason.XML_MALFORMED)
    override fun fatalError(error: SAXParseException) = reject(AbemaInitializationReason.XML_MALFORMED)

    fun result(): AbemaRequestInitialization? =
        if (frames.isEmpty() && kids.isNotEmpty()) AbemaRequestInitialization(kids.toList()) else null
}

// Standard ISO BMFF version-1 common-system PSSH with zero opaque data bytes.
// No provider PSSH, license field, response transformation or proprietary algorithm.
internal fun commonPssh(kids: List<UUID>): ByteArray {
    require(kids.size in 1..MAX_KIDS && kids.toSet().size == kids.size && ZERO_UUID !in kids)
    val commonSystem = UUID.fromString("1077efec-c0b2-4d02-ace3-3c1e52e2fb4b")
    return ByteBuffer.allocate(36 + 16 * kids.size).apply {
        putInt(capacity())
        putInt(0x70737368) // pssh
        putInt(0x01000000) // version 1, flags 0
        putLong(commonSystem.mostSignificantBits)
        putLong(commonSystem.leastSignificantBits)
        putInt(kids.size)
        kids.forEach { putLong(it.mostSignificantBits); putLong(it.leastSignificantBits) }
        putInt(0)
    }.array()
}
