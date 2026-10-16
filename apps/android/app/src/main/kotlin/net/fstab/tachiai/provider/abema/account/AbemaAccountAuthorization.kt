package net.fstab.tachiai.provider.abema.account

import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import net.fstab.tachiai.platform.network.ProviderTransportEvidence
import net.fstab.tachiai.platform.network.ProviderTransportFailureCarrier

internal enum class AbemaAccountFailure { REFUSED, NOT_VERIFIED, TEMPORARY, TIME_LIMIT_REACHED, EXPIRED, CANCELLED, CLEANUP_FAILED, STORAGE_FAILED }
internal class AbemaAccountException(val category: AbemaAccountFailure,
    override val transportFailure: ProviderTransportEvidence? = null) :
    Exception(category.name, null, false, false), ProviderTransportFailureCarrier
internal fun accountRefused(): Nothing = throw AbemaAccountException(AbemaAccountFailure.REFUSED)
internal fun accountId(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,256}"))
internal fun accountToken(value: String) = value.length in 1..6144 && value.all { it.code in 33..126 }

// Values stay inside the protected account flow. None are generic UI state,
// diagnostic fields, saved-state values or catalog IPC projections.
internal class AbemaAccountChallenge(
    val guestId: String, val guestBearer: String, val clientId: String,
    val deviceCode: String, val tokenBody: String, val verificationUri: String,
    val deadlineElapsedMs: Long,
) {
    init {
        require(accountId(guestId) && guestId == clientId)
        require(guestBearer.length in 1..8192 && guestBearer.all { it.code in 33..126 })
        require(deviceCode.length in 1..2048 && deviceCode.all { it.code in 33..126 })
        require(validAbemaVerificationUri(verificationUri) && deadlineElapsedMs >= 0)
        require(validAbemaTokenBody(tokenBody, clientId, deviceCode))
    }
    override fun toString() = "AbemaAccountChallenge(redacted)"
}

internal class AbemaAccountGrant(val userId: String, val bearer: String, val expiresAtMs: Long) {
    init { require(accountId(userId) && accountToken(bearer) && expiresAtMs > 0) }
    override fun toString() = "AbemaAccountGrant(redacted)"
}
internal sealed interface AbemaAccountTokenResult {
    data object Pending : AbemaAccountTokenResult
    class Granted(val grant: AbemaAccountGrant) : AbemaAccountTokenResult {
        override fun toString() = "AbemaAccountTokenResult.Granted(redacted)"
    }
    class Failed(val category: AbemaAccountFailure) : AbemaAccountTokenResult
}

internal fun validAbemaVerificationUri(text: String): Boolean = runCatching {
    if (text.length !in 1..2048 || text.any { it.code !in 33..126 } || '\\' in text || '#' in text) return false
    val decoded = URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
    if (decoded.codePoints().anyMatch { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() || it == '\\'.code || it == 0xfffd }) return false
    val uri = URI(text)
    uri.scheme == "https" && uri.host == "abema.tv" && uri.port == -1 && uri.rawUserInfo == null &&
        uri.rawFragment == null && uri.normalize() == uri && uri.toASCIIString() == text && uri.rawPath.startsWith("/") &&
        uri.path.split('/').none { it == "." || it == ".." }
}.getOrDefault(false)

internal fun validAbemaTokenBody(body: String, client: String, code: String): Boolean = runCatching {
    val value = AbemaAccountJson.objectValue(body, 8192)
    value.keys == setOf("client_id", "device_code", "grant_type", "device_type") &&
        value["client_id"] == client && value["device_code"] == code &&
        value["grant_type"] == "urn:ietf:params:oauth:grant-type:device_code" && value["device_type"] == 3L
}.getOrDefault(false)

internal fun decodeAbemaAccountChallenge(text: String, beganElapsedMs: Long, nowElapsedMs: Long): AbemaAccountChallenge {
    val value = AbemaAccountJson.objectValue(text, 24576)
    if (value.keys != setOf("guestId", "guestBearer", "clientId", "deviceCode", "tokenBody", "verificationUri", "expiresIn")) accountRefused()
    val expiry = value["expiresIn"] as? Long ?: accountRefused()
    if (expiry !in 1..3600 || beganElapsedMs < 0 || nowElapsedMs < beganElapsedMs || beganElapsedMs > Long.MAX_VALUE - expiry * 1000) accountRefused()
    val deadline = beganElapsedMs + expiry * 1000
    if (nowElapsedMs >= deadline) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
    fun field(key: String) = value[key] as? String ?: accountRefused()
    try { return AbemaAccountChallenge(field("guestId"), field("guestBearer"), field("clientId"), field("deviceCode"),
        field("tokenBody"), field("verificationUri"), deadline) }
    catch (_: Exception) { accountRefused() }
}

