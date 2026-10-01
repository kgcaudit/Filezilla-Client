package org.filezilla.android.sync

import org.filezilla.android.data.SyncDirection
import org.filezilla.android.data.SyncJobEntity
import org.filezilla.android.data.SyncRunStatus
import org.filezilla.android.ui.SyncInterval
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure decisions a scheduled mirror rests on: which interval a stored
 * minute count maps back to, how a run's counts become a status, and that a
 * stored row reads its own enums back even when the string is junk.
 *
 * The scan, the diff and the transfer are tested elsewhere (SyncDiffTest and
 * the transfer suite); what is here is only the glue this feature added, which
 * is where a quiet wrong answer would hide.
 */
class ScheduledSyncTest {

    // ----------------------------------------------------------- the interval

    @Test
    fun `an interval maps to the exact choice when it is one`() {
        assertEquals(SyncInterval.SIX_HOURS, SyncInterval.nearest(6 * 60))
        assertEquals(SyncInterval.TWELVE_HOURS, SyncInterval.nearest(12 * 60))
        assertEquals(SyncInterval.DAILY, SyncInterval.nearest(24 * 60))
    }

    @Test
    fun `an odd stored interval snaps to the nearest choice`() {
        // 11 hours is closer to twelve than to six.
        assertEquals(SyncInterval.TWELVE_HOURS, SyncInterval.nearest(11 * 60))
        // 20 hours is closer to a day than to twelve.
        assertEquals(SyncInterval.DAILY, SyncInterval.nearest(20 * 60))
        // 7 hours is closer to six than to twelve.
        assertEquals(SyncInterval.SIX_HOURS, SyncInterval.nearest(7 * 60))
    }

    // ------------------------------------------------------------- the status

    @Test
    fun `nothing to do is a clean run`() {
        assertEquals(SyncRunStatus.OK, ScheduledSyncResult(noop = true).status)
    }

    @Test
    fun `work with no failures is a clean run`() {
        assertEquals(SyncRunStatus.OK, ScheduledSyncResult(made = 2, queued = 5, deleted = 1).status)
    }

    @Test
    fun `some done and some failed is partial`() {
        assertEquals(SyncRunStatus.PARTIAL, ScheduledSyncResult(queued = 4, failed = 2).status)
    }

    @Test
    fun `nothing done and only failures is a failed run`() {
        assertEquals(SyncRunStatus.FAILED, ScheduledSyncResult(failed = 3).status)
    }

    @Test
    fun `only queued work counts as work to hand off`() {
        assertEquals(true, ScheduledSyncResult(queued = 1).hasQueuedWork)
        // Folders made and extras deleted happen in the run itself, not the
        // queue -- so a run that only made or deleted has nothing left to drain.
        assertEquals(false, ScheduledSyncResult(made = 3, deleted = 2).hasQueuedWork)
        assertEquals(false, ScheduledSyncResult(noop = true).hasQueuedWork)
    }

    // -------------------------------------------------------- the row's enums

    private fun job(direction: String, lastStatus: String) = SyncJobEntity(
        id = "j1",
        name = "n",
        localRoot = "/a",
        localLabel = "a",
        siteId = "s1",
        remoteRoot = "/b",
        remoteLabel = "b",
        direction = direction,
        intervalMinutes = 720,
        lastStatus = lastStatus,
    )

    @Test
    fun `a row reads its direction and status back`() {
        val up = job(SyncDirection.UPLOAD.name, SyncRunStatus.OK.name)
        assertEquals(SyncDirection.UPLOAD, up.directionEnum)
        assertEquals(SyncRunStatus.OK, up.lastStatusEnum)
    }

    @Test
    fun `a junk enum string falls back rather than throwing`() {
        val junk = job("sideways", "whoops")
        assertEquals(SyncDirection.UPLOAD, junk.directionEnum)
        assertEquals(SyncRunStatus.NONE, junk.lastStatusEnum)
    }
}
