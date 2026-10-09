package net.fstab.tachiai.platform.network

import javax.crypto.spec.SecretKeySpec
import net.fstab.tachiai.feature.connections.ProviderSetupStore
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.platform.storage.decryptPrivateSecret
import net.fstab.tachiai.platform.storage.encryptPrivateSecret
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.SourceRouteChoice
import net.fstab.tachiai.presentation.SourceRouteMode
import net.fstab.tachiai.presentation.defaultSourceSetups
import org.junit.Assert.*
import org.junit.Test

class Socks5RouteProtocolTest {
    private fun parse(text: String) = parseConnectionProfile(text.toByteArray(Charsets.UTF_8))

    @Test fun canonicalIdentityNormalizesHostPortPathAndCredentialEncodingWithoutLeakingSecrets() {
        val first = parse("socks5://%75ser:p%40ss%2bword@PROXY.example.test:01080/")
        val second = parse("socks5://user:p%40ss%2Bword@proxy.example.test:1080")
        assertSame(ConnectionKind.SOCKS5, first.kind)
        assertSame(Socks5RouteProtocol, first.protocol)
        assertEquals("proxy.example.test:1080", first.endpoint)
        assertEquals("socks5://user:p%40ss%2Bword@proxy.example.test:1080", first.configuration)
        assertEquals(first.configuration, second.configuration)
        assertEquals(routeConfigurationKey(first), routeConfigurationKey(second))
        assertNull(routeConflictKey(first))
        assertFalse(first.toString().contains("user"))
        assertFalse(first.toString().contains("p%40ss"))
        assertFalse(first.endpoint.contains('@'))
        assertTrue(routeProtocols.formatLabel.contains(Socks5RouteProtocol.formatLabel))
        assertTrue(routeProtocols.importHint.contains(Socks5RouteProtocol.importHint))
    }

    @Test fun anonymousIpv4Ipv6AndUtf8CredentialsUseTheSameStrictFormat() {
        assertEquals("127.0.0.1:1080", parse("socks5://127.0.0.1:1080").endpoint)
        assertEquals("[2001:db8::a]:1080", parse("socks5://[2001:DB8::A]:1080/").endpoint)
        val unicode = parse("socks5://calf%C3%A9:%F0%9F%94%92@proxy.example.test:1080")
        assertEquals(unicode.configuration, parse(unicode.configuration).configuration)
        assertEquals("proxy.example.test:1080", unicode.endpoint)
        val maximum = parse("socks5://${"u".repeat(255)}:${"p".repeat(255)}@proxy.example.test:1080")
        assertEquals(maximum.configuration, parse(maximum.configuration).configuration)
    }

    @Test fun malformedAuthorityUnsupportedOptionsAndInvalidDecodedCredentialsFailWithoutEchoingInput() {
        listOf(
            "socks5://proxy.example.test", "socks5://proxy.example.test:0", "socks5://proxy.example.test:65536",
            "socks5://proxy.example.test:1080/path", "socks5://proxy.example.test:1080/%2F",
            "socks5://proxy.example.test:1080?", "socks5://proxy.example.test:1080#",
            "socks5://user@proxy.example.test:1080", "socks5://user:@proxy.example.test:1080",
            "socks5://:private-password@proxy.example.test:1080", "socks5://user:private:password@proxy.example.test:1080",
            "socks5://user:p+word@proxy.example.test:1080", "socks5://user:p@ss@proxy.example.test:1080",
            "socks5://user:%00@proxy.example.test:1080", "socks5://user:%0D%0A@proxy.example.test:1080",
            "socks5://user:%C0%AF@proxy.example.test:1080", "socks5://user:%FF@proxy.example.test:1080",
            "socks5://user:%E2%80%AE@proxy.example.test:1080", "socks5://user:%zz@proxy.example.test:1080",
            "socks5://user:%F3%A0%80%81@proxy.example.test:1080",
            "socks5://user:%@proxy.example.test:1080", "socks5://999.1.1.1:1080",
            "socks5://[fe80::1%25eth0]:1080", "socks5://${"u".repeat(256)}:p@proxy.example.test:1080",
            "socks5://u:${"%C3%A9".repeat(128)}@proxy.example.test:1080",
        ).forEach { raw ->
            val failure = assertThrows(ConnectionImportFailure::class.java) { parse(raw) }
            assertEquals(ConnectionImportFailure.Category.VALUE, failure.category)
            assertFalse(failure.toString().contains("private-password"))
            assertFalse(failure.toString().contains("proxy.example.test"))
        }
        val unsupported = assertThrows(ConnectionImportFailure::class.java) { parse("socks5h://proxy.example.test:1080") }
        assertEquals(ConnectionImportFailure.Category.FORMAT, unsupported.category)
        val oversized = assertThrows(ConnectionImportFailure::class.java) { parse("socks5://" + "x".repeat(CONNECTION_IMPORT_LIMIT)) }
        assertEquals(ConnectionImportFailure.Category.SIZE, oversized.category)
    }

