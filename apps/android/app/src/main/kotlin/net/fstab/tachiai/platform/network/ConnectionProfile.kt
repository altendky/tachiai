package net.fstab.tachiai.platform.network

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal const val CONNECTION_IMPORT_LIMIT = 8192

// Not a data class: generated diagnostics/copy/component methods must not expose secrets.
internal class ConnectionProfile internal constructor(
    internal val protocol: RouteProtocol,
    val endpoint: String,
    internal val configuration: String,
) {
    val kind get() = protocol.kind
    override fun toString() = "ConnectionProfile(${kind.id}, secrets hidden)"
}

internal fun routeDigest(value: String) = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

// Handlers may supply a fixed, credential-free explanation. Never interpolate
// imported text, underlying parser exceptions or credentials into this message.
internal class ConnectionImportFailure(val category: Category, message: String = category.message) : Exception(message) {
    enum class Category(private val description: String) {
        SIZE("Configuration is too large. Maximum size is 8 KiB."),
        TEXT("Use a UTF-8 text configuration, not an archive or image."),
        FORMAT(""),
        OPTION("This configuration contains unsupported options. No commands or scripts are executed."),
        PEERS("The configuration contains an unsupported number of peers."),
        VALUE("A required field is missing, duplicated or invalid. Nothing was saved."),
        SOURCE("This file source needs Android 11 or newer. Use a local downloaded file or paste the configuration.");

        val message get() = if (this == FORMAT) "Use ${routeProtocols.importHint}." else description
    }
}

internal fun failConnectionImport(category: ConnectionImportFailure.Category): Nothing = throw ConnectionImportFailure(category)
internal fun validateConnectionValue(value: Boolean) { if (!value) failConnectionImport(ConnectionImportFailure.Category.VALUE) }

internal fun readConnectionImport(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > CONNECTION_IMPORT_LIMIT) failConnectionImport(ConnectionImportFailure.Category.SIZE)
        output.write(buffer, 0, count)
    }
}

internal fun parseConnectionProfile(bytes: ByteArray): ConnectionProfile = routeProtocols.parse(bytes)

internal fun decodeConnectionText(bytes: ByteArray): String {
    if (bytes.size > CONNECTION_IMPORT_LIMIT) failConnectionImport(ConnectionImportFailure.Category.SIZE)
    val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF").trim()
    } catch (_: Exception) { failConnectionImport(ConnectionImportFailure.Category.TEXT) }
    if (text.any { it.isISOControl() && it !in "\r\n\t" }) failConnectionImport(ConnectionImportFailure.Category.TEXT)
    return text
}

internal fun validConnectionName(value: String): Boolean = value == value.trim() && value.length in 1..48 &&
    value.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
