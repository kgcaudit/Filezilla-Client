package org.filezilla.android.service

import org.filezilla.android.R
import org.filezilla.android.transfer.TransferManager.QueueOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the app says when the queue has finished.
 *
 * It said nothing. The progress notification belongs to the foreground
 * service, so it vanishes with the service the moment the last file lands --
 * which from the user's side looks the same as the app having given up. A
 * long copy left alone had no ending at all.
 */
class QueueNoticeTest {

    @Test
    fun `a run that did nothing says nothing`() {
        assertNull(QueueNotice.of(QueueOutcome.NOTHING))
    }

    @Test
    fun `one file finished is not counted as several`() {
        assertEquals(
            QueueNotice(R.string.done_all_one, emptyList()),
            QueueNotice.of(QueueOutcome(completed = 1, failed = 0)),
        )
    }

    @Test
    fun `several finished are counted`() {
        assertEquals(
            QueueNotice(R.string.done_all, listOf(38)),
            QueueNotice.of(QueueOutcome(completed = 38, failed = 0)),
        )
    }

    /**
     * The one that matters. Rolling failures into the count of successes is
     * the kind of reassurance that costs trust exactly once.
     */
    @Test
    fun `failures are never hidden inside a count of successes`() {
        assertEquals(
            QueueNotice(R.string.done_some_failed, listOf(32, 6)),
            QueueNotice.of(QueueOutcome(completed = 32, failed = 6)),
        )
    }

    @Test
    fun `a run where everything failed says so`() {
        assertEquals(
            QueueNotice(R.string.done_all_failed, listOf(4)),
            QueueNotice.of(QueueOutcome(completed = 0, failed = 4)),
        )
        assertEquals(
            QueueNotice(R.string.done_all_failed_one, emptyList()),
            QueueNotice.of(QueueOutcome(completed = 0, failed = 1)),
        )
    }
}
