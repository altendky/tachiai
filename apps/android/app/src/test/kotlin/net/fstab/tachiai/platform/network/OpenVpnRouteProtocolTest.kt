package net.fstab.tachiai.platform.network

import net.fstab.tachiai.platform.storage.PrivateSecretStore
import org.junit.Assert.*
import org.junit.Test

class OpenVpnRouteProtocolTest {
    private val protocols = RouteProtocols(listOf(WireGuardRouteProtocol, HttpProxyRouteProtocol, OpenVpnRouteProtocol))

    // Deliberately synthetic PEM bodies: these exercise only policy grammar.
    // They are not cryptographically valid credentials and must not be used to
    // claim native Core validation, TLS or tunnel connectivity has succeeded.
    private fun fixture(remote: String = "192.0.2.10", port: String = "1194", proto: String = "udp") = """
        # Synthetic, non-cryptographic grammar fixture
        client
        dev tun
        proto $proto
        remote $remote $port
        nobind
        remote-cert-tls server
        verify-x509-name owned-vpn.example.test name
        tls-version-min 1.2
        <ca>
        -----BEGIN CERTIFICATE-----
        c3ludGhldGljLWNh
        -----END CERTIFICATE-----
        </ca>
        <cert>
        -----BEGIN CERTIFICATE-----
        c3ludGhldGljLWNlcnQ=
        -----END CERTIFICATE-----
        </cert>
        <key>
        -----BEGIN PRIVATE KEY-----
        c3ludGhldGljLWtleQ==
        -----END PRIVATE KEY-----
        </key>
    """.trimIndent()

    private fun parse(text: String) = protocols.parse(text.toByteArray(Charsets.UTF_8))
    private fun rejects(text: String, category: ConnectionImportFailure.Category? = null) {
        val error = assertThrows(ConnectionImportFailure::class.java) { parse(text) }
        category?.let { assertEquals(it, error.category) }
        assertFalse(error.message.orEmpty().contains("private-sentinel"))
        assertFalse(error.message.orEmpty().contains("c3ludGhldGljLWtleQ=="))
    }

    @Test fun acceptedPolicyHasEndpointOnlyMetadataAndStableRoundTrip() {
        val profile = parse(fixture(port = "01194"))
        assertSame(OpenVpnRouteProtocol, profile.protocol)
        assertEquals("192.0.2.10:1194", profile.endpoint)
        assertTrue(profile.configuration.contains("remote 192.0.2.10 1194"))
        assertFalse(profile.configuration.contains("Synthetic"))
        assertFalse(profile.toString().contains("c3ludGhldGljLWtleQ=="))
        assertFalse(profile.endpoint.contains("owned-vpn.example.test"))
        assertEquals(profile.configuration, parse(profile.configuration).configuration)
        assertNull(routeConflictKey(profile))
        assertEquals("[::1]:443", parse(fixture("::1", "443", "tcp-client")).endpoint)
        assertTrue(parse(fixture().replace("tls-version-min 1.2", "tls-version-min 1.3")).configuration.contains("tls-version-min 1.3"))
        parse(fixture().replace("nobind\n", ""))
    }

    @Test fun reorderedOptionsCommentsWhitespacePortAndIpSpellingShareOneCanonicalIdentity() {
        val original = fixture("2001:db8::a", "1194")
        val firstBlock = original.indexOf("<ca>")
        val options = original.substring(0, firstBlock).lineSequence().filter { !it.startsWith('#') && it.isNotEmpty() }.toList()
        val reordered = "; Another whole-line comment\r\n" + options.reversed().joinToString("\r\n") {
            "\t" + it.replace(' ', '\t').replace("2001:db8::a", "2001:0DB8:0:0:0:0:0:000A").replace("1194", "01194") + "\t"
        } + "\r\n" + original.substring(firstBlock).replace("\n", "\r\n")
        val first = parse(original)
        val second = parse(reordered)
        assertEquals("[2001:db8::a]:1194", second.endpoint)
        assertEquals(first.configuration, second.configuration)
        assertEquals(routeConfigurationKey(first), routeConfigurationKey(second))
        assertEquals("[::c000:201]:1194", parse(fixture("::192.0.2.1")).endpoint)
        assertEquals("[2001:db8::1:0:0:1]:1194", parse(fixture("2001:db8:0:0:1:0:0:1")).endpoint)
    }

