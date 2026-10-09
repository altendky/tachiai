package net.fstab.tachiai.platform.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.CountDownLatch
import javax.crypto.spec.SecretKeySpec
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.encryptPrivateSecret
import net.fstab.tachiai.platform.storage.decryptPrivateSecret
import org.junit.Assert.*
import org.junit.Test

class ConnectionProfileStoreTest {
    private class MemorySecrets : PrivateSecretStore {
        var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
    }
    private fun profile() = parseConnectionProfile(fixtureWireGuard().toByteArray())

    @Test fun saveReloadAndDeleteHaveStableIdentityWithoutLeakingConfiguration() {
        val secrets = MemorySecrets()
        val store = ConnectionProfileStore(secrets)
        assertTrue(store.summaries().isEmpty())
        store.add("Japan", profile())
        val summary = store.summaries().single()
        assertEquals("Japan", summary.name)
        assertFalse(summary.toString().contains("PrivateKey"))
        assertEquals(listOf(summary), ConnectionProfileStore(secrets).summaries())
        store.remove(summary.id)
        assertTrue(ConnectionProfileStore(secrets).summaries().isEmpty())
        assertNotNull(secrets.bytes) // Explicit empty encrypted record, no plaintext fallback.
    }

    @Test fun corruptOrFullRecordsAreNotOverwrittenOrEvicted() {
        val secrets = MemorySecrets()
        val store = ConnectionProfileStore(secrets)
        repeat(8) { store.add("Connection $it", profile()) }
        val original = secrets.bytes!!.copyOf()
        assertThrows(IllegalStateException::class.java) { store.add("Ninth", profile()) }
        assertArrayEquals(original, secrets.bytes)
        secrets.bytes = byteArrayOf(1, 2)
        assertThrows(Exception::class.java) { store.add("Replacement", profile()) }
        assertArrayEquals(byteArrayOf(1, 2), secrets.bytes)
    }

    @Test fun totalStorageBoundAndFailedWritesKeepPreviousRecord() {
        val secrets = MemorySecrets()
        val store = ConnectionProfileStore(secrets)
        val large = fixtureWireGuard().replace("AllowedIPs = 0.0.0.0/0, ::/0",
            "AllowedIPs = " + List(16) { "2001:db8:1234:5678:1234:5678:1234:5678/128" }.joinToString(", "))
        val imported = parseConnectionProfile(large.toByteArray())
        repeat(6) { store.add("Long connection name $it", imported) }
        val previous = secrets.bytes!!.copyOf()
        assertTrue(previous.size <= PRIVATE_SECRET_LIMIT)
        assertThrows(IllegalStateException::class.java) { repeat(2) { store.add("Long connection name next", imported) } }
        assertTrue(secrets.bytes!!.size <= PRIVATE_SECRET_LIMIT)
        // A failed storage write must not be reported as success.
        val unavailable = object : PrivateSecretStore {
            override fun read() = previous
            override fun write(plaintext: ByteArray) { error("unavailable") }
        }
        assertThrows(IllegalStateException::class.java) { ConnectionProfileStore(unavailable).remove(store.summaries().first().id) }
    }

    @Test fun storesAcrossActivityInstancesSerializeUpdates() {
        val secrets = MemorySecrets()
        val ready = CountDownLatch(1)
        val threads = (1..2).map { index -> Thread { ready.await(); ConnectionProfileStore(secrets).add("Profile $index", profile()) }.apply { start() } }
        ready.countDown(); threads.forEach { it.join() }
        assertEquals(2, ConnectionProfileStore(secrets).summaries().size)
    }

    @Test fun connectionRecordsRoundTripThroughTheExistingEncryptedEnvelope() {
        val key = SecretKeySpec(ByteArray(32) { (it + 2).toByte() }, "AES")
        val binding = "test-only:connection-profiles:v1".toByteArray()
        var envelope: ByteArray? = null
        val secrets = object : PrivateSecretStore {
            override fun read() = envelope?.let { decryptPrivateSecret(key, binding, it) }
            override fun write(plaintext: ByteArray) { envelope = encryptPrivateSecret(key, binding, plaintext) }
        }
        val store = ConnectionProfileStore(secrets)
        store.add("Synthetic", profile())
        assertFalse(String(envelope!!, Charsets.ISO_8859_1).contains("PrivateKey"))
        assertFalse(String(checkNotNull(envelope), Charsets.ISO_8859_1).contains("vpn.example.test"))
        assertEquals("Synthetic", ConnectionProfileStore(secrets).summaries().single().name)
        store.remove(store.summaries().single().id)
        assertTrue(ConnectionProfileStore(secrets).summaries().isEmpty())
    }

    @Test fun committedMutationDoesNotNeedAnotherReadToReportSuccess() {
        var wrote = false
        val secrets = object : PrivateSecretStore {
            override fun read(): ByteArray? { check(!wrote); return null }
            override fun write(plaintext: ByteArray) { wrote = true }
        }
        assertEquals("Saved", ConnectionProfileStore(secrets).add("Saved", profile()).single().name)
    }

