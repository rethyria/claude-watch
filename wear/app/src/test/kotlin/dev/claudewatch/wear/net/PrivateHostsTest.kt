package dev.claudewatch.wear.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PrivateHostsTest {

    @Test
    fun acceptsRfc1918AndLoopbackLiterals() {
        for (host in listOf(
            "10.0.2.2", // emulator host alias (inside 10/8)
            "10.1.2.3",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.1.10",
            "127.0.0.1",
        )) {
            val address = PrivateHosts.parsePrivateIpv4(host)
            assertNotNull("expected $host to be accepted", address)
            assertEquals(host, address!!.hostAddress)
        }
    }

    @Test
    fun acceptsSurroundingWhitespace() {
        assertNotNull(PrivateHosts.parsePrivateIpv4(" 192.168.0.5 "))
    }

    @Test
    fun rejectsPublicAddresses() {
        for (host in listOf("8.8.8.8", "1.2.3.4", "100.64.0.1", "9.255.255.255", "11.0.0.1")) {
            assertNull("expected $host to be rejected", PrivateHosts.parsePrivateIpv4(host))
        }
    }

    @Test
    fun rejectsAddressesJustOutsideThe172Slash12Range() {
        assertNull(PrivateHosts.parsePrivateIpv4("172.15.0.1"))
        assertNull(PrivateHosts.parsePrivateIpv4("172.32.0.1"))
    }

    @Test
    fun rejectsHostnamesAndMalformedLiterals() {
        for (host in listOf(
            "bridge.local",
            "example.com",
            "192.168.1",
            "192.168.1.1.1",
            "192.168.1.256",
            "192.168.01.1", // ambiguous leading-zero octet
            "192.168.1.-1",
            "0x7f.0.0.1",
            "",
            "bridge.internal", // the pinned name itself must never be user-entered
        )) {
            assertNull("expected $host to be rejected", PrivateHosts.parsePrivateIpv4(host))
        }
    }

    @Test
    fun neverResolvesViaDns() {
        // A parseable private literal keeps its literal address bytes.
        val address = PrivateHosts.parsePrivateIpv4("10.9.8.7")!!
        assertEquals("10.9.8.7", address.hostAddress)
    }
}
