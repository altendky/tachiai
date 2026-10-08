package net.fstab.tachiai.platform.storage

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class PrivateAuthorizationSlotTest {
    @Test fun `original binding is unchanged and provider slot has a different identity`() {
        assertEquals("twitch-own-authorization", PrivateAuthorizationSlot.TWITCH_OWN.bindingName)
        assertEquals(PrivateAuthorizationSlot.entries.size, PrivateAuthorizationSlot.entries.map { it.bindingName }.toSet().size)
        PrivateAuthorizationSlot.entries.forEach { assertTrue(Regex("[a-z-]+").matches(it.bindingName)) }
    }

    @Test fun `ciphertext cannot authenticate under the opposite slot binding`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        fun binding(slot: PrivateAuthorizationSlot) = "net.fstab.tachiai:${slot.bindingName}:v1".toByteArray()
        PrivateAuthorizationSlot.entries.forEach { source ->
            val ciphertext = encryptPrivateSecret(key, binding(source), "synthetic-session".toByteArray())
            PrivateAuthorizationSlot.entries.filter { it != source }.forEach { other ->
                assertThrows(AEADBadTagException::class.java) { decryptPrivateSecret(key, binding(other), ciphertext) }
            }
        }
    }
}
