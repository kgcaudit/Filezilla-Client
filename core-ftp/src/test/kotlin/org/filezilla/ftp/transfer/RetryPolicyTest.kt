package org.filezilla.ftp.transfer

import org.filezilla.ftp.protocol.FtpCommandException
import org.filezilla.ftp.protocol.FtpReply
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketException

class RetryPolicyTest {

    private val policy = RetryPolicy()

    private fun commandFailure(code: Int, text: String = "something happened") =
        FtpCommandException(FtpReply.of(listOf("$code $text")), "test")

    @Test
    fun `backs off exponentially and then levels out`() {
        assertEquals(0, policy.delayBeforeAttempt(1))
        assertEquals(2_000, policy.delayBeforeAttempt(2))
        assertEquals(4_000, policy.delayBeforeAttempt(3))
        assertEquals(8_000, policy.delayBeforeAttempt(4))
        assertEquals(16_000, policy.delayBeforeAttempt(5))
        // Capped, so a long-running retry loop does not drift into hours.
        assertEquals(60_000, RetryPolicy(maxAttempts = 20).delayBeforeAttempt(12))
    }

    @Test
    fun `retries a dropped connection`() {
        // This is the case resume exists for: the network moved under us.
        assertTrue(policy.shouldRetry(SocketException("Connection reset"), attempt = 1))
        assertTrue(policy.shouldRetry(IOException("Connection closed by server"), attempt = 3))
    }

    @Test
    fun `retries a transient 4yz reply but not a permanent 5yz one`() {
        assertTrue(policy.shouldRetry(commandFailure(421), attempt = 1))
        assertTrue(policy.shouldRetry(commandFailure(450), attempt = 1))

        assertFalse(policy.shouldRetry(commandFailure(530), attempt = 1)) // not logged in
        assertFalse(policy.shouldRetry(commandFailure(550), attempt = 1)) // no such file
        assertFalse(policy.shouldRetry(commandFailure(552), attempt = 1)) // out of space
    }

    @Test
    fun `never retries a full disk, whatever code it wears`() {
        // The reported case: an ipTIME NAS answers a full disk with 452, a 4yz
        // that otherwise invites a retry. Climbing the backoff against a server
        // with nowhere to put the file helps nobody, and reads as "reconnecting"
        // for a connection that is fine.
        assertFalse(
            policy.shouldRetry(
                commandFailure(452, "Transfer aborted. No space left on device"),
                attempt = 1,
            ),
        )
        // A quota refusal means the same thing, however it is dressed.
        assertFalse(policy.shouldRetry(commandFailure(452, "Quota exceeded"), attempt = 1))
        // But an ordinary transient 452 -- a busy data channel, say -- is still
        // worth another try.
        assertTrue(policy.shouldRetry(commandFailure(452, "Requested action aborted; busy"), attempt = 1))
    }

    @Test
    fun `reads an out-of-space reply from its words`() {
        assertTrue(looksOutOfSpace("452 Transfer aborted. No space left on device"))
        assertTrue(looksOutOfSpace("552 Exceeded storage allocation"))
        assertFalse(looksOutOfSpace("226 Transfer complete"))
        assertFalse(looksOutOfSpace(null))
    }

    @Test
    fun `never retries a server known to mishandle the resume offset`() {
        // Retrying would re-run the probe and fail the same way, having moved
        // the user's data for nothing.
        assertFalse(policy.shouldRetry(ResumeUnsupportedException(2, "nope"), attempt = 1))
        assertFalse(policy.shouldRetry(ResumeNotHonouredException("nope"), attempt = 1))
    }

    @Test
    fun `never retries a local source that is not there`() {
        // A moved-past-finding or deleted upload source will not reappear by
        // trying again, so spending the budget on it only wastes time. It is an
        // IOException, so without its own case it would be retried like a reset.
        assertFalse(policy.shouldRetry(java.io.FileNotFoundException("no such file"), attempt = 1))
    }

    @Test
    fun `stops once the attempt budget is spent`() {
        assertTrue(policy.shouldRetry(IOException("reset"), attempt = 4))
        assertFalse(policy.shouldRetry(IOException("reset"), attempt = 5))
    }
}
