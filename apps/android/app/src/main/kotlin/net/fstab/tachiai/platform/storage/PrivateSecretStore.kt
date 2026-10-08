package net.fstab.tachiai.platform.storage

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val PRIVATE_SECRET_LIMIT = 8192
private const val ENVELOPE_HEADER = 13 // Version plus 12-byte random GCM IV.

internal interface PrivateSecretStore {
    fun read(): ByteArray?
    fun write(plaintext: ByteArray)
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
