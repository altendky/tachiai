package net.fstab.tachiai.platform.network

import java.io.IOException
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import org.junit.Assert.*
import org.junit.Test

class RouteProtocolTest {
    // Test-only protocol demonstrates extension without editing generic import,
    // storage, session or sharing code. It never opens a listener or uses JNI.
    private class FixtureProtocol(id: String = "fixture") : RouteProtocol {
        override val kind = ConnectionKind(id, "Fixture route")
        override val formatLabel = "fixture configuration"
        override val importHint = "a fixture configuration"
        var creations = 0
        var closes = 0
        override fun recognizes(text: String) = text.startsWith("fixture:")
        override fun parse(text: String) = ConnectionProfile(this, "fixture.example.test:443", text)
        override fun configurationIdentity(profile: ConnectionProfile) = profile.configuration
        override fun conflictIdentity(profile: ConnectionProfile) = "fixture-peer"
        override fun createBackend(profile: ConnectionProfile, security: RouteProxySecurity): RouteBackend {
            creations++
            return object : RouteBackend {
                override val proxyPort = 12345
                override fun close() { closes++ }
            }
        }
    }

    @Test fun registeredHandlerDrivesImportsMetadataStorageAndBackendCreation() {
        val protocol = FixtureProtocol()
        val protocols = RouteProtocols(listOf(protocol))
        assertEquals(protocol.formatLabel, protocols.formatLabel)
        assertEquals(protocol.importHint, protocols.importHint)
        assertEquals(protocol.kind.title, protocols.titles)
        val profile = protocols.parse("fixture:private-token".toByteArray())
        assertSame(protocol, profile.protocol)
        assertFalse(profile.toString().contains("private-token"))
        var stored: ByteArray? = null
        val secrets = object : PrivateSecretStore {
            override fun read() = stored?.copyOf()
            override fun write(plaintext: ByteArray) { stored = plaintext.copyOf() }
        }
        val store = ConnectionProfileStore(secrets, protocols = protocols)
        val summary = store.add("Fixture", profile).single()
        assertSame(protocol.kind, summary.kind)
        assertFalse(summary.toString().contains("private-token"))
        val snapshot = ConnectionProfileStore(secrets, protocols = protocols).selected(summary.id)
        assertEquals(profile.configuration, snapshot.configuration)
        assertSame(protocol, snapshot.protocol)
        val session = RouteSession.create(snapshot)
        assertEquals(1, protocol.creations)
        session.close()
        assertEquals(1, protocol.closes)
    }

    @Test fun sharedTextBoundsAndAmbiguousFormatsApplyToEveryHandler() {
        val protocol = FixtureProtocol()
        val protocols = RouteProtocols(listOf(protocol))
        val oversized = assertThrows(ConnectionImportFailure::class.java) {
            protocols.parse(("fixture:" + "é".repeat(CONNECTION_IMPORT_LIMIT / 2)).toByteArray())
        }
        assertEquals(ConnectionImportFailure.Category.SIZE, oversized.category)
        val binary = assertThrows(ConnectionImportFailure::class.java) { protocols.parse(byteArrayOf(0xC0.toByte())) }
        assertEquals(ConnectionImportFailure.Category.TEXT, binary.category)
        assertThrows(ConnectionImportFailure::class.java) { protocols.parse("fixture:secret\u0000".toByteArray()) }
        assertThrows(IllegalStateException::class.java) { protocols.restore("unknown", "fixture:x".toByteArray()) }
        val ambiguous = RouteProtocols(listOf(protocol, FixtureProtocol("other")))
        val error = assertThrows(ConnectionImportFailure::class.java) { ambiguous.parse("fixture:x".toByteArray()) }
        assertEquals(ConnectionImportFailure.Category.FORMAT, error.category)
        assertThrows(IllegalStateException::class.java) { RouteProtocols(listOf(protocol, FixtureProtocol())) }
    }

    @Test fun storageRejectsUnregisteredForeignAndInvalidProfilesWithoutChangingExistingRecords() {
        val registered = FixtureProtocol()
        val protocols = RouteProtocols(listOf(registered))
        var stored: ByteArray? = null
        var writes = 0
        val secrets = object : PrivateSecretStore {
            override fun read() = stored?.copyOf()
            override fun write(plaintext: ByteArray) { writes++; stored = plaintext.copyOf() }
        }
        val store = ConnectionProfileStore(secrets, protocols = protocols)
        val existing = store.add("Existing", protocols.parse("fixture:original".toByteArray())).single()
        val previous = checkNotNull(stored).copyOf()
        val rejected = listOf(
            FixtureProtocol("unregistered").parse("fixture:private-token"),
            FixtureProtocol().parse("fixture:private-token"), // Same ID, different owner.
            ConnectionProfile(registered, "untrusted endpoint", "unsupported:private-token"),
        )
        rejected.forEach { profile ->
            assertThrows(IllegalStateException::class.java) { store.add("Rejected", profile) }
            assertArrayEquals(previous, stored)
            assertEquals(1, writes)
            assertEquals(listOf(existing), store.summaries())
            assertEquals("fixture:original", store.selected(existing.id).configuration)
        }
    }

    @Test fun registrySharesWireGuardDuplicatesAndRejectsCompetingPeerSettings() {
        val original = parseConnectionProfile(fixtureWireGuard().toByteArray())
        val reordered = parseConnectionProfile(fixtureWireGuard().replace(
            "Address = 10.2.0.2/32, fd00::2/128", "Address = 10.2.0.2/32,fd00::2/128",
        ).toByteArray())
        val conflicting = parseConnectionProfile(fixtureWireGuard().replace("PersistentKeepalive = 25", "PersistentKeepalive = 20").toByteArray())
        var creations = 0
        val registry = RouteSessionRegistry { profile ->
            creations++
            RouteSession.create(profile) { _, _ -> object : RouteBackend {
                override val proxyPort = 12345
                override fun close() {}
            } }
        }
        val session = registry.acquire(original)
        try {
            assertSame(session, registry.acquire(reordered))
            assertThrows(IllegalStateException::class.java) { registry.acquire(conflicting) }
            assertEquals(1, creations)
        } finally { session.close() }
    }

    @Test fun conflictKeysAreProtocolScopedAndProxyProfilesHaveNoPeerConflicts() {
        val first = FixtureProtocol("first").parse("fixture:private-token")
        val second = FixtureProtocol("second").parse("fixture:private-token")
        assertNotEquals(routeConfigurationKey(first), routeConfigurationKey(second))
        assertNotEquals(routeConflictKey(first), routeConflictKey(second))
        val proxy = parseConnectionProfile("http://user:private-password@proxy.example.test:3128".toByteArray())
        assertNull(routeConflictKey(proxy))
        assertFalse(routeConfigurationKey(proxy).contains("private-password"))
    }

    @Test fun registrySharesFailuresWithoutRetryOrSystemFallback() {
        var creations = 0
        val registry = RouteSessionRegistry {
            creations++
            throw IOException("Route unavailable")
        }
        val profile = parseConnectionProfile("http://proxy.example.test:3128".toByteArray())
        repeat(2) { assertThrows(IOException::class.java) { registry.acquire(profile) } }
        assertEquals(1, creations)
    }
}