    @Test fun credentialAndSecurityChangesDoNotShareCanonicalIdentity() {
        val original = fixture()
        val first = parse(original)
        val changes = listOf(
            original.replace("c3ludGhldGljLWtleQ==", "c3ludGhldGljLWtleS0y"),
            original.replace("c3ludGhldGljLWNlcnQ=", "c3ludGhldGljLWNlcnQtMg=="),
            original.replace("c3ludGhldGljLWNh", "c3ludGhldGljLWNhLTI="),
            original.replace("owned-vpn.example.test", "another-owned-vpn.example.test"),
            original.replace("tls-version-min 1.2", "tls-version-min 1.3"),
            original.replace("proto udp", "proto tcp-client"),
            original.replace("1194", "443"),
            original.replace("192.0.2.10", "192.0.2.11"),
        )
        changes.forEach { assertNotEquals(routeConfigurationKey(first), routeConfigurationKey(parse(it))) }
        val ordered = original.replace("c3ludGhldGljLWNh", "c3ludGhldGljLWNh\nc3ludGhldGljLWNlcnQ=")
        val reversed = original.replace("c3ludGhldGljLWNh", "c3ludGhldGljLWNlcnQ=\nc3ludGhldGljLWNh")
        assertNotEquals(routeConfigurationKey(parse(ordered)), routeConfigurationKey(parse(reversed)))
        assertFalse(routeConfigurationKey(first).contains("c3ludGhldGljLWtleQ=="))
    }

    @Test fun everyRequiredDirectiveAndBlockIsMandatoryAndCannotBeDuplicated() {
        val original = fixture()
        val directives = listOf("client", "dev tun", "proto udp", "remote 192.0.2.10 1194",
            "remote-cert-tls server", "verify-x509-name owned-vpn.example.test name", "tls-version-min 1.2")
        directives.forEach {
            rejects(original.replace("$it\n", ""))
            rejects(original + "\n$it")
        }
        rejects(original + "\nnobind")
        listOf("ca", "cert", "key").forEach { name ->
            val block = original.substringAfter("<$name>").substringBefore("</$name>")
            rejects(original.replace("<$name>$block</$name>", ""))
            rejects(original + "\n<$name>$block</$name>")
        }
    }

    @Test fun passwordsExternalFilesCommandsCompressionAndUnknownOptionsAreRefused() {
        val extras = listOf("auth-user-pass", "auth-user-pass private-sentinel", "askpass", "pkcs12 private-sentinel",
            "ca private-sentinel", "cert private-sentinel", "key private-sentinel", "tls-auth private-sentinel",
            "tls-crypt private-sentinel", "up private-sentinel", "down private-sentinel", "script-security 2",
            "management 127.0.0.1 9999", "plugin private-sentinel", "compress lz4", "comp-lzo", "remote-random",
            "redirect-gateway def1", "data-ciphers AES-256-GCM", "dhcp-option DNS 1.1.1.1", "private-sentinel value")
        extras.forEach { rejects(fixture() + "\n$it") }
        rejects(fixture().replace("-----BEGIN PRIVATE KEY-----", "-----BEGIN ENCRYPTED PRIVATE KEY-----"))
        rejects(fixture().replace("c3ludGhldGljLWtleQ==", "Proc-Type: 4,ENCRYPTED"))
        rejects(fixture().replace("c3ludGhldGljLWtleQ==", "<cert>"))
        rejects(fixture().replace("</key>", "</cert>"))
        rejects(fixture().replace("</key>", ""))
    }

    @Test fun invalidArgumentsQuotingInlineCommentsAndInvalidPortsAreRefused() {
        listOf("client value", "dev tun0", "dev tap", "proto tcp", "proto udp6", "remote-cert-tls client",
            "tls-version-min 1.1", "verify-x509-name owned-vpn.example.test name-prefix",
            "verify-x509-name private-sentinel/name name", "verify-x509-name \"owned-vpn.example.test\" name",
            "verify-x509-name ${"a".repeat(254)} name", "nobind value").forEach { replacement ->
            val name = replacement.substringBefore(' ')
            val original = fixture().lineSequence().first { it == name || it.startsWith("$name ") }
            rejects(fixture().replace(original, replacement))
        }
        rejects(fixture().replace("proto udp", "proto udp # private-sentinel"))
        rejects(fixture().replace("proto udp", "proto udp ; private-sentinel"))
        listOf("0", "65536", "000001", "+1194", "-1", "1.5", "abc").forEach { rejects(fixture(port = it)) }
    }

