package org.filezilla.ftp.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IpUtilsTest {

    @Test
    fun `public IPv4 addresses are routable`() {
        for (a in listOf("203.0.113.7", "8.8.8.8", "198.51.100.1", "172.32.0.1", "100.128.0.1")) {
            assertTrue(IpUtils.isRoutableAddress(a), a)
        }
    }

    @Test
    fun `private and special IPv4 ranges are not routable`() {
        for (a in listOf(
            "0.0.0.0", "10.1.2.3", "127.0.0.1", "169.254.1.1",
            "172.16.0.1", "172.31.255.254", "192.168.1.1", "100.64.0.1",
        )) {
            assertFalse(IpUtils.isRoutableAddress(a), a)
        }
    }

    @Test
    fun `malformed input is not routable`() {
        for (a in listOf("", "1.2.3", "1.2.3.4.5", "1.2.3.300", "abc")) {
            assertFalse(IpUtils.isRoutableAddress(a), a)
        }
    }

    @Test
    fun `IPv6 loopback link local and unique local are not routable`() {
        for (a in listOf("::1", "::", "fe80::1", "fe80::1%wlan0", "fc00::1", "fd12:3456::1")) {
            assertFalse(IpUtils.isRoutableAddress(a), a)
        }
    }

    @Test
    fun `global IPv6 is routable`() {
        for (a in listOf("2001:db8::1", "2400:cb00::1")) {
            assertTrue(IpUtils.isRoutableAddress(a), a)
        }
    }

    @Test
    fun `IPv4 mapped IPv6 follows its IPv4 part`() {
        assertTrue(IpUtils.isRoutableAddress("::ffff:203.0.113.7"))
        assertFalse(IpUtils.isRoutableAddress("::ffff:192.168.1.1"))
    }
}
