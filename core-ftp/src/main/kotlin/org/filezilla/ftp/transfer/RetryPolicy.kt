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
            // A local source that is not there will not be there next time
            // either -- it was moved past where it could be found, or deleted.
            is java.io.FileNotFoundException -> false
            // RFC 959 splits replies exactly this way: 4yz is a transient
            // negative reply and invites a retry, 5yz is permanent -- except
            // when the server is out of room. A full disk answers "452 ... No
            // space left on device", a transient-looking 4yz that will say the
            // same thing every time: retrying it only climbs the backoff while
            // the upload has nowhere to land, and shows "reconnecting" for a
            // connection that is perfectly fine. (A quota refusal is the same,
            // whether the server dresses it as 452 or 552.)
            is FtpCommandException -> error.reply.category == 4 && !looksOutOfSpace(error.reply.raw)
            // A broken connection is the case resume exists for.
            is IOException -> true
            else -> false
        }
    }
}

/**
 * Whether a server's reply text says it has no room for the file -- a disk
 * that is full or a quota that is spent.
 *
 * Matched on the message rather than the code because servers disagree on the
 * code: the same condition comes back as 452 from one and 552 from another,
 * and only the words ("No space left on device", "Quota exceeded") are
 * dependable. Shared with the UI, which turns a match into one plain line
 * rather than the raw protocol reply. Not exhaustive across every server's
 * wording, and does not need to be: a miss simply falls back to the ordinary
 * handling, which is a retry that fails quickly and an honest error.
 */
fun looksOutOfSpace(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    val lower = text.lowercase()
    return OUT_OF_SPACE_MARKERS.any { it in lower }
}

private val OUT_OF_SPACE_MARKERS = listOf(
    "no space left on device",
    "quota exceeded",
    "exceeded storage allocation",
    "insufficient storage",
    "disk full",
)
