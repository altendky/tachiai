package net.fstab.tachiai.platform.storage

import javax.crypto.SecretKey

// Reuses only a store-owned key handle. Every caller still reads its envelope
// and decrypts independently; neither bytes, plaintext nor Ciphers are retained.
internal class ReadKeyHandle(private val load: () -> SecretKey) {
    private class Entry(val key: SecretKey)
    private val lock = Any()
    private var entry: Entry? = null
    private var generation = Any()

    fun invalidate() = synchronized(lock) {
        entry = null
        generation = Any()
    }

    fun <T> read(readEnvelope: () -> ByteArray?, decrypt: (SecretKey, ByteArray) -> T): T? {
        val observed = synchronized(lock) { generation }
        var acquired: Entry? = null
        try {
            val envelope = readEnvelope() ?: return null
            val selected = synchronized(lock) {
                entry ?: Entry(load()).also {
                    entry = it
                    generation = Any()
                }
            }
            acquired = selected
            // No lock surrounds file I/O or decrypt: readers have independent
            // Ciphers, and invalidation need not wait for an old decrypt.
            return decrypt(selected.key, envelope)
        } catch (error: Throwable) {
            synchronized(lock) {
                // A failed old decrypt/load/read must not evict a newer handle.
                if (if (acquired != null) entry === acquired else generation === observed) {
                    entry = null
                    generation = Any()
                }
            }
            throw error // No retry, replacement key, or disguised failure.
        }
    }
}