    @Test fun numericRemoteParserRejectsHostnamesScopesMalformedAndExcludedAddressesWithoutDns() {
        val invalid = listOf("private-sentinel.example.test", "0.0.0.0", "0.1.2.3", "224.0.0.1", "239.255.255.255",
            "255.255.255.255", "192.00.2.10", "192.0.2", "256.0.0.1", "::", "0:0:0:0:0:0:0:0",
            "ff00::1", "fe80::1", "febf::1", "fe80::1%eth0", "::ffff:192.0.2.1", "0:0:0:0:0:ffff:c000:201",
            "[2001:db8::1]", "2001::db8::1", "2001:db8:::1", "2001:db8::1:", "1:2:3:4:5:6:7",
            "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7::8", "192.0.2.1::", "2001:db8::gggg")
        invalid.forEach { rejects(fixture(it)) }
        listOf("127.0.0.1", "169.254.1.1", "240.0.0.1", "::1", "fd00::1", "fec0::1", "2001:db8::192.0.2.1")
            .forEach { parse(fixture(it)) }
    }

    @Test fun utf8ByteBoundsAndControlChecksApplyBeforePreview() {
        val base = fixture()
        val canonical = parse(base).configuration
        val padded = canonical + "\n#" + "x".repeat(CONNECTION_IMPORT_LIMIT - canonical.toByteArray().size - 2)
        assertEquals(CONNECTION_IMPORT_LIMIT, padded.toByteArray().size)
        parse(padded)
        rejects(padded + "x", ConnectionImportFailure.Category.SIZE)
        rejects(base + "\n#" + "é".repeat(CONNECTION_IMPORT_LIMIT / 2), ConnectionImportFailure.Category.SIZE)
        rejects(base + "\u0000private-sentinel", ConnectionImportFailure.Category.TEXT)
        rejects(base + "\u000bprivate-sentinel", ConnectionImportFailure.Category.TEXT)
        assertThrows(ConnectionImportFailure::class.java) { protocols.parse(byteArrayOf(0xc0.toByte(), 0xaf.toByte())) }
    }

    @Test fun mixedProtectedStoreRoundTripKeepsCredentialsOutOfSummariesAndRefusesInvalidMutation() {
        var saved: ByteArray? = null
        var writes = 0
        val secretStore = object : PrivateSecretStore {
            override fun read() = saved?.copyOf()
            override fun write(plaintext: ByteArray) { saved = plaintext.copyOf(); writes++ }
        }
        val store = ConnectionProfileStore(secretStore, protocols = protocols)
        val vpn = store.add("Owned OpenVPN", parse(fixture())).single()
        val proxy = store.add("Proxy", parse("http://user:private-sentinel@proxy.example.test:3128")).last()
        val restored = ConnectionProfileStore(secretStore, protocols = protocols)
        assertEquals("openvpn", restored.selected(vpn.id).kind.id)
        assertEquals(parse(fixture()).configuration, restored.selected(vpn.id).configuration)
        assertSame(HttpProxyRouteProtocol, restored.selected(proxy.id).protocol)
        assertEquals("192.0.2.10:1194", restored.summaries().first().endpoint)
        assertFalse(restored.summaries().toString().contains("c3ludGhldGljLWtleQ=="))
        assertFalse(restored.summaries().toString().contains("private-sentinel"))
        assertFalse(restored.summaries().toString().contains("owned-vpn.example.test"))
        val before = checkNotNull(saved).copyOf()
        assertThrows(ConnectionImportFailure::class.java) {
            store.add("Rejected", ConnectionProfile(OpenVpnRouteProtocol, "untrusted", fixture() + "\nup private-sentinel"))
        }
        assertArrayEquals(before, saved)
        assertEquals(2, writes)
        assertEquals(2, restored.summaries().size)
    }
}
