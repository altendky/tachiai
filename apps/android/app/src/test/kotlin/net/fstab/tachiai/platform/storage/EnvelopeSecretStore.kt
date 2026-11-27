package net.fstab.tachiai.platform.storage

import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec

// A software-key stand-in for AndroidPrivateSecretStore (#745): each write
// stores a fresh random-IV envelope, reads decrypt it, and revisions digest it
// without decrypting. decryptMs stands in for a slow keystore operation.
internal class EnvelopeSecretStore(@Volatile var decryptMs: Long = 0) : PrivateSecretStore {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val binding = "fixture-envelope".toByteArray()
    @Volatile var envelope: ByteArray? = null
    @Volatile var readHook: () -> Unit = {}
    val decrypts = AtomicInteger()
    val revisions = AtomicInteger()
    override fun read(): ByteArray? = readRevisioned().plaintext
    override fun revision(): PrivateSecretRevision {
        readHook(); revisions.incrementAndGet()
        return PrivateSecretRevision.of(envelope?.copyOf())
    }
    // Runs inside each decryption, which a sealed read defers to open (#771).
    @Volatile var decryptHook: () -> Unit = {}
    override fun readRevisioned(): RevisionedPrivateSecret =
        readSealed().let { RevisionedPrivateSecret(it.open(), it.revision) }
    override fun readSealed(): SealedPrivateSecret {
        readHook()
        val stored = envelope?.copyOf()
        return SealedPrivateSecret(stored == null, PrivateSecretRevision.of(stored)) {
            stored?.let {
                decrypts.incrementAndGet()
                decryptHook()
                if (decryptMs > 0) Thread.sleep(decryptMs)
                decryptPrivateSecret(key, binding, it)
            }
        }
    }
    override fun write(plaintext: ByteArray) { envelope = encryptPrivateSecret(key, binding, plaintext) }
}
