package org.filezilla.ftp.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Whether the certificate is checked against the host it came from.
 *
 * A bare [SSLSocket] does not do this. The chain is validated and the name
 * in the certificate is never looked at -- so a certificate signed by a
 * real authority, for somebody else's domain entirely, passes. It was
 * missing here before there was anything else to notice it: the switch
 * that turned checking off was doing the noticing.
 *
 * Not testable through a handshake with the fixture server, whose
 * certificate is self-signed: the chain fails first, and a failure on the
 * chain looks the same from outside as a failure on the name. So the
 * decision is made on a socket directly, which is the code that runs
 * either way.
 */
class HostnameCheckTest {

    private fun socket(): SSLSocket =
        SSLSocketFactory.getDefault().createSocket() as SSLSocket

    @Test
    fun `a server with nothing pinned has its name checked`() {
        val socket = socket()
        TlsFactory(pinnedCertificate = null).applyHostnameCheck(socket)

        assertEquals("HTTPS", socket.sslParameters.endpointIdentificationAlgorithm)
    }

    @Test
    fun `a pinned server is identified by its certificate instead`() {
        val socket = socket()
        TlsFactory(pinnedCertificate = "AA:BB").applyHostnameCheck(socket)

        // Left off on purpose. A home server's certificate names whatever
        // it was generated with, rarely the address it is reached at, and
        // it has already been identified by something stricter than a name.
        // Checking the name as well would rule out the servers this is for.
        assertNull(socket.sslParameters.endpointIdentificationAlgorithm)
    }
}