// Only the observed flat HTTP400 pending response admits another request.
// Message text is type-checked, discarded and never interpreted or reported.
internal fun parseAbemaAccountToken(status: Int, body: String?, guestId: String, nowMs: Long): AbemaAccountTokenResult {
    try {
        if (!accountId(guestId)) accountRefused()
        if (status == 400) {
            val value = AbemaAccountJson.objectValue(body ?: accountRefused(), 65536)
            if (value.keys == setOf("code", "message") && value["message"] is String &&
                (value["code"] == 16L || value["code"] == "AUTHORIZATION_PENDING")) return AbemaAccountTokenResult.Pending
            return AbemaAccountTokenResult.Failed(AbemaAccountFailure.REFUSED)
        }
        if (status != 200) return AbemaAccountTokenResult.Failed(AbemaAccountFailure.REFUSED)
        val value = AbemaAccountJson.objectValue(body ?: accountRefused(), 65536)
        if (value.keys != setOf("access_token", "token_type", "refresh_token", "user_id")) accountRefused()
        val token = value["access_token"] as? String ?: accountRefused()
        val id = value["user_id"] as? String ?: accountRefused()
        if (!accountToken(token) || !accountId(id) || id == guestId ||
            (value["token_type"] as? String)?.equals("Bearer", true) != true ||
            value["refresh_token"] !is String || (value["refresh_token"] as String).length > 8192) accountRefused()
        val grant = decodedAbemaGrant(token, id, nowMs)
        return AbemaAccountTokenResult.Granted(grant)
    } catch (error: AbemaAccountException) { return AbemaAccountTokenResult.Failed(error.category) }
    catch (_: Exception) { return AbemaAccountTokenResult.Failed(AbemaAccountFailure.REFUSED) }
}

// TLS token issuance and authenticated profile agreement establish acceptance;
// JWT decoding supplies only bounded consistency and expiry data, never a
// claim that this client independently verified a provider signature.
internal fun decodedAbemaGrant(token: String, expectedId: String, nowMs: Long): AbemaAccountGrant {
    if (!accountToken(token) || !accountId(expectedId) || nowMs < 0) accountRefused()
    val parts = token.split('.')
    if (parts.size != 3 || parts.any { it.isEmpty() || !it.matches(Regex("[A-Za-z0-9_-]+")) }) accountRefused()
    val claims = try { AbemaAccountJson.objectValue(strictAccountUtf8(Base64.getUrlDecoder().decode(parts[1])), 8192) }
        catch (_: Exception) { accountRefused() }
    if (claims["sub"] != expectedId) accountRefused()
    val expiry = claims["exp"] as? Long ?: accountRefused()
    if (expiry <= 0 || expiry > Long.MAX_VALUE / 1000 || expiry * 1000 <= nowMs) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
    return AbemaAccountGrant(expectedId, token, expiry * 1000)
}

// The verified first-party runtime projects JWT dev into clientInformation's
// device.id (modules 58557/91970/59090). This is required only for playback;
// ordinary account/catalog acceptance and the protected storage codec stay
// unchanged. Acceptance still comes from TLS issuance and matching profile.
internal fun abemaPlaybackDeviceId(grant: AbemaAccountGrant, nowMs: Long): String {
    if (decodedAbemaGrant(grant.bearer, grant.userId, nowMs).expiresAtMs != grant.expiresAtMs) accountRefused()
    val claims = try {
        AbemaAccountJson.objectValue(strictAccountUtf8(Base64.getUrlDecoder().decode(grant.bearer.split('.')[1])), 8192)
    } catch (_: Exception) { accountRefused() }
    val device = claims["dev"] as? String ?: accountRefused()
    if (device.length !in 1..256 || device.any { it.code !in 33..126 }) accountRefused()
    return device
}

internal fun parseAbemaAccountProfile(body: String): String {
    val value = AbemaAccountJson.objectValue(body, 65536)
    val profile = value["profile"] as? Map<*, *> ?: accountRefused()
    return (profile["userId"] as? String)?.takeIf(::accountId) ?: accountRefused()
}
// Only the browser's existing first-party GET header is eligible. No login
// bodies, passwords, passcodes, cookies or browser storage are inspected.
internal fun abemaEmailRegistrationId(url: String): String? =
    Regex("https://api\\.p-c3-e\\.abema-tv\\.com/v1/users/([A-Za-z0-9_-]{1,256})/email")
        .matchEntire(url)?.groupValues?.get(1)

internal fun parseAbemaEmailCandidate(url: String, method: String, headers: Map<String, String>, nowMs: Long): AbemaAccountGrant? {
    if (method != "GET") return null
    val id = abemaEmailRegistrationId(url) ?: return null
    val authorization = headers.entries.filter { it.key.equals("Authorization", ignoreCase = true) }
    if (authorization.size != 1) return null
    val value = authorization.single().value
    if (value.length !in 8..6151 || !value.substring(0, 7).equals("Bearer ", ignoreCase = true)) return null
    return runCatching { decodedAbemaGrant(value.substring(7), id, nowMs) }.getOrNull()
}

