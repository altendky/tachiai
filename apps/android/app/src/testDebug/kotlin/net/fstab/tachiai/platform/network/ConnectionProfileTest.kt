package net.fstab.tachiai.platform.network

import java.io.ByteArrayInputStream
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

internal fun fixtureWireGuard(): String {
    val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    return """
        # Synthetic fixture, not a real connection or key.
        [Interface]
        PrivateKey = $key
        Address = 10.2.0.2/32, fd00::2/128
        DNS = 10.2.0.1, fd00::1
        MTU = 1280
        [Peer]
        PublicKey = $key
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = vpn.example.test:51820
        PersistentKeepalive = 25
    """.trimIndent()
}

class ConnectionProfileTest {
    private fun parse(text: String) = parseConnectionProfile(text.toByteArray())
    private fun rejects(text: String, category: ConnectionImportFailure.Category) {
        val error = assertThrows(ConnectionImportFailure::class.java) { parse(text) }
        assertEquals(category, error.category)
    }

    @Test fun protonShapedDualStackConfigurationIsCanonicalAndRedacted() {
        val profile = parse("\uFEFF" + fixtureWireGuard().replace("\n", "\r\n"))
        assertEquals(ConnectionKind.WIREGUARD, profile.kind)
        assertEquals("vpn.example.test:51820", profile.endpoint)
        assertFalse(profile.configuration.contains("Synthetic fixture"))
        assertFalse(profile.toString().contains("PrivateKey"))
        assertEquals(profile.configuration, parse(profile.configuration).configuration)
    }

    @Test fun optionalValuesAndIpv6EndpointAreAccepted() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
        val profile = parse(fixtureWireGuard().replace("vpn.example.test:51820", "[2001:db8::1]:443")
            .replace("PersistentKeepalive = 25", "PersistentKeepalive = off\nPresharedKey = $key"))
        assertEquals("[2001:db8::1]:443", profile.endpoint)
    }

    @Test fun unsupportedOptionsScriptsAndExtraPeersAreRejectedNotDropped() {
        listOf("PreUp", "PostUp", "PreDown", "PostDown", "SaveConfig", "Table", "ListenPort", "FwMark", "Unknown")
            .forEach { rejects(fixtureWireGuard().replace("MTU = 1280", "$it = secret-should-not-leak"), ConnectionImportFailure.Category.OPTION) }
        rejects(fixtureWireGuard() + "\n[Peer]\n", ConnectionImportFailure.Category.PEERS)
        rejects(fixtureWireGuard().replace("[Peer]", "[Unknown]"), ConnectionImportFailure.Category.OPTION)
        val error = assertThrows(ConnectionImportFailure::class.java) { parse(fixtureWireGuard() + "\n[Peer]\n") }
        assertEquals("This first importer supports exactly one WireGuard peer.", error.message)
    }

    @Test fun missingDuplicateAndMalformedFieldsStopImport() {
        val original = fixtureWireGuard()
        listOf(
            original.replace("MTU = 1280", "MTU = 1280\nMTU = 1500"),
            original.replace("[Interface]", "[Interface]\n[Interface]"),
            original.replace("PrivateKey = ", "PrivateKey = bad"),
            original.replace("Address = 10.2.0.2/32, fd00::2/128", "Address = hostname.test/32"),
            original.replace("10.2.0.2/32", "10.2.0.2/33"),
            original.replace("fd00::2/128", "fd00::2/129"),
            original.replace("10.2.0.1", "999.2.0.1"),
            original.replace("DNS = 10.2.0.1, fd00::1", "DNS = lookup.example.test"),
            original.replace("::/0", "::/0,"),
            original.replace("vpn.example.test:51820", "https://secret.example.test:443"),
            original.replace("vpn.example.test:51820", "2001:db8::1:443"),
            original.replace("vpn.example.test:51820", "[fe80::1%wlan0]:443"),
            original.replace("vpn.example.test:51820", "vpn.example.test:0"),
            original.replace("PersistentKeepalive = 25", "PersistentKeepalive = 65536"),
            original.replace("MTU = 1280", "MTU = 1"),
        ).forEach { rejects(it, ConnectionImportFailure.Category.VALUE) }
    }

    @Test fun proxyCredentialsStayOutOfPreviewAndErrors() {
        val profile = parse("http://user:private-password@proxy.example.test:3128")
        assertEquals(ConnectionKind.HTTP_PROXY, profile.kind)
        assertEquals("proxy.example.test:3128", profile.endpoint)
        assertFalse(profile.toString().contains("private-password"))
        assertEquals("[2001:db8::1]:8080", parse("http://[2001:db8::1]:8080/").endpoint)
        listOf("http://proxy.example.test", "http://proxy.example.test:0", "http://proxy.example.test:80/path",
            "http://user@proxy.example.test:80", "http://proxy.example.test:80?q=secret", "http://proxy.example.test:80#secret")
            .forEach { rejects(it, ConnectionImportFailure.Category.VALUE) }
        rejects("https://proxy.example.test:443", ConnectionImportFailure.Category.FORMAT)
    }

    @Test fun boundedReadAndStrictUtf8RefuseBinaryOrOversizedData() {
        assertEquals(CONNECTION_IMPORT_LIMIT, readConnectionImport(ByteArrayInputStream(ByteArray(CONNECTION_IMPORT_LIMIT))).size)
        assertThrows(ConnectionImportFailure::class.java) {
            readConnectionImport(ByteArrayInputStream(ByteArray(CONNECTION_IMPORT_LIMIT + 1)))
        }
        assertThrows(ConnectionImportFailure::class.java) { parseConnectionProfile(byteArrayOf(0xC0.toByte(), 0xAF.toByte())) }
        rejects(fixtureWireGuard() + "\u0000", ConnectionImportFailure.Category.TEXT)
        rejects("x".repeat(CONNECTION_IMPORT_LIMIT + 1), ConnectionImportFailure.Category.SIZE)
    }

    @Test fun namesAreBoundedAndCannotContainHiddenControls() {
        assertTrue(validConnectionName("Japan · Proton"))
        listOf("", " x", "x\n", "x\u202E", "x".repeat(49)).forEach { assertFalse(validConnectionName(it)) }
    }
}
