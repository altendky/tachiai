package net.fstab.tachiai.platform.storage

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class PrivateAuthorizationSlotTest {
    @Test fun `remaining provider bindings have distinct identities`() {
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

    @Test fun `catalog bindings include default Twitch and cannot decrypt playback or another instance`() {
        val defaultId = net.fstab.tachiai.presentation.defaultProviderInstanceId(net.fstab.tachiai.presentation.PrototypeService.TWITCH)
        val otherId = "12345678-1234-1234-1234-123456789abc"
        val own = twitchCatalogInstanceBindingName(defaultId)
        val other = twitchCatalogInstanceBindingName(otherId)
        assertNotEquals(own, other)
        assertThrows(IllegalArgumentException::class.java) { twitchCatalogInstanceBindingName("not-a-uuid") }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        fun binding(name: String) = "net.fstab.tachiai:$name:v1".toByteArray()
        val encrypted = encryptPrivateSecret(key, binding(own), "synthetic-pair".toByteArray())
        (PrivateAuthorizationSlot.entries.map { it.bindingName } + other + twitchProviderInstanceBindingName(otherId)).forEach {
            assertThrows(AEADBadTagException::class.java) { decryptPrivateSecret(key, binding(it), encrypted) }
        }
    }
}
