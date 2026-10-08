package net.fstab.tachiai.platform.storage

import javax.crypto.KeyGenerator
import javax.crypto.AEADBadTagException
import org.junit.Assert.*
import org.junit.Test

class PrivateSecretStoreTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val binding = "net.fstab.tachiai:twitch-own-authorization:v1".toByteArray()
    private val plaintext = "fixture-token-not-a-provider-secret".toByteArray()

    @Test fun `ciphertext round trips without containing plaintext`() {
        val encrypted = encryptPrivateSecret(key, binding, plaintext)
        assertArrayEquals(plaintext, decryptPrivateSecret(key, binding, encrypted))
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains(plaintext.toString(Charsets.UTF_8)))
    }

    @Test fun `writes use fresh IVs including Forget empty records`() {
        val first = encryptPrivateSecret(key, binding, plaintext)
        val second = encryptPrivateSecret(key, binding, plaintext)
        assertFalse(first.copyOfRange(1, 13).contentEquals(second.copyOfRange(1, 13)))
        assertArrayEquals(byteArrayOf(), decryptPrivateSecret(key, binding, encryptPrivateSecret(key, binding, byteArrayOf())))
    }

    @Test fun `tampering wrong binding and wrong key fail authentication`() {
        val encrypted = encryptPrivateSecret(key, binding, plaintext)
        val changed = encrypted.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { decryptPrivateSecret(key, binding, changed) }
        assertThrows(AEADBadTagException::class.java) { decryptPrivateSecret(key, "other-app".toByteArray(), encrypted) }
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertThrows(AEADBadTagException::class.java) { decryptPrivateSecret(other, binding, encrypted) }
    }

    @Test fun `versions truncation and sizes are bounded before decryption`() {
        val encrypted = encryptPrivateSecret(key, binding, plaintext)
        assertThrows(IllegalArgumentException::class.java) { decryptPrivateSecret(key, binding, encrypted.copyOf().apply { this[0] = 2 }) }
        assertThrows(IllegalArgumentException::class.java) { decryptPrivateSecret(key, binding, ByteArray(28)) }
        assertThrows(IllegalArgumentException::class.java) { decryptPrivateSecret(key, binding, ByteArray(PRIVATE_SECRET_LIMIT + 30)) }
        assertThrows(IllegalArgumentException::class.java) { encryptPrivateSecret(key, binding, ByteArray(PRIVATE_SECRET_LIMIT + 1)) }
    }
}
