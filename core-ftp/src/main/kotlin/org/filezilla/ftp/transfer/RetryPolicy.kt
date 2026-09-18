package org.filezilla.ftp.transfer

import org.filezilla.ftp.protocol.FtpCommandException
import java.io.IOException
import kotlin.math.min
import kotlin.math.pow

/**
 * When a failed transfer is worth another attempt, and how long to wait.
 *
 * Port of the retry behaviour around `OPTION_RECONNECTCOUNT` and
 * `OPTION_RECONNECTDELAY` (`QueueView.cpp:1492-1493`), with exponential
 * backoff added: on a phone the usual cause of failure is the network moving
 * underneath the transfer, and hammering a just-dropped connection helps
 * nobody.
 */
data class RetryPolicy(
    val maxAttempts: Int = 5,
    val initialDelayMillis: Long = 2_000,
    val maxDelayMillis: Long = 60_000,
    val multiplier: Double = 2.0,
) {
    /** Delay before attempt number [attempt], counting the first as 1. */
    fun delayBeforeAttempt(attempt: Int): Long {
        if (attempt <= 1) return 0
        val raw = initialDelayMillis * multiplier.pow(attempt - 2)
        return min(raw.toLong(), maxDelayMillis)
    }

    /**
     * Whether [error] is worth retrying.
     *
     * The distinction that matters is between a connection that broke and a
     * server that said no. A dropped socket is exactly what resume exists for,
     * so it is retried; a 5xx refusal, a login failure or a server known to
     * mishandle the resume offset will say the same thing next time, so
     * retrying only wastes the user's data.
     */
    fun shouldRetry(error: Throwable, attempt: Int): Boolean {
        if (attempt >= maxAttempts) return false
        return when (error) {
            // Permanent: the server has answered, and the answer will not
            // change on a second try.
            is ResumeUnsupportedException -> false
            is ResumeNotHonouredException -> false
            // RFC 959 splits replies exactly this way: 4yz is a transient
            // negative reply and invites a retry, 5yz is permanent.
            is FtpCommandException -> error.reply.category == 4
            // A broken connection is the case resume exists for.
            is IOException -> true
            else -> false
        }
    }
}
