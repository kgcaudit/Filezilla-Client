package org.filezilla.android.ui

import org.filezilla.ftp.journal.TransferDirection
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.journal.TransferState
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the strip along the foot of the files screen says, and when it is
 * there at all.
 *
 * It replaces a navigation tab that cost 80dp of every screen in the app and
 * said only "transfers". The rule that earns the replacement is the first
 * test here: no strip when nothing is moving.
 */
class TransferStripTest {

    private fun record(
        id: String,
        state: TransferState,
        done: Long = 0,
        total: Long? = null,
    ) = TransferRecord(
        id = id,
        direction = TransferDirection.DOWNLOAD,
        host = "example.org",
        port = 21,
        user = "bob",
        remotePath = "/pub/$id",
        localPath = "/tmp/$id",
        state = state,
        bytesTransferred = done,
        totalBytes = total,
    )

    @Test
    fun `an empty queue has no strip`() {
        assertNull(summariseTransfers(emptyList()))
    }

    @Test
    fun `a queue of finished transfers has no strip`() {
        assertNull(
            summariseTransfers(
                listOf(
                    record("a", TransferState.COMPLETED, 10, 10),
                    record("b", TransferState.FAILED),
                ),
            ),
        )
    }

    /**
     * The case that would pin the strip to the screen for good. A transfer
     * the user paused is outstanding but nothing is happening to it, and a
     * bar that will not go away is a bug this app has shipped once already.
     */
    @Test
    fun `a paused transfer does not hold the strip open`() {
        assertNull(summariseTransfers(listOf(record("a", TransferState.PAUSED, 5, 10))))
    }

    @Test
    fun `transfers in hand are counted and measured`() {
        val summary = summariseTransfers(
            listOf(
                record("a", TransferState.RUNNING, 30, 100),
                record("b", TransferState.PENDING, 0, 100),
            ),
        )

        assertEquals(2, summary?.count)
        assertEquals(0.15f, summary?.fraction!!, 0.001f)
    }

    /** Both of these start again by themselves, so from the outside they are still going. */
    @Test
    fun `a transfer waiting on the network or retrying still counts`() {
        for (state in listOf(TransferState.WAITING_FOR_NETWORK, TransferState.INTERRUPTED)) {
            assertEquals("$state", 1, summariseTransfers(listOf(record("a", state)))?.count)
        }
    }

    /** Finished ones are not in the figure: it is how far the work in hand has got. */
    @Test
    fun `what is already done is not counted in the progress`() {
        val summary = summariseTransfers(
            listOf(
                record("done", TransferState.COMPLETED, 100, 100),
                record("now", TransferState.RUNNING, 50, 100),
            ),
        )

        assertEquals(1, summary?.count)
        assertEquals(0.5f, summary?.fraction!!, 0.001f)
    }

    /**
     * A server that never gave a size means the figure cannot be known, and
     * the strip says so with an indeterminate bar rather than inventing one.
     */
    @Test
    fun `a transfer of unknown size leaves the progress unknown`() {
        val summary = summariseTransfers(
            listOf(
                record("a", TransferState.RUNNING, 30, 100),
                record("b", TransferState.RUNNING, 10, null),
            ),
        )

        assertEquals(2, summary?.count)
        assertNull(summary?.fraction)
    }

    @Test
    fun `a total of zero does not divide by zero`() {
        val summary = summariseTransfers(listOf(record("a", TransferState.RUNNING, 0, 0)))

        assertEquals(1, summary?.count)
        assertNull(summary?.fraction)
    }

    /** A partial file longer than the server's figure must not read past full. */
    @Test
    fun `progress cannot exceed the whole`() {
        val summary = summariseTransfers(listOf(record("a", TransferState.RUNNING, 200, 100)))

        assertEquals(1f, summary?.fraction!!, 0.001f)
    }
}

/**
 * That the strip counts what is moving now, not only what has landed.
 *
 * The bug: thirty-eight files queued, one of them running, and the strip sat
 * at "0%" for as long as that file took. The journal's byte count is written
 * when a transfer ends, not while it runs -- that is what makes a resume
 * know where to pick up -- so reading only the journal says nothing has
 * moved until something finishes. The queue screen had always merged the
 * live figures; the strip never did.
 */
