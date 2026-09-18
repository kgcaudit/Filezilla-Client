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

    private fun commandFailure(code: Int) =
        FtpCommandException(FtpReply.of(listOf("$code something happened")), "test")

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
    fun `never retries a server known to mishandle the resume offset`() {
        // Retrying would re-run the probe and fail the same way, having moved
        // the user's data for nothing.
        assertFalse(policy.shouldRetry(ResumeUnsupportedException(2, "nope"), attempt = 1))
        assertFalse(policy.shouldRetry(ResumeNotHonouredException("nope"), attempt = 1))
    }

    @Test
    fun `stops once the attempt budget is spent`() {
        assertTrue(policy.shouldRetry(IOException("reset"), attempt = 4))
        assertFalse(policy.shouldRetry(IOException("reset"), attempt = 5))
    }
}
