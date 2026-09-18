package org.filezilla.android.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadConflictTest {

    private fun conflict(remote: Long?, local: Long, remoteTime: Long? = 1_000, localTime: Long = 2_000) =
        DownloadConflict(
            displayName = "movie.mkv",
            remoteSize = remote,
            remoteModifiedMillis = remoteTime,
            localSize = local,
            localModifiedMillis = localTime,
        )

    @Test
    fun `the same size reads as probably the same file`() {
        assertTrue(conflict(remote = 84_172, local = 84_172).sameSize)
    }

    @Test
    fun `a different size reads as a different file`() {
        assertFalse(conflict(remote = 84_172, local = 84_173).sameSize)
    }

    /**
     * A server that gives no size gives no evidence. Claiming a match on that
     * would push the user toward overwriting a file on no information at all.
     */
    @Test
    fun `an unknown remote size is never called a match`() {
        assertFalse(conflict(remote = null, local = 84_172).sameSize)
    }

    /**
     * Timestamps are deliberately not part of the verdict. An FTP listing
     * reports the server's own clock and timezone at whatever precision the
     * format carries, while the local copy holds the moment it was written --
     * so two identical files routinely differ by hours, and treating that as
     * "different" would send the user re-downloading what they already have.
     */
    @Test
    fun `times that disagree do not make identical files look different`() {
        val hoursApart = conflict(
            remote = 84_172,
            local = 84_172,
            remoteTime = 1_700_000_000_000,
            localTime = 1_700_032_400_000,
        )

        assertTrue(hoursApart.sameSize)
    }
}
