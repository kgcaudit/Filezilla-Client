package org.filezilla.android.ui

import org.filezilla.ftp.journal.TransferDirection
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.journal.TransferState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The word the queue puts on a transfer.
 *
 * The user's report was a video of a card changing its mind several times a
 * second when the Wi-Fi dropped. Each state it passed through was true for
 * the instant it lasted; the problem was that the screen showed all of them.
 */
class TransferMoodTest {

    private fun record(
        state: TransferState,
        attempts: Int = 0,
    ) = TransferRecord(
        id = "t",
        direction = TransferDirection.DOWNLOAD,
        host = "h",
        port = 21,
        user = "u",
        remotePath = "/pub/film.mkv",
        localPath = "/tmp/film.part",
        state = state,
        attempts = attempts,
    )

    /**
     * The whole point. A dropped connection walks a transfer round this loop
     * -- interrupted, picked back up, interrupted again -- and every step of
     * it has to read as one thing.
     */
    @Test
    fun `every step of a retry loop reads as reconnecting`() {
        val loop = listOf(
            moodOf(record(TransferState.INTERRUPTED, attempts = 1), stalled = false),
            moodOf(record(TransferState.PENDING, attempts = 1), stalled = false),
            moodOf(record(TransferState.RUNNING, attempts = 2), stalled = true),
            moodOf(record(TransferState.INTERRUPTED, attempts = 2), stalled = false),
        )

        assertEquals(listOf(TransferMood.RECONNECTING), loop.distinct())
    }

    @Test
    fun `a transfer with bytes arriving is running`() {
        assertEquals(
            TransferMood.RUNNING,
            moodOf(record(TransferState.RUNNING, attempts = 3), stalled = false),
        )
    }

    /** A first-time queue entry is not a retry, and must not say it is. */
    @Test
    fun `a transfer that has never been attempted is queued`() {
        assertEquals(TransferMood.QUEUED, moodOf(record(TransferState.PENDING), stalled = false))
    }

    /**
     * Waiting for an allowed network keeps its own word: it means something
     * the user can act on -- turn Wi-Fi on, or allow mobile data -- which
     * "reconnecting" does not.
     */
    @Test
    fun `waiting for a network is its own thing`() {
        assertEquals(
            TransferMood.WAITING_FOR_NETWORK,
            moodOf(record(TransferState.WAITING_FOR_NETWORK, attempts = 4), stalled = false),
        )
    }

    /** The three a user caused or that are final are never dressed up as retries. */
    @Test
    fun `the settled states are reported as themselves`() {
        assertEquals(TransferMood.PAUSED, moodOf(record(TransferState.PAUSED, 2), stalled = true))
        assertEquals(TransferMood.FAILED, moodOf(record(TransferState.FAILED, 9), stalled = true))
        assertEquals(TransferMood.DONE, moodOf(record(TransferState.COMPLETED), stalled = true))
    }

    @Test
    fun `only the unsettled moods hide the figures`() {
        assertEquals(
            setOf(TransferMood.RECONNECTING, TransferMood.WAITING_FOR_NETWORK),
            TransferMood.entries.filter { it.isUnsettled }.toSet(),
        )
    }
}
