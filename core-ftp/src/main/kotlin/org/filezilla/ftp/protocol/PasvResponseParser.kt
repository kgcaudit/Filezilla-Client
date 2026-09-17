package org.filezilla.ftp.protocol

import org.filezilla.ftp.net.IpUtils

/** What to do when a `PASV` reply carries an address that cannot be routed. */
enum class PasvFallbackMode {
    /** Silently substitute the control connection's peer address. */
    USE_SERVER_ADDRESS,

    /** Treat it as a passive-mode failure so the caller can fall back to active. */
    FAIL,

    /** Always use the peer address, routable reply or not. */
    ALWAYS_USE_SERVER_ADDRESS,
}

/** Where a data connection should be opened, plus how that was decided. */
data class DataEndpoint(
    val host: String,
    val port: Int,
    /** True when the reply's address was replaced by the control peer's. */
    val addressSubstituted: Boolean = false,
)

/**
 * Parser for `PASV` and `EPSV` replies.
 *
 * The `PASV` half is a port of `CFtpRawTransferOpData::ParsePasvResponse`
 * (`rawtransfer.cpp:327-420`). Servers are wildly inconsistent about how they
 * wrap the six octets — `(h,h,h,h,p,p)`, `[...]`, `{...}`, `<...>` or nothing
 * at all — so the parser walks every candidate delimiter and only accepts one
 * whose payload validates. That retry is what makes
 * `227 Entering passive mode (127,0,0,1,8,190).` parse correctly: the space
 * after "Entering" is a candidate delimiter too, and it has to be rejected
 * rather than fail the whole reply.
 */
object PasvResponseParser {

    private const val DELIMITERS = " ([{<"
    private const val DIGITS = "0123456789,"

    /**
     * Parses a `PASV` reply.
     *
     * @param peerHost the control connection's peer address, used when the
     *   reply's own address is unroutable.
     * @return null when no valid payload is present, or when the address is
     *   unroutable and [fallbackMode] says to fail.
     */
    fun parsePasv(
        reply: String,
        peerHost: String,
        fallbackMode: PasvFallbackMode = PasvFallbackMode.USE_SERVER_ADDRESS,
        triedActive: Boolean = false,
        behindProxy: Boolean = false,
    ): DataEndpoint? {
        val parsed = scanForOctets(reply) ?: return null
        var host = parsed.first
        val port = parsed.second

        // Nothing is known about what is on the far side of a proxy, so the
        // reply is taken at face value.
        if (behindProxy) return DataEndpoint(host, port)

        var substituted = false
        if (!IpUtils.isRoutableAddress(host) && IpUtils.isRoutableAddress(peerHost)) {
            if (fallbackMode != PasvFallbackMode.FAIL || triedActive) {
                host = peerHost
                substituted = true
            } else {
                return null
            }
        } else if (fallbackMode == PasvFallbackMode.ALWAYS_USE_SERVER_ADDRESS) {
            host = peerHost
            substituted = true
        }
        return DataEndpoint(host, port, substituted)
    }

    /** Walks candidate delimiters until one yields six valid octets. */
    private fun scanForOctets(reply: String): Pair<String, Int>? {
        var pos = 2
        while (true) {
            pos = reply.indexOfAny(DELIMITERS, pos + 1)
            if (pos < 0) return null

            val end = reply.indexOfNoneOf(DIGITS, pos + 1)
            when (val open = reply[pos]) {
                ' ' -> if (end >= 0 && reply[end] != ' ') continue
                else -> {
                    val close = when (open) {
                        '(' -> ')'
                        '[' -> ']'
                        '{' -> '}'
                        else -> '>'
                    }
                    if (end < 0 || reply[end] != close) continue
                }
            }

            val payload = if (end < 0) reply.substring(pos + 1) else reply.substring(pos + 1, end)
            val tokens = payload.split(',')
            if (tokens.size != 6) continue

            val nums = IntArray(6)
            var valid = true
            for (i in 0 until 6) {
                val t = tokens[i]
                if (t.isEmpty() || t.length > 3) { valid = false; break }
                val n = t.toIntOrNull()
                if (n == null || n > 255) { valid = false; break }
                nums[i] = n
            }
            if (!valid) continue

            val host = "${nums[0]}.${nums[1]}.${nums[2]}.${nums[3]}"
            return host to (nums[4] * 256 + nums[5])
        }
    }

    /**
     * Parses an `EPSV` reply, a port of `ParseEpsvResponse`
     * (`rawtransfer.cpp:297-325`). Only the port is carried; the host is
     * always the control connection's peer.
     */
    fun parseEpsv(reply: String, peerHost: String): DataEndpoint? {
        val open = reply.indexOf("(|||")
        if (open < 0) return null
        val close = reply.indexOf("|)", open + 4)
        if (close < 0 || close == open + 4) return null
        val port = reply.substring(open + 4, close).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return DataEndpoint(peerHost, port)
    }

    private fun String.indexOfAny(chars: String, from: Int): Int {
        for (i in maxOf(from, 0) until length) if (this[i] in chars) return i
        return -1
    }

    private fun String.indexOfNoneOf(chars: String, from: Int): Int {
        for (i in maxOf(from, 0) until length) if (this[i] !in chars) return i
        return -1
    }
}
