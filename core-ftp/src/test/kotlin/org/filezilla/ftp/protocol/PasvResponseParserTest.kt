package org.filezilla.ftp.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PasvResponseParserTest {

    private val routablePeer = "203.0.113.7"

    private fun pasv(reply: String, peer: String = routablePeer) =
        PasvResponseParser.parsePasv(reply, peer)

    @Test
    fun `parses the common parenthesised form`() {
        val ep = pasv("227 Entering Passive Mode (192,0,2,5,8,190)")
        assertEquals("192.0.2.5", ep!!.host)
        assertEquals(8 * 256 + 190, ep.port)
    }

    @Test
    fun `skips the space before the payload and keeps scanning`() {
        // The space after "Entering" is a candidate delimiter that must be
        // rejected rather than failing the whole reply.
        val ep = pasv("227 Entering passive mode (127,0,0,1,8,190).", peer = "127.0.0.1")
        assertEquals("127.0.0.1", ep!!.host)
        assertEquals(2238, ep.port)
    }

    @Test
    fun `accepts bracket brace and angle delimiters`() {
        for (wrapped in listOf("[192,0,2,5,4,1]", "{192,0,2,5,4,1}", "<192,0,2,5,4,1>")) {
            val ep = pasv("227 Passive $wrapped")
            assertEquals("192.0.2.5", ep!!.host, wrapped)
            assertEquals(1025, ep.port, wrapped)
        }
    }

    @Test
    fun `accepts a bare space delimited payload`() {
        val ep = pasv("227 192,0,2,5,4,1")
        assertEquals("192.0.2.5", ep!!.host)
        assertEquals(1025, ep.port)
    }

    @Test
    fun `rejects an out of range octet`() {
        assertNull(pasv("227 Entering Passive Mode (192,0,2,999,8,190)"))
    }

    @Test
    fun `rejects a payload with the wrong number of fields`() {
        assertNull(pasv("227 Entering Passive Mode (192,0,2,5,8)"))
    }

    @Test
    fun `rejects a reply with no payload at all`() {
        assertNull(pasv("227 Entering Passive Mode"))
    }

    @Test
    fun `substitutes the peer address when the reply is unroutable`() {
        // A server behind NAT announcing its private address.
        val ep = pasv("227 Entering Passive Mode (192,168,1,50,8,190)")
        assertEquals(routablePeer, ep!!.host)
        assertTrue(ep.addressSubstituted)
    }

    @Test
    fun `keeps an unroutable reply when the peer is unroutable too`() {
        // On a LAN both sides are private and the reply is the correct answer.
        val ep = PasvResponseParser.parsePasv(
            "227 Entering Passive Mode (192,168,1,50,8,190)",
            peerHost = "192.168.1.50",
        )
        assertEquals("192.168.1.50", ep!!.host)
        assertTrue(!ep.addressSubstituted)
    }

    @Test
    fun `fails passive mode when configured to and active was not tried`() {
        val ep = PasvResponseParser.parsePasv(
            "227 Entering Passive Mode (192,168,1,50,8,190)",
            peerHost = routablePeer,
            fallbackMode = PasvFallbackMode.FAIL,
            triedActive = false,
        )
        assertNull(ep)
    }

    @Test
    fun `substitutes anyway once active mode has already been tried`() {
        val ep = PasvResponseParser.parsePasv(
            "227 Entering Passive Mode (192,168,1,50,8,190)",
            peerHost = routablePeer,
            fallbackMode = PasvFallbackMode.FAIL,
            triedActive = true,
        )
        assertEquals(routablePeer, ep!!.host)
    }

    @Test
    fun `always substitutes in always mode even for a routable reply`() {
        val ep = PasvResponseParser.parsePasv(
            "227 Entering Passive Mode (198,51,100,9,8,190)",
            peerHost = routablePeer,
            fallbackMode = PasvFallbackMode.ALWAYS_USE_SERVER_ADDRESS,
        )
        assertEquals(routablePeer, ep!!.host)
    }

    @Test
    fun `takes a proxied reply at face value`() {
        val ep = PasvResponseParser.parsePasv(
            "227 Entering Passive Mode (10,0,0,4,8,190)",
            peerHost = routablePeer,
            behindProxy = true,
        )
        assertEquals("10.0.0.4", ep!!.host)
    }

    @Test
    fun `parses an EPSV reply`() {
        val ep = PasvResponseParser.parseEpsv("229 Entering Extended Passive Mode (|||49152|)", routablePeer)
        assertEquals(routablePeer, ep!!.host)
        assertEquals(49152, ep.port)
    }

    @Test
    fun `rejects a malformed EPSV reply`() {
        assertNull(PasvResponseParser.parseEpsv("229 Entering Extended Passive Mode (|||)", routablePeer))
        assertNull(PasvResponseParser.parseEpsv("229 Entering Extended Passive Mode", routablePeer))
        assertNull(PasvResponseParser.parseEpsv("229 Entering Extended Passive Mode (|||99999|)", routablePeer))
    }
}
