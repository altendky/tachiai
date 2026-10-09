package net.fstab.tachiai.platform.network

import java.util.Locale
import javax.crypto.spec.SecretKeySpec
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.platform.storage.decryptPrivateSecret
import net.fstab.tachiai.platform.storage.encryptPrivateSecret
import org.junit.Assert.*
import org.junit.Test

class OpenConnectRouteProtocolTest {
    private val protocols = RouteProtocols(listOf(HttpProxyRouteProtocol, OpenConnectRouteProtocol))

    // Synthetic Base64 bodies validate policy grammar only, never real crypto,
    // TLS, a native key match or an OpenConnect gateway connection.
    private fun fixture(server: String = "https://vpn.example.test/", bootstrap: String = "192.0.2.10") = """
        # Synthetic grammar fixture
        [OpenConnect]
        Server=$server
        Bootstrap=$bootstrap
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

    @Test fun defaultRegistryParsesAndRestoresProtectedSavedOpenConnectWithoutActivation() {
        val profile = parseConnectionProfile(fixture("https://vpn.example.test/private-sentinel").toByteArray())
        assertSame(OpenConnectRouteProtocol, profile.protocol)
        val restoredProfile = routeProtocols.restore("openconnect", profile.configuration.toByteArray())
        assertSame(OpenConnectRouteProtocol, restoredProfile.protocol)
        assertEquals(profile.configuration, restoredProfile.configuration)
        assertTrue(routeProtocols.formatLabel.contains(OpenConnectRouteProtocol.formatLabel))
        assertTrue(routeProtocols.importHint.contains(OpenConnectRouteProtocol.importHint))
        assertTrue(routeProtocols.titles.contains("OpenConnect"))

        val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val binding = "test-only:openconnect-profiles:v1".toByteArray()
        var envelope: ByteArray? = null
        var writes = 0
        val secrets = object : PrivateSecretStore {
            override fun read() = envelope?.let { decryptPrivateSecret(key, binding, it) }
            override fun write(plaintext: ByteArray) {
                envelope = encryptPrivateSecret(key, binding, plaintext)
                writes++
            }
        }
        // Omit the protocols argument: registration and persisted protocol-ID
        // restore must work through the actual app defaults, without JNI setup.
        val summary = ConnectionProfileStore(secrets).add("Owned OpenConnect", profile).single()
        assertEquals("openconnect", summary.kind.id)
        assertEquals("vpn.example.test:443", summary.endpoint)
        val encrypted = checkNotNull(envelope).copyOf()
        listOf("[OpenConnect]", "private-sentinel", "c3ludGhldGljLWtleQ==").forEach {
            assertFalse(String(encrypted, Charsets.ISO_8859_1).contains(it))
        }
        val reloaded = ConnectionProfileStore(secrets)
        assertEquals(listOf(summary), reloaded.summaries())
        assertSame(OpenConnectRouteProtocol, reloaded.selected(summary.id).protocol)
        assertEquals(profile.configuration, reloaded.selected(summary.id).configuration)
        assertArrayEquals(encrypted, envelope)
        assertEquals(1, writes) // Read/restore never resaves or activates.
    }

    @Test fun endpointSummaryExcludesPathBootstrapAndAllCredentialMaterial() {
        val profile = parse(fixture("https://VPN.Example.Test:00443/private-sentinel/path"))
        assertSame(OpenConnectRouteProtocol, profile.protocol)
        assertEquals("vpn.example.test:443", profile.endpoint)
        assertTrue(profile.configuration.contains("Server=https://vpn.example.test:443/private-sentinel/path"))
        assertEquals(profile.configuration, parse(profile.configuration).configuration)
        assertFalse(profile.toString().contains("private-sentinel"))
        assertFalse(profile.toString().contains("c3ludGhldGljLWtleQ=="))
        assertFalse(profile.endpoint.contains("192.0.2.10"))
        assertNull(routeConflictKey(profile))
    }

    @Test fun absentAndRootPathsHostCasePortWhitespaceAndOrderHaveStableIdentity() {
        val original = fixture("https://VPN.Example.Test:00443")
        val reordered = fixture().replace("Server=https://vpn.example.test/\nBootstrap=192.0.2.10",
            "Bootstrap = 192.0.2.10\nServer = https://vpn.example.test:443/").replace("\n", "\r\n")
        val expected = routeConfigurationKey(parse(original))
        assertEquals(expected, routeConfigurationKey(parse(reordered)))
        assertEquals(expected, routeConfigurationKey(parse(fixture("https://vpn.example.test"))))
        assertEquals("192.0.2.20:443", parse(fixture("https://192.0.2.20")).endpoint)
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("i.example.test:443", parse(fixture("https://I.Example.Test/")).endpoint)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun everyCredentialEndpointPathAndBootstrapChangeChangesIdentity() {
        val original = fixture()
        val key = routeConfigurationKey(parse(original))
        listOf(
            original.replace("c3ludGhldGljLWNh", "c3ludGhldGljLWNhLTI="),
            original.replace("c3ludGhldGljLWNlcnQ=", "c3ludGhldGljLWNlcnQtMg=="),
            original.replace("c3ludGhldGljLWtleQ==", "c3ludGhldGljLWtleS0y"),
            original.replace("vpn.example.test", "other.example.test"),
            original.replace("https://vpn.example.test/", "https://vpn.example.test:444/"),
            original.replace("https://vpn.example.test/", "https://vpn.example.test/group/"),
            original.replace("192.0.2.10", "192.0.2.11"),
        ).forEach { assertNotEquals(key, routeConfigurationKey(parse(it))) }
        val ca = original.substringAfter("<ca>\n").substringBefore("\n</ca>")
        val one = original.replace(ca, "$ca\n" + ca.replace("c3ludGhldGljLWNh", "c3ludGhldGljLWNhLTI="))
        val two = original.replace(ca, ca.replace("c3ludGhldGljLWNh", "c3ludGhldGljLWNhLTI=") + "\n$ca")
        assertNotEquals(routeConfigurationKey(parse(one)), routeConfigurationKey(parse(two)))
    }

    @Test fun headerRequiredFieldsAndBlocksCannotBeMissingDuplicatedOrNested() {
        val original = fixture()
        listOf("[OpenConnect]", "Server=https://vpn.example.test/", "Bootstrap=192.0.2.10").forEach {
            rejects(original.replace("$it\n", ""))
            rejects(original + "\n$it")
        }
        listOf("ca", "cert", "key").forEach { name ->
            val block = original.substringAfter("<$name>").substringBefore("</$name>")
            rejects(original.replace("<$name>$block</$name>", ""))
            rejects(original + "\n<$name>$block</$name>")
        }
        rejects(original.replace("</key>", ""))
        rejects(original.replace("</key>", "</cert>"))
        rejects(original.replace("c3ludGhldGljLWtleQ==", "<ca>"))
        rejects(original.replace("[OpenConnect]", "[OpenConnect]\n[Other]"))
    }

    @Test fun passwordsCookiesCommandsExternalFilesAndEncryptedOrMalformedPemAreRefused() {
        listOf("Password", "Username", "Cookie", "Token", "Script", "Command", "CSD", "Hostscan", "SSO", "CAFile", "CertificateFile", "KeyFile")
            .forEach { rejects(fixture() + "\n$it=private-sentinel", ConnectionImportFailure.Category.OPTION) }
        rejects(fixture().replace("-----BEGIN PRIVATE KEY-----", "-----BEGIN ENCRYPTED PRIVATE KEY-----")) // gitleaks:allow -- synthetic PEM grammar, no key
        rejects(fixture().replace("c3ludGhldGljLWtleQ==", "Proc-Type: 4,ENCRYPTED"))
        rejects(fixture().replace("c3ludGhldGljLWtleQ==", ""))
        rejects(fixture().replace("c3ludGhldGljLWtleQ==", "private-sentinel"))
        rejects(fixture().replace("-----END PRIVATE KEY-----", "-----END EC PRIVATE KEY-----"))
        val material = fixture().substringAfter("<key>\n").substringBefore("\n</key>")
        rejects(fixture().replace(material, "$material\n$material"))
        for (type in listOf("RSA PRIVATE KEY", "EC PRIVATE KEY", "DSA PRIVATE KEY")) {
            parse(fixture().replace("PRIVATE KEY", type))
        }
    }

    @Test fun endpointSubsetRefusesDnsAmbiguityRedirectComponentsAndUnsafePaths() {
        val invalid = listOf("http://vpn.example.test/", "HTTPS://vpn.example.test/", "https://user:private-sentinel@vpn.example.test/",
            "https://vpn.example.test/?private-sentinel", "https://vpn.example.test/#private-sentinel", "https://vpn.example.test/%2f",
            "https://vpn.example.test/../private-sentinel", "https://vpn.example.test/a..b", "https://vpn.example.test/a b",
            "https://vpn.example.test:0/", "https://vpn.example.test:65536/", "https://vpn.example.test:000443/", "https://vpn.example.test:+443/",
            "https://[::1]/", "https://vpn..example.test/", "https://.vpn.example.test/", "https://vpn.example.test./",
            "https://-vpn.example.test/", "https://vpn-.example.test/", "https://v_on.example.test/", "https://é.example.test/",
            "https://${"a".repeat(64)}.test/", "https://192.00.2.10/", "https://999.0.2.10/", "https://vpn.example.test/ # private-sentinel")
        invalid.forEach { rejects(fixture(it)) }
        parse(fixture("https://vpn.example.test/group_1/path-name/file.conf"))
        parse(fixture("https://vpn.example.test:65535//group/"))
        val prefix = "https://vpn.example.test:443/"
        parse(fixture(prefix + "a".repeat(512 - prefix.length)))
        rejects(fixture(prefix + "a".repeat(513 - prefix.length)))
        // Canonical default port insertion must still fit native's endpoint cap.
        rejects(fixture("https://vpn.example.test/" + "a".repeat(512 - "https://vpn.example.test/".length)))
    }

    @Test fun bootstrapIsStrictNumericUnicastIpv4AndNeverAHostname() {
        listOf("private-sentinel.example.test", "192.00.2.10", "192.0.2", "256.0.0.1", "+192.0.2.10", "192.0.2.10:443",
            "::1", "[::1]", "0.0.0.0", "0.1.2.3", "224.0.0.1", "239.255.255.255", "255.255.255.255")
            .forEach { rejects(fixture(bootstrap = it)) }
        listOf("127.0.0.1", "10.0.2.2", "192.0.2.10").forEach { parse(fixture(bootstrap = it)) }
    }

    @Test fun strictUtf8ControlAndByteBoundsApplyBeforePreview() {
        val canonical = parse(fixture()).configuration
        val padded = canonical + "\n#" + "x".repeat(CONNECTION_IMPORT_LIMIT - canonical.toByteArray().size - 2)
        parse(padded)
        rejects(padded + "x", ConnectionImportFailure.Category.SIZE)
        rejects(fixture() + "\n#" + "é".repeat(CONNECTION_IMPORT_LIMIT / 2), ConnectionImportFailure.Category.SIZE)
        rejects(fixture() + "\u0000private-sentinel", ConnectionImportFailure.Category.TEXT)
        rejects(fixture() + "\u007fprivate-sentinel", ConnectionImportFailure.Category.TEXT)
        assertThrows(ConnectionImportFailure::class.java) { protocols.parse(byteArrayOf(0xc0.toByte(), 0xaf.toByte())) }
    }

    @Test fun injectedProtectedStoreRoundTripAndInvalidMutationPreserveIsolationAndSavedBytes() {
        var saved: ByteArray? = null
        var writes = 0
        val secrets = object : PrivateSecretStore {
            override fun read() = saved?.copyOf()
            override fun write(plaintext: ByteArray) { saved = plaintext.copyOf(); writes++ }
        }
        val store = ConnectionProfileStore(secrets, protocols = protocols)
        val vpn = store.add("Owned OpenConnect", parse(fixture("https://vpn.example.test/private-sentinel"))).single()
        val proxy = store.add("Proxy", parse("http://user:private-sentinel@proxy.example.test:3128")).last()
        val restored = ConnectionProfileStore(secrets, protocols = protocols)
        assertSame(OpenConnectRouteProtocol, restored.selected(vpn.id).protocol)
        assertSame(HttpProxyRouteProtocol, restored.selected(proxy.id).protocol)
        assertEquals(parse(fixture("https://vpn.example.test/private-sentinel")).configuration, restored.selected(vpn.id).configuration)
        assertEquals("vpn.example.test:443", restored.summaries().first().endpoint)
        assertFalse(restored.summaries().toString().contains("private-sentinel"))
        assertFalse(restored.summaries().toString().contains("c3ludGhldGljLWtleQ=="))
        assertFalse(restored.summaries().toString().contains("192.0.2.10"))
        val before = checkNotNull(saved).copyOf()
        assertThrows(ConnectionImportFailure::class.java) {
            store.add("Rejected", ConnectionProfile(OpenConnectRouteProtocol, "untrusted", fixture() + "\nPassword=private-sentinel"))
        }
        assertArrayEquals(before, saved)
        assertEquals(2, writes)
        assertEquals(2, restored.summaries().size)
    }
}
