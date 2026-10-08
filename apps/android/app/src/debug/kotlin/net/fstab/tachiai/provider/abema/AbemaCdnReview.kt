package net.fstab.tachiai.provider.abema

import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

internal enum class AbemaCdnDecision { APPROVED, PENDING, REJECTED }
internal enum class AbemaCdnRule { ABEMATV_AKAMAI_FAMILY, APPROVED_EXACT, REJECTED_EXACT, UNKNOWN_ORIGIN }
internal enum class AbemaCdnStage { SELECTED_MANIFEST, DECLARED_MEDIA }

// User-approved debug-prototype boundary, not a claim of CDN ownership or
// provider permission. One DNS label only; no arbitrary Akamai subdomains.
internal object AbemaCdnApprovalPolicy {
    private val family = Regex("[a-z0-9][a-z0-9-]{0,54}-abematv\\.akamaized\\.net")
    // Add exact origins only after recording the user's decision in
    // docs/src/project/media-origin-approvals.md. The journal never grants access.
    private val approvedExact = emptySet<String>()
    private val rejectedExact = emptySet<String>()

    fun approvedHost(host: String?): Boolean {
        val normalized = host?.lowercase(Locale.ROOT) ?: return false
        return "https://$normalized" !in rejectedExact &&
            (family.matches(normalized) || "https://$normalized" in approvedExact)
    }

    // Sanitize immediately. Never retain path/query/fragment/userinfo, even in
    // memory queues or hashes. Unsafe/ambiguous authorities cannot be reviewed.
    fun review(uri: URI, stage: AbemaCdnStage, nowMs: Long): AbemaCdnReviewRecord? {
        if (uri.scheme != "https" || uri.port !in listOf(-1, 443) || uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (!validHost(host)) return null
        val origin = "https://$host"
        val (decision, rule) = when {
            origin in rejectedExact -> AbemaCdnDecision.REJECTED to AbemaCdnRule.REJECTED_EXACT
            family.matches(host) -> AbemaCdnDecision.APPROVED to AbemaCdnRule.ABEMATV_AKAMAI_FAMILY
            origin in approvedExact -> AbemaCdnDecision.APPROVED to AbemaCdnRule.APPROVED_EXACT
            else -> AbemaCdnDecision.PENDING to AbemaCdnRule.UNKNOWN_ORIGIN
        }
        return AbemaCdnReviewRecord(nowMs, decision, stage, rule, "$origin/<redacted-path>")
    }

    internal fun validHost(host: String): Boolean = host.length in 1..253 && host.split('.').let { labels ->
        labels.size >= 2 && labels.all { Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?").matches(it) }
    }
}

internal data class AbemaCdnReviewRecord(
    val firstSeenMs: Long,
    val decision: AbemaCdnDecision,
    val stage: AbemaCdnStage,
    val rule: AbemaCdnRule,
    val pattern: String,
) {
    val identity: String get() = "${decision.name}\t${stage.name}\t${rule.name}\t$pattern"
}

internal object AbemaCdnReviewCodec {
    const val MAX_BYTES = 64 * 1024
    const val MAX_RECORDS = 128
    const val HEADER = "tachiai-abema-cdn-review-v1"
    fun encode(records: List<AbemaCdnReviewRecord>): ByteArray {
        check(records.size <= MAX_RECORDS && records.map { it.identity }.toSet().size == records.size)
        val text = buildString {
            append(HEADER).append('\n')
            records.forEach { append(it.firstSeenMs).append('\t').append(it.identity).append('\n') }
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_BYTES)
        check(decode(bytes) == records)
        return bytes
    }

    fun decode(bytes: ByteArray): List<AbemaCdnReviewRecord> {
        check(bytes.size <= MAX_BYTES && bytes.all { it.toInt() in 9..126 })
        val lines = bytes.toString(Charsets.US_ASCII).split('\n')
        check(lines.first() == HEADER && lines.last() == "" && lines.size <= MAX_RECORDS + 2)
        val records = lines.drop(1).dropLast(1).map { row ->
            val fields = row.split('\t')
            check(fields.size == 5 && Regex("[0-9]{1,13}").matches(fields[0]))
            val timestamp = fields[0].toLong()
            val pattern = fields[4]
            check(pattern.startsWith("https://") && pattern.endsWith("/<redacted-path>"))
            val host = pattern.removePrefix("https://").removeSuffix("/<redacted-path>")
            check(AbemaCdnApprovalPolicy.validHost(host))
            AbemaCdnReviewRecord(timestamp, AbemaCdnDecision.valueOf(fields[1]), AbemaCdnStage.valueOf(fields[2]),
                AbemaCdnRule.valueOf(fields[3]), pattern)
        }
        check(records.map { it.identity }.toSet().size == records.size)
        return records
    }
}

// App-private, no-backup, bounded evidence. Decisions here cannot change the
// compiled policy. Both duplicate slots synchronize atomic read/merge/write.
// Existing observations are deduplicated in memory: segment numbers/tokens do
// not create disk writes or new rows. No pruning/automatic approvals occur.
internal class AbemaCdnReviewJournal(private val file: File) {
    companion object { private val lock = Any() }
    private val seen = mutableSetOf<String>()

    fun record(value: AbemaCdnReviewRecord) = synchronized(lock) {
        if (value.identity in seen) return@synchronized
        val parent = checkNotNull(file.parentFile)
        check(!Files.isSymbolicLink(file.toPath()) && parent.isDirectory &&
            !Files.isSymbolicLink(parent.toPath()))
        val records = if (file.exists()) {
            check(file.isFile && file.length() <= AbemaCdnReviewCodec.MAX_BYTES)
            file.inputStream().use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(bytes.size() + count <= AbemaCdnReviewCodec.MAX_BYTES)
                    bytes.write(buffer, 0, count)
                }
                AbemaCdnReviewCodec.decode(bytes.toByteArray())
            }
        } else emptyList()
        if (records.none { it.identity == value.identity }) {
            val bytes = AbemaCdnReviewCodec.encode(records + value)
            val temporary = File.createTempFile(".cdn-review-", ".tmp", parent)
            try {
                FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
                check(!Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(parent.toPath()))
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { check(!temporary.exists() || temporary.delete()) }
        }
        seen.add(value.identity)
    }
}
