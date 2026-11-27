package net.fstab.tachiai.platform.storage

import javax.crypto.SecretKey

// Reuses only a store-owned key handle. Every caller still reads its envelope
// and decrypts independently; neither bytes, plaintext nor Ciphers are retained.
internal class ReadKeyHandle(private val load: () -> SecretKey) {
    private class Entry(val key: SecretKey)
    private val lock = Any()
    private var entry: Entry? = null
    private var generation = Any()

    // One envelope read, decrypted later by open (#771). The handle
    // generation is observed before the file read, as in a single read.
    inner class Sealed internal constructor(private val observed: Any, val envelope: ByteArray?) {
        fun <T> open(decrypt: (SecretKey, ByteArray) -> T): T? {
            val bytes = envelope ?: return null
            var acquired: Entry? = null
            try {
                val selected = synchronized(lock) {
                    entry ?: Entry(load()).also {
                        entry = it
                        generation = Any()
                    }
                }
                acquired = selected
                // No lock surrounds decrypt: readers have independent Ciphers,
                // and invalidation need not wait for an old decrypt.
                return decrypt(selected.key, bytes)
            } catch (error: Throwable) {
                evict(acquired, observed)
                throw error // No retry, replacement key, or disguised failure.
            }
        }
        override fun toString() = "ReadKeyHandle.Sealed(redacted)"
    }

    fun invalidate() = synchronized(lock) {
        entry = null
        generation = Any()
    }

    // A failed old decrypt/load/read must not evict a newer handle.
    private fun evict(acquired: Entry?, observed: Any) = synchronized(lock) {
        if (if (acquired != null) entry === acquired else generation === observed) {
            entry = null
            generation = Any()
        }
    }

    fun seal(readEnvelope: () -> ByteArray?): Sealed {
        val observed = synchronized(lock) { generation }
        val envelope = try { readEnvelope() } catch (error: Throwable) {
            evict(null, observed)
            throw error
        }
        return Sealed(observed, envelope)
    }

    fun <T> read(readEnvelope: () -> ByteArray?, decrypt: (SecretKey, ByteArray) -> T): T? =
        seal(readEnvelope).open(decrypt)
}
