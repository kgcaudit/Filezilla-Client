package org.filezilla.ftp.net

import org.filezilla.ftp.protocol.FtpControlConnection
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.ServerCapabilities
import org.filezilla.ftp.testing.FtpsTestServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Whether the pin is actually load-bearing, against a real handshake.
 *
 * Nothing below the socket can be mocked into telling the truth here. A
 * trust manager that is never consulted, a fingerprint compared against
 * the wrong bytes, a pin that quietly falls back to accepting anything --
 * all three pass a unit test and none of them survives a handshake with a
 * server holding a certificate nobody signed.
 *
 * Which is what this uses. The test server's certificate is self-signed,
 * so it is exactly the case the app exists to handle: unverifiable on its
 * own, and identifiable only by being recognised.
 */
class CertificatePinningTest {

    private lateinit var server: FtpsTestServer

    @BeforeEach
    fun start() {
        assumeTrue(FtpsTestServer.isAvailable, "FTPS test server not set up")
        server = FtpsTestServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        if (::server.isInitialized) server.stop()
    }

    private fun settings(pin: String?) = FtpSettings(
        host = "127.0.0.1",
        port = server.port,
        user = server.user,
        password = server.password,
        security = FtpSecurity.EXPLICIT_TLS,
        pinnedCertificate = pin,
    )

    private fun connectWith(pin: String?) {
        FtpControlConnection(settings(pin), ServerCapabilities()).use {
            it.connect()
            it.login()
        }
    }

    @Test
    fun `the right fingerprint connects`() {
        connectWith(FtpsTestServer.fingerprint)
    }

    @Test
    fun `an unknown certificate is refused, and kept to be asked about`() {
        val refused = assertThrows<CertificateNotTrusted> { connectWith(null) }

        // Refusing is half of it. A refusal that threw the certificate away
        // would leave the app with nothing to put in front of the person,
        // and the only thing they could do is give up or go back to
        // trusting everything.
        assertEquals(FtpsTestServer.fingerprint, refused.certificate.fingerprint)
        assertTrue(refused.certificate.selfSigned, "the test server signs its own certificate")
        assertFalse(refused.changed, "nothing was accepted before, so nothing changed")
        assertNotNull(refused.certificate.commonName)
    }

    @Test
    fun `a certificate that is not the pinned one is refused as a change`() {
        // The same shape as somebody answering in the server's place: the
        // handshake succeeds on its own terms and the certificate is simply
        // not the one that was accepted.
        val wrong = FtpsTestServer.fingerprint.replaceFirst("A", "B").let {
            if (it == FtpsTestServer.fingerprint) it.replaceFirst("1", "2") else it
        }

        val refused = assertThrows<CertificateNotTrusted> { connectWith(wrong) }

        assertTrue(refused.changed, "a swapped certificate has to read as a change")
        assertEquals(wrong, refused.previouslyTrusted)
        assertEquals(
            FtpsTestServer.fingerprint,
            refused.certificate.fingerprint,
            "the refusal should carry what was presented, not what was expected",
        )
    }

    @Test
    fun `the comparison does not care about case`() {
        // Fingerprints get copied out of a NAS admin page, an openssl
        // command or a chat message, and they do not all use the same case.
        // Refusing the right certificate over that would send somebody
        // hunting for a man in the middle who was not there.
        connectWith(FtpsTestServer.fingerprint.lowercase())
    }

    @Test
    fun `a pinned server still transfers on the data channel`() {
        // The data connection does its own handshake against the same
        // context. A pin applied only to the control connection would pass
        // every test above and then fail on the first listing.
        server.putFile("one.bin", 64)
        FtpControlConnection(settings(FtpsTestServer.fingerprint), ServerCapabilities()).use {
            it.connect()
            it.login()
            val listed = org.filezilla.ftp.transfer.FtpTransferEngine(it, ServerCapabilities()).list()
            assertTrue("one.bin" in listed.map { entry -> entry.name })
        }
    }
}

/**
 * Which handshake failures are a question about identity.
 *
 * The host check runs in the socket, after this trust manager has already
 * said the chain is fine, so its failure arrives as a bare handshake error
 * with nothing attached. Turning that into a dead end is exactly what the
 * removed "accept any certificate" switch used to prevent -- a server with
 * a real certificate for a name it is not reached by would have been
 * unusable with no way to say otherwise.
 */
class RefusalKindTest {

    private fun seen() = ServerCertificate("AA:BB", "CN=nas", "CN=nas", 0, 1)

    private fun managerHavingSeen(certificate: ServerCertificate): PinningTrustManager {
        val manager = PinningTrustManager(pinned = null)
        // The field is set by the handshake; this stands in for one having
        // happened, which is the state the question depends on.
        val field = PinningTrustManager::class.java.getDeclaredField("lastSeen")
        field.isAccessible = true
        field.set(manager, certificate)
        return manager
    }

    @org.junit.jupiter.api.Test
    fun `a failure about the certificate becomes a question`() {
        val manager = managerHavingSeen(seen())
        val hostMismatch = javax.net.ssl.SSLHandshakeException("handshake failed").apply {
            initCause(java.security.cert.CertificateException("No subject alternative names matching IP"))
        }

        val raised = manager.refusalFor(hostMismatch)

        assertEquals("AA:BB", raised?.certificate?.fingerprint)
        assertFalse(raised!!.changed, "nothing was pinned, so nothing changed")
    }

    @org.junit.jupiter.api.Test
    fun `a failure about anything else is left alone`() {
        val manager = managerHavingSeen(seen())

        // No shared protocol or cipher. Putting a fingerprint in front of
        // somebody for this would teach them to accept fingerprints.
        val noCommonProtocol = javax.net.ssl.SSLHandshakeException("no protocols in common")

        assertNull(manager.refusalFor(noCommonProtocol))
    }

    @org.junit.jupiter.api.Test
    fun `a handshake that never got a certificate has nothing to ask about`() {
        val manager = PinningTrustManager(pinned = null)
        val failure = javax.net.ssl.SSLHandshakeException("connection closed").apply {
            initCause(java.security.cert.CertificateException("nothing"))
        }

        assertNull(manager.refusalFor(failure))
    }
}
