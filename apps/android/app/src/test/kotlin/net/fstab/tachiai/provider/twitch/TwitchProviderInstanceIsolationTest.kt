package net.fstab.tachiai.provider.twitch

import kotlinx.coroutines.runBlocking
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.platform.storage.PrivateAuthorizationSlot
import net.fstab.tachiai.platform.storage.twitchProviderInstanceBindingName
import net.fstab.tachiai.platform.storage.encryptPrivateSecret
import net.fstab.tachiai.platform.storage.decryptPrivateSecret
import javax.crypto.spec.SecretKeySpec
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test

class TwitchProviderInstanceIsolationTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
    }
    @Test fun newInstanceStartsEmptyAndForgetDoesNotInvalidateAnotherInstancesLeaseOrPendingSave() = runBlocking {
        val historical = Memory()
        val fresh = Memory()
        fun cache(store: Memory) = TwitchSavedAuthorization(store, { 1_000_000L }, { 1_000L },
            TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL)
        val default = cache(historical); val other = cache(fresh)
        assertEquals(SavedAuthorizationState.SAVED, default.saveValidated("invented-default", 101_000L, default.revision()))
        val original = historical.bytes!!.copyOf()
        val defaultLease = default.read().lease!!
        assertEquals(SavedAuthorizationState.MISSING, other.read().state)
        assertNull(fresh.bytes)
        assertEquals(SavedAuthorizationState.SAVED, other.saveValidated("invented-second", 101_000L, other.revision()))
        val otherLease = other.read().lease!!
        val pendingDefault = default.revision(); val pendingOther = other.revision()
        assertFalse(other.isCurrent(defaultLease)); assertFalse(default.isCurrent(otherLease))
        assertEquals(SavedAuthorizationState.FORGOTTEN, other.forget())
        assertFalse(other.isCurrent(otherLease)); assertTrue(default.isStoredCurrent(defaultLease))
        assertArrayEquals(original, historical.bytes)
        assertEquals(SavedAuthorizationState.SUPERSEDED, other.saveValidated("invented-late", 101_000L, pendingOther))
        assertEquals(SavedAuthorizationState.SAVED, default.saveValidated("invented-renewed", 101_000L, pendingDefault))
        assertEquals(SavedAuthorizationState.MISSING, other.read().state)
    }
    @Test fun onlyCanonicalNewUuidCanSelectASeparateProtectedBinding() {
        val first = twitchProviderInstanceBindingName("12345678-1234-1234-1234-123456789abc")
        val second = twitchProviderInstanceBindingName("12345678-1234-1234-1234-123456789abd")
        assertNotEquals(first, second)
        assertNotEquals(PrivateAuthorizationSlot.TWITCH_PROVIDER_SMART_TV_LOCAL.bindingName, first)
        PrototypeService.entries.forEach { service ->
            assertThrows(IllegalArgumentException::class.java) { twitchProviderInstanceBindingName(defaultProviderInstanceId(service)) }
        }
        assertThrows(IllegalArgumentException::class.java) { twitchProviderInstanceBindingName("../old-local-grant") }
        assertThrows(IllegalArgumentException::class.java) { twitchProviderInstanceBindingName("12345678-1234-1234-1234-123456789ABC") }
    }
    @Test fun encryptedGrantCannotBeTransplantedBetweenInstanceOrHistoricalBindings() {
        val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        fun aad(name: String) = "net.fstab.tachiai:$name:v1".toByteArray()
        val first = aad(twitchProviderInstanceBindingName("12345678-1234-1234-1234-123456789abc"))
        val second = aad(twitchProviderInstanceBindingName("12345678-1234-1234-1234-123456789abd"))
        val historical = aad(PrivateAuthorizationSlot.TWITCH_PROVIDER_SMART_TV_LOCAL.bindingName)
        val grant = encodeSavedTwitchToken(SavedTwitchToken("invented-encrypted", 1_000_000L, 1_100_000L),
            TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL)
        val encrypted = encryptPrivateSecret(key, first, grant)
        assertArrayEquals(grant, decryptPrivateSecret(key, first, encrypted))
        assertThrows(Exception::class.java) { decryptPrivateSecret(key, second, encrypted) }
        assertThrows(Exception::class.java) { decryptPrivateSecret(key, historical, encrypted) }
    }
}
