package net.fstab.tachiai.presentation

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import net.fstab.tachiai.platform.media.NativeTimelineEvidence

// A bounded user preference, not a measured synchronization result. One day
// covers live latency/replay alignment without admitting arbitrary Long values.
internal const val MAX_PAIR_OFFSET_MS = 24L * 60 * 60 * 1_000
internal data class PairOffsetCorrespondence(val aMs: Long, val bMs: Long,
    val aLive: Boolean, val bLive: Boolean, val aTimeline: NativeTimelineEvidence?,
    val bTimeline: NativeTimelineEvidence?, val residualMs: Long = 0L) {
    init {
        require(aMs >= 0 && bMs >= 0)
        require(residualMs in -MAX_PAIR_OFFSET_MS..MAX_PAIR_OFFSET_MS)
    }
    override fun toString() = "PairOffsetCorrespondence(clocks=redacted, aLive=$aLive, bLive=$bLive, residualMs=$residualMs)"
}

internal data class PairOffsetPreference(val offsetMs: Long, val enabled: Boolean,
    val correspondence: PairOffsetCorrespondence? = null) {
    init { require(offsetMs in -MAX_PAIR_OFFSET_MS..MAX_PAIR_OFFSET_MS) }
}

internal fun validPairOffsetKey(key: String) = key.matches(Regex("[a-f0-9]{64}"))

// Contains only a hash and orientation. Titles, local item UUIDs, routes,
// authorization and signed sources never contribute to this durable identity.
internal class PairOffsetIdentity private constructor(val key: String, private val reversed: Boolean) {
    fun toCanonical(offsetMs: Long): Long? = orient(offsetMs)
    fun toDisplay(offsetMs: Long): Long? = orient(offsetMs)
    fun toCanonical(preference: PairOffsetPreference): PairOffsetPreference? = orient(preference)
    fun toDisplay(preference: PairOffsetPreference): PairOffsetPreference? = orient(preference)
    private fun orient(value: PairOffsetPreference): PairOffsetPreference? {
        val offset = orient(value.offsetMs) ?: return null
        val correspondence = value.correspondence?.let { saved ->
            if (!reversed) saved else PairOffsetCorrespondence(saved.bMs, saved.aMs,
                saved.bLive, saved.aLive, saved.bTimeline, saved.aTimeline,
                orient(saved.residualMs) ?: return null)
        }
        return value.copy(offsetMs = offset, correspondence = correspondence)
    }
    private fun orient(value: Long): Long? = if (!reversed) value else try {
        Math.negateExact(value)
    } catch (_: ArithmeticException) { null }

    companion object {
        fun forSelection(selection: ConfiguredPlaybackSelection): PairOffsetIdentity {
            require(selection.isPair) { "Pair offsets require both selected feeds" }
            fun fields(values: List<ByteArray>) = ByteArrayOutputStream().also { output ->
                DataOutputStream(output).use { data -> values.forEach { value ->
                    data.writeInt(value.size); data.write(value)
                } }
            }.toByteArray()
            fun feed(value: ConfiguredPlaybackFeed) = fields(listOf(value.instanceId,
                value.resource.providerId.value, value.resource.kind, value.resource.identity,
                value.resource.intent.name, value.kind.name).map { it.toByteArray(Charsets.UTF_8) })
            fun compare(first: ByteArray, second: ByteArray): Int {
                for (index in 0 until minOf(first.size, second.size)) {
                    val order = (first[index].toInt() and 255).compareTo(second[index].toInt() and 255)
                    if (order != 0) return order
                }
                return first.size.compareTo(second.size)
            }
            val a = feed(checkNotNull(selection.a)); val b = feed(checkNotNull(selection.b))
            // Identical sources cannot reveal an assignment reversal. Keep
            // deterministic A/B slot orientation (+1) for this ambiguous case.
            val reversed = compare(a, b) > 0
            val bytes = fields(listOf("tachiai:pair-offset:v1".toByteArray(Charsets.US_ASCII),
                if (reversed) b else a, if (reversed) a else b))
            val key = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            return PairOffsetIdentity(key, reversed)
        }
    }
}