    @Test fun socks5UsesProtectedVersionedStorageAlongsideExistingRoutesAndDeletedSelectionsFailClosed() {
        val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val binding = "test-only:connection-profiles:v1".toByteArray()
        var envelope: ByteArray? = null
        val secrets = object : PrivateSecretStore {
            override fun read() = envelope?.let { decryptPrivateSecret(key, binding, it) }
            override fun write(plaintext: ByteArray) { envelope = encryptPrivateSecret(key, binding, plaintext) }
        }
        val store = ConnectionProfileStore(secrets)
        val wireGuard = store.add("WireGuard", parse(fixtureWireGuard())).single()
        val http = store.add("HTTP", parse("http://proxy.example.test:3128")).last()
        val profile = parse("socks5://fixture:private-password@proxy.example.test:1080")
        val saved = store.add("SOCKS", profile).last()
        assertSame(ConnectionKind.SOCKS5, saved.kind)
        assertEquals("proxy.example.test:1080", saved.endpoint)
        assertFalse(saved.toString().contains("private-password"))
        val encrypted = checkNotNull(envelope).copyOf()
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("private-password"))
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("socks5://"))
        val reloaded = ConnectionProfileStore(secrets)
        assertEquals(listOf(wireGuard, http, saved), reloaded.summaries())
        assertEquals(profile.configuration, reloaded.selected(saved.id).configuration)
        assertArrayEquals(encrypted, envelope) // Reads never migrate or activate.
        reloaded.remove(saved.id)
        assertEquals(listOf(wireGuard, http), store.summaries())
        assertThrows(IllegalStateException::class.java) { store.selected(saved.id) }
    }

    @Test fun registrySharesCanonicalDuplicatesAndSeparatesEndpointsAndCredentials() {
        var created = 0
        var closed = 0
        val registry = RouteSessionRegistry { profile ->
            RouteSession.create(profile) { selected, _ ->
                assertSame(Socks5RouteProtocol, selected.protocol)
                created++
                object : RouteBackend {
                    override val proxyPort = 12000 + created
                    override fun close() { closed++ }
                }
            }
        }
        val first = registry.acquire(parse("socks5://u:p@proxy.example.test:1080"))
        val second = registry.acquire(parse("socks5://u:other@proxy.example.test:1080"))
        val third = registry.acquire(parse("socks5://u:p@other.example.test:1080"))
        try {
            assertSame(first, registry.acquire(parse("socks5://%75:p@PROXY.example.test:01080/")))
            assertNotSame(first, second)
            assertNotSame(first, third)
            assertEquals(3, created)
            first.close()
            assertEquals(1, closed)
            assertFalse(second.isSystem)
            assertFalse(third.isSystem)
            assertEquals(12002, second.proxyPort)
        } finally { first.close(); second.close(); third.close() }
        assertEquals(3, closed)
    }

    @Test fun providerAssignmentStoresOnlyTheSavedSocksReferenceAndPreservesOtherProviderRoute() {
        class Memory : PrivateSecretStore {
            var bytes: ByteArray? = null
            override fun read() = bytes?.copyOf()
            override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
        }
        val routeSecrets = Memory()
        val routes = ConnectionProfileStore(routeSecrets)
        val saved = routes.add("SOCKS", parse("socks5://fixture:private-password@proxy.example.test:1080")).single()
        val original = checkNotNull(routeSecrets.bytes).copyOf()
        val reference = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, saved.id, saved.name)
        val settings = Memory()
        val providers = ProviderSetupStore(settings)
        providers.save(PrototypeService.ABEMA, SourceRouteChoice.system, defaultSourceSetups())
        providers.save(PrototypeService.TWITCH, reference, defaultSourceSetups())
        val snapshot = ProviderSetupStore(settings).read(defaultSourceSetups())
        assertEquals(SourceRouteChoice.system, snapshot[PrototypeService.ABEMA]!!.route)
        assertEquals(reference, snapshot[PrototypeService.TWITCH]!!.route)
        val selected = routes.selected(checkNotNull(snapshot[PrototypeService.TWITCH]!!.route?.connectionId))
        assertSame(Socks5RouteProtocol, selected.protocol)
        assertFalse(String(checkNotNull(settings.bytes)).contains("private-password"))
        assertFalse(String(checkNotNull(settings.bytes)).contains("socks5://"))
        assertArrayEquals(original, routeSecrets.bytes) // Assignment never activates or rewrites route secrets.
    }
}
