package net.fstab.tachiai.platform.network

import java.net.Inet6Address
import java.net.InetAddress

// Only numeric IPv6 candidates reach InetAddress, never a hostname/DNS lookup.
internal fun literalIp(value: String): Boolean = if (':' in value) {
    value.length <= 45 && Regex("[0-9A-Fa-f:.]+").matches(value) && try {
        InetAddress.getByName(value) is Inet6Address
    } catch (_: Exception) { false }
} else {
    val parts = value.split('.')
    parts.size == 4 && parts.all { part -> Regex("0|[1-9][0-9]{0,2}").matches(part) && part.toInt() in 0..255 }
}

internal fun endpointHost(value: String): Boolean = literalIp(value) ||
    (':' !in value && value.length in 1..253 && !value.all { it.isDigit() || it == '.' } &&
        value.split('.').all { Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?").matches(it) })