class TransferStripLiveProgressTest {

    private fun record(
        id: String,
        state: TransferState,
        done: Long = 0,
        total: Long? = null,
    ) = TransferRecord(
        id = id,
        direction = TransferDirection.DOWNLOAD,
        host = "example.org",
        port = 21,
        user = "bob",
        remotePath = "/pub/$id",
        localPath = "/tmp/$id",
        state = state,
        bytesTransferred = done,
        totalBytes = total,
        updatedAtMillis = 0,
    )

    @Test
    fun `a running transfer's progress counts before it finishes`() {
        val records = listOf(
            record("a", TransferState.RUNNING, done = 0, total = 100),
            record("b", TransferState.PENDING, done = 0, total = 100),
        )

        // Half of the first file is on the wire and none of it is journalled,
        // which is the state the user was looking at.
        val summary = summariseTransfers(records, live = mapOf("a" to 50L))

        assertEquals(2, summary!!.count)
        assertEquals(0.25f, summary.fraction!!, 0.001f)
    }

    /** Without the live figures it is the nothing the user saw. */
    @Test
    fun `the journal alone says nothing has moved`() {
        val records = listOf(
            record("a", TransferState.RUNNING, done = 0, total = 100),
            record("b", TransferState.PENDING, done = 0, total = 100),
        )

        assertEquals(0f, summariseTransfers(records)!!.fraction!!, 0.001f)
    }

    /**
     * A transfer that has just landed has its journalled figure and no live
     * one, so the larger of the two is what counts -- taking the live figure
     * alone would drop finished files back to zero.
     */
    @Test
    fun `a finished transfer keeps its journalled bytes`() {
        val records = listOf(
            record("a", TransferState.PENDING, done = 100, total = 100),
            record("b", TransferState.RUNNING, done = 0, total = 100),
        )

        val summary = summariseTransfers(records, live = mapOf("b" to 20L))

        assertEquals(0.6f, summary!!.fraction!!, 0.001f)
    }

    /** And a live figure for something no longer moving is not counted twice. */
    @Test
    fun `a stale live figure cannot push the bar past the end`() {
        val records = listOf(record("a", TransferState.RUNNING, done = 100, total = 100))

        val summary = summariseTransfers(records, live = mapOf("a" to 999L))

        assertEquals(1f, summary!!.fraction!!, 0.001f)
    }
}

/**
 * That the strip does not claim to be transferring while it is not.
 *
 * The screenshot that started this was a phone in airplane mode reading
 * "38 transferring · 0%". Both halves were wrong: nothing was transferring,
 * and the nought was the absence of progress rather than progress. The queue
 * counts work held for a network as still in hand, which is right -- it
 * restarts itself -- but what the strip says about it has to be the truth of
 * the moment.
 */
class TransferStripWaitingTest {

    private fun record(id: String, state: TransferState, done: Long = 0, total: Long? = 100) =
        TransferRecord(
            id = id,
            direction = TransferDirection.DOWNLOAD,
            host = "example.org",
            port = 21,
            user = "bob",
            remotePath = "/pub/$id",
            localPath = "/tmp/$id",
            state = state,
            bytesTransferred = done,
            totalBytes = total,
            updatedAtMillis = 0,
        )

    @Test
    fun `a queue with nothing running is waiting`() {
        val summary = summariseTransfers(
            listOf(
                record("a", TransferState.WAITING_FOR_NETWORK),
                record("b", TransferState.PENDING),
            ),
        )

        assertEquals(2, summary!!.count)
        assertTrue("it says it is transferring", summary.waiting)
    }

    @Test
    fun `one transfer actually running is not waiting`() {
        val summary = summariseTransfers(
            listOf(
                record("a", TransferState.RUNNING, done = 10),
                record("b", TransferState.WAITING_FOR_NETWORK),
            ),
        )

        assertFalse(summary!!.waiting)
    }
}
