package org.filezilla.android.transfer

import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.FtpSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerConnectionTest {

    // Security spelled out rather than left to default, so the copy below
    // actually differs from it.
    private val nas = FtpSettings(
        host = "nas.example",
        port = 41,
        user = "u",
        password = "p",
        security = FtpSecurity.PLAIN,
    )

    @Test
    fun `a connection to the same server, just used, is reused`() {
        assertTrue(canReuse(heldFor = nas, wanted = nas, idleMillis = 0))
        assertTrue(canReuse(heldFor = nas, wanted = nas, idleMillis = CONNECTION_IDLE_LIMIT_MILLIS))
    }

    @Test
    fun `nothing held means nothing to reuse`() {
        assertFalse(canReuse(heldFor = null, wanted = nas, idleMillis = 0))
    }

    /**
     * The costly mistake. A connection is logged in as one account on one
     * server; handing it a transfer meant for another is not slow, it is wrong.
     */
    @Test
    fun `a connection opened for another server is never reused`() {
        assertFalse(canReuse(nas.copy(host = "other.example"), nas, idleMillis = 0))
        assertFalse(canReuse(nas.copy(port = 21), nas, idleMillis = 0))
        assertFalse(canReuse(nas.copy(user = "someone"), nas, idleMillis = 0))
        assertFalse(canReuse(nas.copy(password = "different"), nas, idleMillis = 0))
        assertFalse(canReuse(nas.copy(security = FtpSecurity.EXPLICIT_TLS), nas, idleMillis = 0))
    }

    /**
     * The cheap mistake, but still a mistake: a socket the server dropped
     * costs a failed attempt and a backoff, which is slower than opening one.
     */
    @Test
    fun `a connection left sitting is opened again instead`() {
        assertFalse(canReuse(nas, nas, idleMillis = CONNECTION_IDLE_LIMIT_MILLIS + 1))
        assertFalse(canReuse(nas, nas, idleMillis = 10 * 60 * 1000))
    }
}
