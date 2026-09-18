package org.filezilla.android.ui

import org.filezilla.android.R
import org.filezilla.ftp.protocol.FtpCommandException
import org.filezilla.ftp.protocol.FtpReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Turning a failure into something worth reading.
 *
 * The screen used to show `Throwable.message`, and for a mistyped host name
 * Java's message is the host name alone -- so the app answered a typo by
 * repeating the typo back. These map each failure to a cause and a next step,
 * and the mapping is easy to get subtly wrong, so it is pinned.
 */
class ConnectionFailureTest {

    private fun reply(code: Int, text: String) =
        FtpReply.of(listOf("$code $text"))

    @Test
    fun `a mistyped host says so instead of echoing the host`() {
        val failure = describeFailure(UnknownHostException("ftp.exmaple.org"), online = true)
        assertEquals(R.string.fail_host, failure.title)
        // The raw text is kept, but it is no longer the whole message.
        assertTrue("ftp.exmaple.org" in failure.technical)
    }

    @Test
    fun `being offline is blamed before the server is`() {
        // Every network failure looks like a broken server when the phone has
        // no connection. Blaming the server would send the user to fix
        // something that is not wrong.
        val failure = describeFailure(UnknownHostException("ftp.example.org"), online = false)
        assertEquals(R.string.fail_offline, failure.title)
    }

    @Test
    fun `a refused connection points at the port`() {
        assertEquals(
            R.string.fail_refused,
            describeFailure(ConnectException("Connection refused"), online = true).title,
        )
    }

    @Test
    fun `a timeout is not confused with a refusal`() {
        assertEquals(
            R.string.fail_timeout,
            describeFailure(SocketTimeoutException("timed out"), online = true).title,
        )
    }

    @Test
    fun `a TLS failure points at the encryption setting`() {
        assertEquals(
            R.string.fail_tls,
            describeFailure(SSLHandshakeException("handshake"), online = true).title,
        )
    }

    @Test
    fun `530 is reported as a rejected login`() {
        val failure = describeFailure(
            FtpCommandException(reply(530, "Login incorrect"), "login failed"),
            online = true,
        )
        assertEquals(R.string.fail_login, failure.title)
        // The server's own words, which are worth more than the exception's.
        assertEquals("530 Login incorrect", failure.technical)
    }

    @Test
    fun `a 4yz reply is transient, not permanent`() {
        // RFC 959 splits replies this way, and the advice differs: one says
        // try again shortly, the other says something is wrong.
        assertEquals(
            R.string.fail_busy,
            describeFailure(
                FtpCommandException(reply(421, "Too many connections"), "refused"),
                online = true,
            ).title,
        )
    }

    @Test
    fun `an unrecognised failure still offers the log`() {
        val failure = describeFailure(IllegalStateException("something odd"), online = true)
        assertEquals(R.string.fail_unknown, failure.title)
        assertTrue("IllegalStateException" in failure.technical)
    }

    @Test
    fun `a plain IO failure is a connection problem when online`() {
        assertEquals(
            R.string.fail_network,
            describeFailure(IOException("broken pipe"), online = true).title,
        )
    }
}
