package net.fstab.tachiai.provider.abema.account

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import net.fstab.tachiai.presentation.MAX_PROVIDER_INSTANCES
import net.fstab.tachiai.presentation.validProviderInstanceId

// Public scheduling metadata only. This is never an account or route authority.
internal interface AbemaRenewalJobStorage {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

internal data class AbemaRenewalGeneration(val generation: Long, val acceptedWallMs: Long, val expiresWallMs: Long) {
    init { require(generation > 0 && acceptedWallMs >= 0 && expiresWallMs > acceptedWallMs) }
    override fun toString() = "AbemaRenewalGeneration(redacted)"
}

internal data class AbemaRenewalJobKey(val instance: String, val schedule: String, val jobId: Int) {
    init {
        require(validProviderInstanceId(instance) && validProviderInstanceId(schedule))
        require(jobId in AbemaRenewalJobLedger.JOB_IDS)
    }
    override fun toString() = "AbemaRenewalJobKey(redacted)"
}

internal enum class AbemaRenewalJobDisposition {
    DUE, RETRY, RUNNING, COMPLETED, DEFERRED_INTERRUPTED, DEFERRED_STOP,
    DEFERRED_INTERACTION, DEFERRED_STORAGE, DEFERRED_FAILURE, DEFERRED_CLEANUP, EXHAUSTED,
}

internal data class AbemaRenewalJobEntry(val key: AbemaRenewalJobKey, val generation: AbemaRenewalGeneration,
    val dueWallMs: Long, val attempts: Int, val disposition: AbemaRenewalJobDisposition) {
    init {
        require(dueWallMs >= generation.expiresWallMs && attempts in 0..3)
        require(disposition != AbemaRenewalJobDisposition.DUE || attempts == 0)
        require(disposition != AbemaRenewalJobDisposition.RUNNING || attempts > 0)
        require(disposition != AbemaRenewalJobDisposition.RETRY || attempts in 1..2)
    }
    val pending get() = disposition in setOf(AbemaRenewalJobDisposition.DUE, AbemaRenewalJobDisposition.RETRY)
    override fun toString() = "AbemaRenewalJobEntry(redacted)"
}

internal data class AbemaRenewalJobAttempt(val key: AbemaRenewalJobKey, val number: Int) {
    init { require(number in 1..3) }
    override fun toString() = "AbemaRenewalJobAttempt(redacted)"
}

// Serialized by the ordinary account worker. A failed read/write poisons this
// instance: resetting metadata could repeat an uncertain provider transaction.
internal class AbemaRenewalJobLedger(private val storage: AbemaRenewalJobStorage,
    private val scheduleId: () -> String = { UUID.randomUUID().toString() }) {
    companion object {
        val JOB_IDS = 0x54410000 until 0x54410000 + MAX_PROVIDER_INSTANCES
        const val MAX_BYTES = 16 * 1024
        const val FILE_NAME = "abema-renewal-jobs-v1.bin"
    }
    private var entries: List<AbemaRenewalJobEntry>? = null
    private var refused = false

    fun recoverCold() {
        check(entries == null && !refused)
        try {
            val loaded = storage.read()?.let(::decodeAbemaRenewalJobMetadata) ?: emptyList()
            val recovered = loaded.map { if (it.disposition == AbemaRenewalJobDisposition.RUNNING)
                it.copy(disposition = AbemaRenewalJobDisposition.DEFERRED_INTERRUPTED) else it }
            if (recovered != loaded) storage.write(encode(recovered))
            entries = recovered
        } catch (error: Throwable) { refused = true; throw error }
    }

    fun snapshot(): List<AbemaRenewalJobEntry> {
        check(!refused)
        return checkNotNull(entries).toList()
    }

    // Only a fresh private snapshot may supply generation. Repeated accepted
    // notifications are idempotent too; an ambiguous witness stays deferred.
    fun reconcile(instance: String, generation: AbemaRenewalGeneration?): AbemaRenewalJobEntry? {
        require(validProviderInstanceId(instance))
        val current = snapshot()
        val prior = current.singleOrNull { it.key.instance == instance }
        if (generation == null) {
            if (prior != null) commit(current - prior)
            return null
        }
        if (prior != null && prior.generation == generation) return prior
        check(current.size < MAX_PROVIDER_INSTANCES || prior != null)
        val jobId = prior?.key?.jobId ?: JOB_IDS.first { id -> current.none { it.key.jobId == id } }
        val id = scheduleId()
        check(current.none { it.key.schedule == id })
        val next = AbemaRenewalJobEntry(AbemaRenewalJobKey(instance, id, jobId), generation,
            generation.expiresWallMs, 0, AbemaRenewalJobDisposition.DUE)
        commit(current.filterNot { it.key.instance == instance } + next)
        return next
    }

    // This durable write must complete before the owner lease is activated.
    fun consume(key: AbemaRenewalJobKey, generation: AbemaRenewalGeneration, wallMs: Long): AbemaRenewalJobAttempt? {
        val current = snapshot()
        val entry = current.singleOrNull { it.key == key } ?: return null
        if (!entry.pending || entry.generation != generation || wallMs < entry.dueWallMs || entry.attempts >= 3) return null
        val next = entry.copy(attempts = entry.attempts + 1, disposition = AbemaRenewalJobDisposition.RUNNING)
        commit(current - entry + next)
        return AbemaRenewalJobAttempt(key, next.attempts)
    }

    // Every actual scheduling call publishes a fresh invocation key first.
    // Early/no-network delivery and failed scheduling consume no provider attempt.
    fun rearm(key: AbemaRenewalJobKey): AbemaRenewalJobEntry? {
        val current = snapshot()
        val entry = current.singleOrNull { it.key == key && it.pending } ?: return null
        val id = scheduleId()
        check(current.none { it.key.schedule == id })
        val next = entry.copy(key = key.copy(schedule = id))
        commit(current - entry + next)
        return next
    }

    fun defer(key: AbemaRenewalJobKey, disposition: AbemaRenewalJobDisposition) {
        require(disposition.name.startsWith("DEFERRED_"))
        val current = snapshot()
        val entry = current.singleOrNull { it.key == key } ?: return
        if (!entry.pending && entry.disposition != AbemaRenewalJobDisposition.RUNNING) return
        commit(current - entry + entry.copy(disposition = disposition))
    }

    // Outcomes are classified in RAM; no diagnostic is written to this ledger.
    fun settle(attempt: AbemaRenewalJobAttempt, disposition: AbemaRenewalJobDisposition,
        wallMs: Long, retrySafe: Boolean = false): AbemaRenewalJobEntry? {
        require(disposition !in setOf(AbemaRenewalJobDisposition.DUE, AbemaRenewalJobDisposition.RUNNING,
            AbemaRenewalJobDisposition.RETRY))
        require(!retrySafe || disposition == AbemaRenewalJobDisposition.DEFERRED_FAILURE)
        val current = snapshot()
        val entry = current.singleOrNull { it.key == attempt.key && it.attempts == attempt.number &&
            it.disposition == AbemaRenewalJobDisposition.RUNNING } ?: return null
        val next = if (!retrySafe) entry.copy(disposition = disposition)
        else if (entry.attempts >= 3) entry.copy(disposition = AbemaRenewalJobDisposition.EXHAUSTED)
        else {
            val wait = if (entry.attempts == 1) 120_000L else 600_000L
            if (wallMs < entry.generation.acceptedWallMs || maxOf(wallMs, entry.dueWallMs) > Long.MAX_VALUE - wait)
                entry.copy(disposition = AbemaRenewalJobDisposition.DEFERRED_FAILURE)
            else {
                val id = scheduleId()
                check(current.none { it.key.schedule == id })
                entry.copy(key = entry.key.copy(schedule = id), dueWallMs = maxOf(wallMs, entry.dueWallMs) + wait,
                    disposition = AbemaRenewalJobDisposition.RETRY)
            }
        }
        commit(current - entry + next)
        return next
    }

    private fun commit(updated: List<AbemaRenewalJobEntry>) {
        check(!refused && entries != null)
        try { storage.write(encode(updated)); entries = updated.toList() }
        catch (error: Throwable) { refused = true; throw error }
    }

    private fun encode(values: List<AbemaRenewalJobEntry>): ByteArray {
        require(values.size <= MAX_PROVIDER_INSTANCES)
        return ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(1); data.writeInt(values.size)
            values.forEach { value ->
                data.writeUTF(value.key.instance); data.writeUTF(value.key.schedule); data.writeInt(value.key.jobId)
                data.writeLong(value.generation.generation); data.writeLong(value.generation.acceptedWallMs)
                data.writeLong(value.generation.expiresWallMs); data.writeLong(value.dueWallMs)
                data.writeInt(value.attempts); data.writeUTF(value.disposition.name)
            }
        } }.toByteArray().also { check(it.size <= MAX_BYTES) }
    }

    override fun toString() = "AbemaRenewalJobLedger(redacted)"
}

// Read-only validation for bounded inventory observations too. No file access,
// recovery transition or authority is acquired by decoding public metadata.
internal fun decodeAbemaRenewalJobMetadata(bytes: ByteArray): List<AbemaRenewalJobEntry> {
    check(bytes.size <= AbemaRenewalJobLedger.MAX_BYTES)
    return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
        check(data.readInt() == 1)
        val count = data.readInt(); check(count in 0..MAX_PROVIDER_INSTANCES)
        val result = List(count) {
            val key = AbemaRenewalJobKey(data.readUTF(), data.readUTF(), data.readInt())
            val generation = AbemaRenewalGeneration(data.readLong(), data.readLong(), data.readLong())
            AbemaRenewalJobEntry(key, generation, data.readLong(), data.readInt(),
                AbemaRenewalJobDisposition.valueOf(data.readUTF()))
        }
        check(data.read() == -1 && result.map { it.key.instance }.distinct().size == count &&
            result.map { it.key.schedule }.distinct().size == count && result.map { it.key.jobId }.distinct().size == count)
        result
    }
}
