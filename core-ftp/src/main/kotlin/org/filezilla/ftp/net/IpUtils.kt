package org.filezilla.ftp.net

/**
 * Address classification, ported from libfilezilla's `is_routable_address`.
 *
 * The FTP engine needs this to spot a `PASV` reply that hands back a private
 * address from a server sitting behind NAT: when the reply is unroutable but
 * the control connection's peer is routable, the peer address is the one that
 * actually works (`rawtransfer.cpp:402-413`).
 */
object IpUtils {

    /** False for loopback, link-local, RFC 1918, CGNAT and IPv6 ULA ranges. */
    fun isRoutableAddress(address: String): Boolean = when {
        address.isEmpty() -> false
        address.contains(':') -> isRoutableIpv6(address)
        else -> isRoutableIpv4(address)
    }

    private fun isRoutableIpv4(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4) return false
        val o = IntArray(4)
        for (i in 0 until 4) {
            o[i] = parts[i].toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }
        return when {
            o[0] == 0 -> false                                  // 0.0.0.0/8
            o[0] == 10 -> false                                 // 10.0.0.0/8
            o[0] == 127 -> false                                // loopback
            o[0] == 169 && o[1] == 254 -> false                 // link-local
            o[0] == 172 && o[1] in 16..31 -> false              // 172.16.0.0/12
            o[0] == 192 && o[1] == 168 -> false                 // 192.168.0.0/16
            o[0] == 100 && o[1] in 64..127 -> false             // CGNAT 100.64.0.0/10
            else -> true
        }
    }

    private fun isRoutableIpv6(address: String): Boolean {
        val normalized = address.substringBefore('%').lowercase()
        if (normalized == "::" || normalized == "::1") return false
        // An IPv4-mapped address is routable exactly when its IPv4 part is.
        val lastGroup = normalized.substringAfterLast(':')
        if (lastGroup.contains('.')) return isRoutableIpv4(lastGroup)

        val firstGroup = normalized.substringBefore(':').padStart(4, '0')
        val byte0 = firstGroup.take(2).toIntOrNull(16) ?: return true
        val byte1 = firstGroup.substring(2, 4).toIntOrNull(16) ?: return true
        return when {
            (byte0 and 0xfe) == 0xfc -> false                   // fc00::/7 unique local
            byte0 == 0xfe && (byte1 and 0xc0) == 0x80 -> false  // fe80::/10 link local
            else -> true
        }
    }
}
