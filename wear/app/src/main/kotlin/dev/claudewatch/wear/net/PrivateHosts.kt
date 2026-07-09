package dev.claudewatch.wear.net

import java.net.InetAddress

/**
 * v1 ships cleartext HTTP on the LAN only. Android's network security config
 * cannot express CIDR ranges, so the RFC1918-only cleartext scope is enforced
 * here in code: every bridge URL uses the app-private hostname
 * [BRIDGE_URL_HOST] (the only name the network security config permits
 * cleartext for), and [BridgeClient] pins that name's DNS answer to an
 * operator-entered address that this validator has accepted as private.
 */
object PrivateHosts {
    /** The only hostname the network security config allows cleartext HTTP to. */
    const val BRIDGE_URL_HOST = "bridge.internal"

    /**
     * Parses [host] as an IPv4 literal and returns it when it is a private
     * (RFC1918) or loopback address; returns null for anything else,
     * including hostnames and public addresses. Never performs DNS.
     *
     * Allowed ranges:
     *  - 10.0.0.0/8      (includes the emulator host alias 10.0.2.2)
     *  - 172.16.0.0/12
     *  - 192.168.0.0/16
     *  - 127.0.0.0/8     (loopback, for `adb reverse` setups)
     */
    fun parsePrivateIpv4(host: String): InetAddress? {
        val parts = host.trim().split(".")
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0..3) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
            if (part.length > 1 && part[0] == '0') return null // no ambiguous octal forms
            val value = part.toInt()
            if (value > 255) return null
            octets[i] = value
        }
        val allowed = octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168) ||
            octets[0] == 127
        if (!allowed) return null
        return InetAddress.getByAddress(
            host.trim(),
            byteArrayOf(octets[0].toByte(), octets[1].toByte(), octets[2].toByte(), octets[3].toByte()),
        )
    }
}