// Project registration status only; the provider's email remains request-local
// and is neither returned nor retained in account state or diagnostics.
internal fun parseAbemaAccountEmailRegistration(body: String): AbemaAccountEmailResult = try {
    val value = AbemaAccountJson.objectValue(body, 65536)
    if ("email" !in value || value.keys.any { it !in setOf("email", "hasPassword") } ||
        "hasPassword" in value && value["hasPassword"] !is Boolean) AbemaAccountEmailResult.INCONCLUSIVE
    else when (val email = value["email"]) {
        null, "" -> AbemaAccountEmailResult.UNREGISTERED
        is String -> if (email.length <= 320 && email.none { it.isWhitespace() || Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() })
            AbemaAccountEmailResult.REGISTERED else AbemaAccountEmailResult.INCONCLUSIVE
        else -> AbemaAccountEmailResult.INCONCLUSIVE
    }
} catch (_: Exception) { AbemaAccountEmailResult.INCONCLUSIVE }

internal fun strictAccountUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()

// Provider JSON is fully checked before projection, including ignored fields.
// Duplicate keys, relaxed syntax, invalid Unicode and trailing input are refused.
internal object AbemaAccountJson {
    fun objectValue(text: String, limit: Int): Map<String, Any?> {
        if (text.length > limit || text.toByteArray(Charsets.UTF_8).size > limit) accountRefused()
        val parser = Parser(text)
        val value = parser.value(0)
        parser.space()
        if (!parser.done() || value !is Map<*, *>) accountRefused()
        @Suppress("UNCHECKED_CAST") return value as Map<String, Any?>
    }
    private class Parser(private val text: String) {
        private var at = 0
        private var nodes = 0
        fun done() = at == text.length
        fun space() { while (text.getOrNull(at) in listOf(' ', '\t', '\r', '\n')) at++ }
        private fun take(c: Char) = (text.getOrNull(at) == c).also { if (it) at++ }
        fun value(depth: Int): Any? {
            if (depth > 16 || ++nodes > 4096) accountRefused()
            space()
            return when (text.getOrNull(at)) {
                '{' -> {
                    at++; space(); val result = linkedMapOf<String, Any?>()
                    if (!take('}')) while (true) {
                        space(); val key = string(); if (result.containsKey(key)) accountRefused()
                        space(); if (!take(':')) accountRefused(); result[key] = value(depth + 1); space()
                        if (take('}')) break; if (!take(',')) accountRefused()
                    }; result
                }
                '[' -> {
                    at++; space(); val result = arrayListOf<Any?>()
                    if (!take(']')) while (true) {
                        if (result.size >= 1000) accountRefused(); result.add(value(depth + 1)); space()
                        if (take(']')) break; if (!take(',')) accountRefused()
                    }; result
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> {
                    val start = at; take('-')
                    if (!take('0')) { if (text.getOrNull(at) !in '1'..'9') accountRefused(); while (text.getOrNull(at) in '0'..'9') at++ }
                    if (take('.')) digits()
                    if (take('e') || take('E')) { if (!take('+')) take('-'); digits() }
                    val number = text.substring(start, at)
                    number.toLongOrNull() ?: number.toDoubleOrNull()?.takeIf { it.isFinite() } ?: accountRefused()
                }
                else -> accountRefused()
            }
        }
        private fun digits() { if (text.getOrNull(at) !in '0'..'9') accountRefused(); while (text.getOrNull(at) in '0'..'9') at++ }
        private fun literal(word: String, value: Any?): Any? { if (!text.startsWith(word, at)) accountRefused(); at += word.length; return value }
        private fun string(): String {
            if (!take('"')) accountRefused()
            val out = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                if (c == '"') {
                    var i = 0
                    while (i < out.length) {
                        val current = out[i++]
                        if (Character.isHighSurrogate(current)) { if (i == out.length || !Character.isLowSurrogate(out[i++])) accountRefused() }
                        else if (Character.isLowSurrogate(current)) accountRefused()
                    }; return out.toString()
                }
                if (c.code < 32) accountRefused()
                if (c != '\\') { out.append(c); continue }
                when (val escape = text.getOrNull(at++)) {
                    '"', '\\', '/' -> out.append(escape)
                    'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                    'u' -> { val hex = text.substring(at, minOf(at + 4, text.length)); if (hex.length != 4 || !hex.matches(Regex("[0-9A-Fa-f]{4}"))) accountRefused(); out.append(hex.toInt(16).toChar()); at += 4 }
                    else -> accountRefused()
                }
            }; accountRefused()
        }
    }
}
