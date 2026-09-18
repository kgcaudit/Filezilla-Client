package org.filezilla.android.ui

import org.filezilla.ftp.protocol.FtpSecurity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What happens to the port when the encryption setting changes.
 *
 * It used to be overwritten every time, which threw away a port the user had
 * typed -- and a server on a non-standard port is exactly why someone touches
 * that field. The rule is now conditional, and a conditional rule is one that
 * can be subtly wrong, so it is pinned.
 */
class SitePortTest {

    private fun after(port: String, from: FtpSecurity, to: FtpSecurity) =
        portAfterSecurityChange(port, from, to)

    @Test
    fun `a port the user typed is left alone`() {
        // The case from the report: a NAS on port 41 stayed on 41.
        assertEquals("41", after("41", FtpSecurity.PLAIN, FtpSecurity.EXPLICIT_TLS))
        assertEquals("2121", after("2121", FtpSecurity.EXPLICIT_TLS, FtpSecurity.IMPLICIT_TLS))
    }

    @Test
    fun `an untouched default follows the protocol`() {
        // Implicit FTPS on 21 is a connection that hangs, so a port nobody
        // chose is still worth moving.
        assertEquals("990", after("21", FtpSecurity.PLAIN, FtpSecurity.IMPLICIT_TLS))
        assertEquals("21", after("990", FtpSecurity.IMPLICIT_TLS, FtpSecurity.PLAIN))
    }

    @Test
    fun `21 is not treated as chosen just because both protocols share it`() {
        // Plain and explicit TLS are both 21, so leaving one for the other
        // changes nothing either way.
        assertEquals("21", after("21", FtpSecurity.PLAIN, FtpSecurity.EXPLICIT_TLS))
    }

    @Test
    fun `an empty port gets the new default rather than staying empty`() {
        assertEquals("21", after("", FtpSecurity.IMPLICIT_TLS, FtpSecurity.PLAIN))
        assertEquals("990", after("", FtpSecurity.PLAIN, FtpSecurity.IMPLICIT_TLS))
    }

    @Test
    fun `a new server starts on plain FTP`() {
        val blank = SiteDraft.blank()
        assertEquals(FtpSecurity.PLAIN, blank.security)
        assertEquals(21, blank.port)
    }
}