    @Test fun selectedRouteIsAReadOnlySnapshotAndMissingIdsNeverFallback() {
        val secrets = MemorySecrets()
        val store = ConnectionProfileStore(secrets)
        val imported = profile()
        val id = store.add("Selected", imported).single().id
        val original = secrets.bytes!!.copyOf()
        val snapshot = store.selected(id)
        assertEquals(imported.kind, snapshot.kind)
        assertEquals(imported.configuration, snapshot.configuration)
        assertArrayEquals(original, secrets.bytes)
        assertFalse(snapshot.toString().contains("PrivateKey"))
        assertThrows(IllegalStateException::class.java) { store.selected("missing") }
        assertArrayEquals(original, secrets.bytes)
        store.remove(id)
        assertThrows(IllegalStateException::class.java) { store.selected(id) }
    }

    @Test fun wireGuardRegistryIgnoresFieldOrderingButDetectsConflictingPeerSettings() {
        val original = profile()
        val reordered = parseConnectionProfile(fixtureWireGuard().replace(
            "AllowedIPs = 0.0.0.0/0, ::/0", "AllowedIPs = 0.0.0.0/0,::/0",
        ).lineSequence().let { lines ->
            val groups = lines.joinToString("\n").split("[Peer]")
            groups[0] + "[Peer]\n" + groups[1].lines().filter { it.isNotBlank() }.reversed().joinToString("\n")
        }.toByteArray())
        assertEquals(routeConfigurationKey(original), routeConfigurationKey(reordered))
        assertEquals(routeConflictKey(original), routeConflictKey(reordered))
        val different = parseConnectionProfile(fixtureWireGuard().replace("PersistentKeepalive = 25", "PersistentKeepalive = 20").toByteArray())
        assertNotEquals(routeConfigurationKey(original), routeConfigurationKey(different))
        assertEquals(routeConflictKey(original), routeConflictKey(different))
        val endpointChanged = parseConnectionProfile(fixtureWireGuard().replace("Endpoint = ", "Endpoint = alternative.").toByteArray())
        assertNotEquals(routeConfigurationKey(original), routeConfigurationKey(endpointChanged))
        assertEquals(routeConflictKey(original), routeConflictKey(endpointChanged))
    }

    @Test fun legacyRecordsAreReadWithoutRewritingAndExplicitMutationPreservesRemainingProfiles() {
        val wireGuardId = "12345678-1234-1234-1234-123456789abc"
        val proxyId = "abcdefab-abcd-abcd-abcd-abcdefabcdef"
        val secrets = MemorySecrets()
        secrets.bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(1); data.writeInt(2)
                data.writeUTF(wireGuardId); data.writeUTF("Legacy WireGuard"); data.writeUTF(fixtureWireGuard())
                data.writeUTF(proxyId); data.writeUTF("Legacy proxy"); data.writeUTF("http://user:secret@proxy.example.test:3128")
            }
        }.toByteArray()
        val legacy = secrets.bytes!!.copyOf()
        val store = ConnectionProfileStore(secrets)
        assertEquals(listOf(wireGuardId, proxyId), store.summaries().map { it.id })
        assertEquals(ConnectionKind.WIREGUARD, store.selected(wireGuardId).kind)
        assertEquals(ConnectionKind.HTTP_PROXY, store.selected(proxyId).kind)
        assertArrayEquals(legacy, secrets.bytes)

        store.remove(wireGuardId)
        DataInputStream(ByteArrayInputStream(secrets.bytes!!)).use { assertEquals(2, it.readInt()) }
        val reloaded = ConnectionProfileStore(secrets)
        assertEquals(proxyId, reloaded.summaries().single().id)
        assertEquals("Legacy proxy", reloaded.summaries().single().name)
        assertEquals("http://user:secret@proxy.example.test:3128", reloaded.selected(proxyId).configuration)
    }

    @Test fun unknownOrMismatchedPersistedProtocolsFailClosedWithoutOverwriting() {
        val secrets = MemorySecrets()
        listOf("unknown-protocol", "wireguard").forEach { protocolId ->
            secrets.bytes = ByteArrayOutputStream().also { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(2); data.writeInt(1)
                    data.writeUTF("12345678-1234-1234-1234-123456789abc"); data.writeUTF("Existing")
                    data.writeUTF(protocolId); data.writeUTF("http://proxy.example.test:3128")
                }
            }.toByteArray()
            val previous = secrets.bytes!!.copyOf()
            val store = ConnectionProfileStore(secrets)
            assertThrows(Exception::class.java) { store.summaries() }
            assertThrows(Exception::class.java) { store.add("New", profile()) }
            assertArrayEquals(previous, secrets.bytes)
        }
    }
}
