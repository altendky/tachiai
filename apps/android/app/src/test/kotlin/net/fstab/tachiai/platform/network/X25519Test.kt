package net.fstab.tachiai.platform.network

import org.junit.Assert.assertEquals
import org.junit.Test

class X25519Test {
    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

    // RFC 7748 section 6.1 key pairs.
    @Test fun derivesTheRfc7748PublicKeys() {
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
            x25519PublicKey(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")).hex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f",
            x25519PublicKey(hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")).hex())
    }

    // A WireGuard route shows the base64 public key of its own private key.
    @Test fun wireGuardRoutesExposeOnlyTheirDerivedPublicKey() {
        val private = java.util.Base64.getEncoder().encodeToString(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        val peer = java.util.Base64.getEncoder().encodeToString(hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"))
        val protocol = WireGuardRouteProtocol
        val profile = protocol.parse("[Interface]\nPrivateKey = $private\nAddress = 10.0.0.2/32\n\n" +
            "[Peer]\nPublicKey = $peer\nAllowedIPs = 0.0.0.0/0\nEndpoint = 203.0.113.1:51820\n")
        val expected = java.util.Base64.getEncoder().encodeToString(hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"))
        assertEquals(expected, protocol.publicKey(profile))
    }
}
