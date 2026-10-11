package net.fstab.tachiai.provider.abema

import java.util.Collections
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.CatalogAvailability
import net.fstab.tachiai.provider.catalog.CatalogEntry
import net.fstab.tachiai.provider.catalog.CatalogIntent
import net.fstab.tachiai.provider.catalog.CatalogResource
import org.json.JSONArray
import org.json.JSONObject

internal const val ABEMA_CHANNEL_RESPONSE_LIMIT = 512 * 1024
internal const val ABEMA_CHANNEL_ENTRY_LIMIT = 500
private val channelIdentity = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")

internal enum class AbemaChannelFailure {
    INVALID_RESPONSE, RESPONSE_LIMIT, POLICY_REFUSED, COOKIE_HANDLER_REFUSED,
    CANCELLED, TIME_LIMIT_REACHED, NETWORK_FAILED, CLEANUP_FAILED,
}

// Only closed categories escape; never retain a response, URL or original cause.
internal class AbemaChannelException(val category: AbemaChannelFailure) :
    Exception(category.name, null, false, false)

internal class AbemaChannelResponse(
    val status: Int,
    entries: List<CatalogEntry> = emptyList(),
    val retryAtEpochMs: Long? = null,
) {
    val entries: List<CatalogEntry> = Collections.unmodifiableList(ArrayList(entries))
    init {
        require(status in 100..599 && entries.size <= ABEMA_CHANNEL_ENTRY_LIMIT)
        require(status == 200 || entries.isEmpty())
        require(retryAtEpochMs == null || status == 429 && retryAtEpochMs >= 0)
    }
    override fun toString() = "AbemaChannelResponse(redacted)"
}

internal interface AbemaChannelTransport : AutoCloseable {
    fun fetch(): AbemaChannelResponse
    fun cancelActiveRequest() = Unit
}

// A channel listing is a public identity snapshot, never evidence of live state,
// region access or entitlement. Playback URLs and all other fields are discarded.
internal fun parseAbemaChannelResponse(body: String): List<CatalogEntry> {
    if (body.length > ABEMA_CHANNEL_RESPONSE_LIMIT ||
        body.toByteArray(Charsets.UTF_8).size > ABEMA_CHANNEL_RESPONSE_LIMIT)
        throw AbemaChannelException(AbemaChannelFailure.RESPONSE_LIMIT)
    try {
        ChannelJsonPreflight(body).check()
        val root = JSONObject(body)
        val channels = root.opt("channels") as? JSONArray ?: invalidChannels()
        if (channels.length() > ABEMA_CHANNEL_ENTRY_LIMIT)
            throw AbemaChannelException(AbemaChannelFailure.RESPONSE_LIMIT)
        val seen = hashSetOf<String>()
        val entries = ArrayList<CatalogEntry>(channels.length())
        for (index in 0 until channels.length()) {
            val channel = channels.opt(index) as? JSONObject ?: invalidChannels()
            val id = channel.opt("id") as? String ?: invalidChannels()
            val name = channel.opt("name") as? String ?: invalidChannels()
            if (id.length !in 1..64 || !id.matches(channelIdentity) || !seen.add(id))
                invalidChannels()
            if (name.length !in 1..160 || name.any {
                it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
            }) invalidChannels()
            entries.add(CatalogEntry(CatalogResource(ProviderId("abema"), "channel", id, CatalogIntent.CHANNEL),
                name.trim(), CatalogAvailability.UNKNOWN))
        }
        return Collections.unmodifiableList(entries)
    } catch (error: AbemaChannelException) { throw error }
    catch (_: Exception) { invalidChannels() }
}

private fun invalidChannels(): Nothing = throw AbemaChannelException(AbemaChannelFailure.INVALID_RESPONSE)

// Android's org.json parser accepts some non-JSON syntax and duplicate keys.
// Bound nesting/all values before recursion in that parser, and reject ambiguous
// keys, trailing input, invalid Unicode and relaxed syntax before normalization.
private class ChannelJsonPreflight(private val text: String) {
    private var offset = 0
    private var values = 0

    fun check() {
        value(0)
        whitespace()
        if (offset != text.length) invalidChannels()
    }

    private fun value(depth: Int) {
        if (depth > 32 || ++values > 16_000) invalidChannels()
        whitespace()
        when (text.getOrNull(offset)) {
            '{' -> objectValue(depth)
            '[' -> arrayValue(depth)
            '"' -> stringValue()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> numberValue()
            else -> invalidChannels()
        }
    }

    private fun objectValue(depth: Int) {
        offset++
        whitespace()
        if (take('}')) return
        val keys = hashSetOf<String>()
        while (true) {
            whitespace()
            if (text.getOrNull(offset) != '"' || !keys.add(stringValue())) invalidChannels()
            whitespace()
            if (!take(':')) invalidChannels()
            value(depth + 1)
            whitespace()
            if (take('}')) return
            if (!take(',')) invalidChannels()
        }
    }

    private fun arrayValue(depth: Int) {
        offset++
        whitespace()
        if (take(']')) return
        while (true) {
            value(depth + 1)
            whitespace()
            if (take(']')) return
            if (!take(',')) invalidChannels()
        }
    }

    private fun stringValue(): String {
        if (!take('"')) invalidChannels()
        val decoded = StringBuilder()
        while (offset < text.length) {
            val char = text[offset++]
            when {
                char == '"' -> {
                    var index = 0
                    while (index < decoded.length) {
                        val current = decoded[index++]
                        if (Character.isHighSurrogate(current)) {
                            if (index == decoded.length || !Character.isLowSurrogate(decoded[index++])) invalidChannels()
                        } else if (Character.isLowSurrogate(current)) invalidChannels()
                    }
                    return decoded.toString()
                }
                char.code < 32 -> invalidChannels()
                char == '\\' -> {
                    when (val escaped = text.getOrNull(offset++)) {
                        '"', '\\', '/' -> decoded.append(escaped)
                        'b' -> decoded.append('\b')
                        'f' -> decoded.append('\u000c')
                        'n' -> decoded.append('\n')
                        'r' -> decoded.append('\r')
                        't' -> decoded.append('\t')
                        'u' -> {
                            if (offset + 4 > text.length) invalidChannels()
                            val hex = text.substring(offset, offset + 4)
                            if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) invalidChannels()
                            decoded.append(hex.toInt(16).toChar())
                            offset += 4
                        }
                        else -> invalidChannels()
                    }
                }
                else -> decoded.append(char)
            }
        }
        invalidChannels()
    }

    private fun literal(expected: String) {
        if (!text.startsWith(expected, offset)) invalidChannels()
        offset += expected.length
    }

    private fun numberValue() {
        take('-')
        if (!take('0')) {
            val first = text.getOrNull(offset)
            if (first == null || first !in '1'..'9') invalidChannels()
            while (digit()) offset++
        }
        if (take('.')) digits()
        if (take('e') || take('E')) {
            if (!take('+')) take('-')
            digits()
        }
    }

    private fun digits() {
        if (!digit()) invalidChannels()
        while (digit()) offset++
    }

    private fun digit() = text.getOrNull(offset)?.let { it in '0'..'9' } == true

    private fun take(char: Char): Boolean = if (text.getOrNull(offset) == char) {
        offset++; true
    } else false

    private fun whitespace() {
        while (offset < text.length && text[offset] in " \t\r\n") offset++
    }
}
