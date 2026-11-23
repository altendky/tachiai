package net.fstab.tachiai.platform.network

import java.math.BigInteger

// X25519 base-point multiplication (RFC 7748 section 5): derives a WireGuard
// public key from its private key so a route can show which provider keypair
// it uses. Display only; never used for a handshake.
internal fun x25519PublicKey(privateKey: ByteArray): ByteArray {
    require(privateKey.size == 32)
    val p = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    val a24 = BigInteger.valueOf(121665)
    val k = privateKey.copyOf().also {
        it[0] = (it[0].toInt() and 248).toByte()
        it[31] = ((it[31].toInt() and 127) or 64).toByte()
    }
    val scalar = BigInteger(1, k.reversedArray())
    val x1 = BigInteger.valueOf(9)
    var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO
    var x3 = x1; var z3 = BigInteger.ONE
    var swap = 0
    for (t in 254 downTo 0) {
        val bit = if (scalar.testBit(t)) 1 else 0
        swap = swap xor bit
        if (swap == 1) { x2 = x3.also { x3 = x2 }; z2 = z3.also { z3 = z2 } }
        swap = bit
        val a = x2.add(z2).mod(p); val aa = a.multiply(a).mod(p)
        val b = x2.subtract(z2).mod(p); val bb = b.multiply(b).mod(p)
        val e = aa.subtract(bb).mod(p)
        val c = x3.add(z3).mod(p); val d = x3.subtract(z3).mod(p)
        val da = d.multiply(a).mod(p); val cb = c.multiply(b).mod(p)
        x3 = da.add(cb).mod(p).let { it.multiply(it).mod(p) }
        z3 = x1.multiply(da.subtract(cb).mod(p).let { it.multiply(it).mod(p) }).mod(p)
        x2 = aa.multiply(bb).mod(p)
        z2 = e.multiply(aa.add(a24.multiply(e)).mod(p)).mod(p)
    }
    if (swap == 1) { x2 = x3; z2 = z3 }
    val u = x2.multiply(z2.modPow(p.subtract(BigInteger.valueOf(2)), p)).mod(p)
    // Big-endian magnitude, left-padded to 32 bytes, then little-endian as RFC 7748 encodes it.
    val raw = u.toByteArray().let { it.copyOfRange(maxOf(0, it.size - 32), it.size) }
    return (ByteArray(32 - raw.size) + raw).reversedArray()
}
