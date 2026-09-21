package org.filezilla.ftp.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The parts of a certificate a person is actually shown.
 *
 * All of this exists to be compared against something the server prints,
 * so the shape is not cosmetic: a fingerprint in the wrong case, or run
 * together without separators, is one that gets glanced at and declared
 * identical.
 */
class ServerCertificateTest {

    private fun certificate(
        fingerprint: String = "AA:BB",
        subject: String = "CN=nas.home,O=Me",
        issuer: String = "CN=nas.home,O=Me",
        notBefore: Long = 0,
        notAfter: Long = 1_000,
    ) = ServerCertificate(fingerprint, subject, issuer, notBefore, notAfter)

    @Test
    fun `a fingerprint is upper case hex in pairs`() {
        // What openssl, a NAS admin page and FileZilla all print. A
        // fingerprint that has to be reformatted before it can be compared
        // is one nobody compares.
        val printed = ServerCertificate.fingerprintOf(byteArrayOf(0, 1, 15, 16, -1))

        assertTrue(printed.startsWith("0") || printed.first().isLetterOrDigit())
        assertEquals(64, printed.replace(":", "").length)
        assertEquals(printed.uppercase(), printed)
        assertEquals(32, printed.split(":").size)
        assertTrue(printed.split(":").all { it.length == 2 })
    }

    @Test
    fun `the same bytes always give the same fingerprint`() {
        assertEquals(
            ServerCertificate.fingerprintOf("hello".toByteArray()),
            ServerCertificate.fingerprintOf("hello".toByteArray()),
        )
    }

    @Test
    fun `one byte different is a different fingerprint`() {
        assertFalse(
            ServerCertificate.fingerprintOf("hello".toByteArray()) ==
                ServerCertificate.fingerprintOf("hellp".toByteArray()),
        )
    }

    @Test
    fun `the common name is pulled out of the rest of the subject`() {
        assertEquals("nas.home", certificate().commonName)
        assertEquals("nas.home", certificate(issuer = "CN=nas.home,O=Me").issuerName)
    }

    @Test
    fun `a common name that is not first is still found`() {
        assertEquals("nas.home", certificate(subject = "O=Me,CN=nas.home,C=KR").commonName)
    }

    @Test
    fun `an escaped comma inside a name does not cut it short`() {
        assertEquals(
            "Home, Inc",
            certificate(subject = """O=Me,CN=Home\, Inc,C=KR""").commonName,
        )
    }

    @Test
    fun `a subject with no common name is shown whole rather than blank`() {
        // Better an unfamiliar string than an empty box: the person is
        // being asked to recognise something.
        assertEquals("O=Me,C=KR", certificate(subject = "O=Me,C=KR").commonName)
    }

    @Test
    fun `a certificate that vouches for itself says so`() {
        assertTrue(certificate().selfSigned)
        assertFalse(certificate(issuer = "CN=Some Authority").selfSigned)
    }

    @Test
    fun `validity is a range, and the ends are inside it`() {
        val cert = certificate(notBefore = 100, notAfter = 200)

        assertFalse(cert.isCurrentAt(99))
        assertTrue(cert.isCurrentAt(100))
        assertTrue(cert.isCurrentAt(200))
        assertFalse(cert.isCurrentAt(201))
    }

    @Test
    fun `a first meeting and a change read differently`() {
        val first = CertificateNotTrusted(certificate(), previouslyTrusted = null)
        val changed = CertificateNotTrusted(certificate(), previouslyTrusted = "CC:DD")

        assertFalse(first.changed)
        assertTrue(changed.changed)
        // The two are not the same event and must not read as the same one:
        // one is routine, the other is the only warning anybody gets.
        assertFalse(first.message == changed.message)
    }
}
