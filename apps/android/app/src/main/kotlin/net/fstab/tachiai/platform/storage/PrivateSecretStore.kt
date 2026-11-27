package net.fstab.tachiai.platform.storage

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val PRIVATE_SECRET_LIMIT = 8192
private const val ENVELOPE_HEADER = 13 // Version plus 12-byte random GCM IV.

internal interface PrivateSecretStore {
    fun read(): ByteArray?
    fun write(plaintext: ByteArray)
    // The stored record's revision without decrypting it, or null when this
    // store cannot name one (#745). Callers hold the owning store's lock.
    fun revision(): PrivateSecretRevision? = null
    // One read with the revision of exactly the record it decrypted.
    fun readRevisioned(): RevisionedPrivateSecret = RevisionedPrivateSecret(read(), null)
    // One read of exactly one stored record, decrypted only by open() (#771).
    // Callers hold the owning store's lock for this call alone and open the
    // record after releasing it. A store that cannot defer decryption opens
    // the record here.
    fun readSealed(): SealedPrivateSecret = readRevisioned().let { read ->
        SealedPrivateSecret(read.plaintext == null, read.revision) { read.plaintext }
    }
}

// A digest of one exact encrypted record, holding no plaintext (#745). Every
// write encrypts with a fresh random IV, so any committed write by any process
// gives a new revision. An absent record has its own revision.
internal class PrivateSecretRevision private constructor(private val digest: ByteArray) {
    companion object {
        val ABSENT = PrivateSecretRevision(ByteArray(0))
        fun of(envelope: ByteArray?): PrivateSecretRevision =
            if (envelope == null) ABSENT else PrivateSecretRevision(MessageDigest.getInstance("SHA-256").digest(envelope))
    }
    override fun equals(other: Any?) = other is PrivateSecretRevision && MessageDigest.isEqual(digest, other.digest)
    override fun hashCode() = digest.contentHashCode()
    override fun toString() = "PrivateSecretRevision(redacted)"
}

internal class RevisionedPrivateSecret(val plaintext: ByteArray?, val revision: PrivateSecretRevision?) {
    override fun toString() = "RevisionedPrivateSecret(redacted)"
}

// A record read under its store's lock but not yet decrypted (#771). It holds
// only the encrypted envelope until open() returns the plaintext a locked
// read would have returned. absent means no record was stored.
internal class SealedPrivateSecret(val absent: Boolean, val revision: PrivateSecretRevision?,
    private val decrypt: () -> ByteArray?) {
    fun open(): ByteArray? = decrypt()
    override fun toString() = "SealedPrivateSecret(redacted)"
}

internal fun encryptPrivateSecret(key: SecretKey, binding: ByteArray, plaintext: ByteArray): ByteArray {
    require(plaintext.size <= PRIVATE_SECRET_LIMIT)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key) // Provider-generated, fresh IV each write.
    cipher.updateAAD(binding)
    check(cipher.iv.size == 12)
    val encrypted = cipher.doFinal(plaintext)
    return ByteBuffer.allocate(ENVELOPE_HEADER + encrypted.size)
        .put(1.toByte()).put(cipher.iv).put(encrypted).array()
}

internal fun decryptPrivateSecret(key: SecretKey, binding: ByteArray, envelope: ByteArray): ByteArray {
    require(envelope.size in (ENVELOPE_HEADER + 16)..(PRIVATE_SECRET_LIMIT + ENVELOPE_HEADER + 16))
    val input = ByteBuffer.wrap(envelope)
    require(input.get() == 1.toByte())
    val iv = ByteArray(12).also(input::get)
    val ciphertext = ByteArray(input.remaining()).also(input::get)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
    cipher.updateAAD(binding)
    return cipher.doFinal(ciphertext)
}
