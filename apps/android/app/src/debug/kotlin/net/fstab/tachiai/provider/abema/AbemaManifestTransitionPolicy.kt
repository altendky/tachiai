package net.fstab.tachiai.provider.abema

import java.io.StringReader
import java.util.UUID
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

internal enum class AbemaManifestTransitionVerdict {
    INITIAL_PROTECTED, SAME_PROTECTION, NO_DECLARATIONS,
    XML_REFUSED, PROTECTION_REFUSED, ID_SET_CHANGED, PROTECTED_START_REQUIRED,
}

// Whole-document comparison only. No identifiers, signature values or XML leave
// this transient policy. Absence of declarations is not evidence of clear samples.
internal class AbemaManifestTransitionPolicy(expectedKids: List<UUID>) {
    private val expected = expectedKids.toSet()
    private var baseline: Set<ProtectionShape>? = null
    private var baselineMarkers: Set<ProtectionMarker> = emptySet()
    var verdict = AbemaManifestTransitionVerdict.PROTECTED_START_REQUIRED
        private set
    val requiresDeclarationFreeModel: Boolean
        get() = verdict == AbemaManifestTransitionVerdict.NO_DECLARATIONS

    init { require(expected.isNotEmpty() && expected.size <= 16 && UUID(0, 0) !in expected) }

    fun accepts(body: String): Boolean {
        var diagnostic: AbemaInitializationDiagnostic? = null
        val initialization = parseAbemaRequestInitialization(body) { diagnostic = it }
        if (diagnostic?.reason !in setOf(AbemaInitializationReason.ACCEPTED, AbemaInitializationReason.NO_DEFAULT_ID))
            return refuse(AbemaManifestTransitionVerdict.XML_REFUSED)
        // The first parse has already bounded bytes, elements/depth, declarations,
        // hierarchy and ID syntax. This second, non-expanding audit catches local-
        // name lookalikes that a downstream SDK could recognize differently.
        val audit = ProtectionAudit()
        try {
            val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
            for (feature in listOf("http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd")) {
                try { reader.setFeature(feature, false) } catch (_: SAXException) { }
            }
            reader.contentHandler = audit; reader.errorHandler = audit; reader.entityResolver = audit
            reader.parse(InputSource(StringReader(body)))
        } catch (_: Exception) { return refuse(AbemaManifestTransitionVerdict.PROTECTION_REFUSED) }

        if (initialization == null) {
            if (audit.hasProtectionMarker) return refuse(AbemaManifestTransitionVerdict.PROTECTION_REFUSED)
            if (baseline == null) return refuse(AbemaManifestTransitionVerdict.PROTECTED_START_REQUIRED)
            verdict = AbemaManifestTransitionVerdict.NO_DECLARATIONS
            return true
        }
        if (initialization.kids.toSet() != expected) return refuse(AbemaManifestTransitionVerdict.ID_SET_CHANGED)
        val original = baseline
        if (original != null && (!original.containsAll(audit.shapes) || !baselineMarkers.containsAll(audit.markers)))
            return refuse(AbemaManifestTransitionVerdict.PROTECTION_REFUSED)
        if (original == null) {
            baseline = audit.shapes.toSet()
            baselineMarkers = audit.markers.toSet()
        }
        verdict = if (original == null) AbemaManifestTransitionVerdict.INITIAL_PROTECTED
            else AbemaManifestTransitionVerdict.SAME_PROTECTION
        return true
    }

    private fun refuse(value: AbemaManifestTransitionVerdict): Boolean { verdict = value; return false }
}

// Shapes are baseline-known, not universally recognized DRM systems. No opaque
// payload, license URL or identifier is read. Scope and namespace remain bound.
private data class ProtectionShape(val namespace: String, val scope: String, val scheme: String, val value: String?)
private data class ProtectionMarker(val attribute: Boolean, val namespace: String, val name: String,
    val scopeNamespace: String, val scope: String)

private class ProtectionAudit : DefaultHandler() {
    private val frames = ArrayDeque<Pair<String, String>>()
    val shapes = linkedSetOf<ProtectionShape>()
    val markers = linkedSetOf<ProtectionMarker>()
    var hasProtectionMarker = false
        private set
    private fun reject(): Nothing = throw SAXException("Manifest protection audit refused")
    private fun marker(attribute: Boolean, namespace: String, name: String, scope: Pair<String, String>) {
        if (listOf(namespace, name, scope.first, scope.second).any { it.length > 256 }) reject()
        hasProtectionMarker = true
        markers.add(ProtectionMarker(attribute, namespace, name, scope.first, scope.second))
        if (markers.size > 64) reject()
    }

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        val mpd = "urn:mpeg:dash:schema:mpd:2011"
        val cenc = "urn:mpeg:cenc:2013"
        if (localName in setOf("MPD", "Period", "AdaptationSet", "Representation") && uri != mpd) reject()
        if (localName == "ContentProtection") {
            hasProtectionMarker = true
            val parent = frames.lastOrNull() ?: reject()
            if (uri != mpd || parent.first != mpd || parent.second !in setOf("AdaptationSet", "Representation")) reject()
            val scheme = attributes.getValue("", "schemeIdUri") ?: reject()
            val value = attributes.getValue("", "value")
            if (scheme.isEmpty() || scheme.length > 256 || (value?.length ?: 0) > 64) reject()
            shapes.add(ProtectionShape(uri, parent.second, scheme, value))
            if (shapes.size > 32) reject()
        }
        if (uri == cenc || localName.lowercase() in setOf("pssh", "pro", "laurl", "license", "protectionheader"))
            marker(false, uri, localName, frames.lastOrNull() ?: ("" to ""))
        for (index in 0 until attributes.length) {
            if (attributes.getLocalName(index).lowercase() in setOf("default_kid", "kid") || attributes.getURI(index) == cenc)
                marker(true, attributes.getURI(index), attributes.getLocalName(index), uri to localName)
        }
        frames.addLast(uri to localName)
    }
    override fun endElement(uri: String, localName: String, qName: String) { frames.removeLast() }
    override fun resolveEntity(publicId: String?, systemId: String?): InputSource = reject()
    override fun error(error: SAXParseException) = reject()
    override fun fatalError(error: SAXParseException) = reject()
}
