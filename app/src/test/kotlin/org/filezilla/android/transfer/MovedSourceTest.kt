package org.filezilla.android.transfer

import org.filezilla.ftp.journal.TransferDirection
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.journal.TransferState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The rule that decides whether something the user owns gets deleted.
 *
 * Every other test here can cost a red build. This one can cost somebody
 * their files, so each case that must answer "no" is written out separately
 * rather than folded into a table: an interrupted transfer, a paused one, a
 * failed one and one that has merely been queued are four different ways of
 * not having arrived, and a change that let any of them through would take
 * away a file that is not anywhere else yet.
 */
@RunWith(RobolectricTestRunner::class)
class MovedSourceTest {

    private fun record(state: TransferState, moving: Boolean = true) = TransferRecord(
        id = "one",
        direction = TransferDirection.UPLOAD,
        host = "h",
        port = 21,
        user = "u",
        remotePath = "/there/film.mkv",
        localPath = "file:///phone/film.mkv",
        state = state,
        removeSourceWhenDone = moving,
    )

    @Test
    fun `a finished move takes its source with it`() {
        assertTrue(MovedSource.isDue(record(TransferState.COMPLETED)))
    }

    /** A copy never removes anything, however finished it is. */
    @Test
    fun `a finished copy leaves its source alone`() {
        assertFalse(MovedSource.isDue(record(TransferState.COMPLETED, moving = false)))
    }

    @Test
    fun `a queued move has not moved anything yet`() {
        assertFalse(MovedSource.isDue(record(TransferState.PENDING)))
    }

    @Test
    fun `a running move has not finished moving`() {
        assertFalse(MovedSource.isDue(record(TransferState.RUNNING)))
    }

    @Test
    fun `an interrupted move is going to be retried, not undone`() {
        assertFalse(MovedSource.isDue(record(TransferState.INTERRUPTED)))
    }

    @Test
    fun `a move held for the network has not gone anywhere`() {
        assertFalse(MovedSource.isDue(record(TransferState.WAITING_FOR_NETWORK)))
    }

    @Test
    fun `a paused move is waiting on the user, not finished`() {
        assertFalse(MovedSource.isDue(record(TransferState.PAUSED)))
    }

    /** The one that would actually lose the file: it did not get there. */
    @Test
    fun `a failed move keeps the only copy there is`() {
        assertFalse(MovedSource.isDue(record(TransferState.FAILED)))
    }

    // ----------------------------------------------------- the download half

    /** Delivered into the user's own folder, and only then. */
    @Test
    fun `the server's copy goes once the file is in the chosen folder`() {
        assertTrue(
            MovedSource.mayRemoveRemote(
                record(TransferState.COMPLETED),
                MovedSource.Delivery.SAVED,
            ),
        )
    }

    /**
     * The bytes are in app-private storage, which the user cannot reach. A
     * move into there is a move into nowhere.
     */
    @Test
    fun `a file still waiting to be published leaves the server alone`() {
        assertFalse(
            MovedSource.mayRemoveRemote(
                record(TransferState.COMPLETED),
                MovedSource.Delivery.NOT_YET,
            ),
        )
    }

    /**
     * "Keep the one I already have" drops the downloaded bytes on purpose.
     * Removing the server's copy then would leave none at all.
     */
    @Test
    fun `keeping the existing file leaves the server alone`() {
        assertFalse(
            MovedSource.mayRemoveRemote(
                record(TransferState.COMPLETED),
                MovedSource.Delivery.KEPT_EXISTING,
            ),
        )
    }

    // ------------------------------------------------ which files may be cut

    @Test
    fun `a file the app walked to is one it may remove`() {
        assertTrue(
            MovedSource.localFileOf("file://" + writeATempFile()) != null,
        )
    }

    /**
     * A document the system picker handed over belongs to whatever app owns
     * it. The user chose to send that file, not to lose it.
     */
    @Test
    fun `a document from the system picker is not ours to remove`() {
        assertNull(MovedSource.localFileOf("content://com.android.providers/document/1234"))
    }

    @Test
    fun `a path that is not a file is not removed`() {
        assertNull(MovedSource.localFileOf("file:///phone/never-existed"))
    }

    @Test
    fun `nonsense is not a file`() {
        assertNull(MovedSource.localFileOf(""))
    }

    private fun writeATempFile(): String =
        java.io.File.createTempFile("moved", ".mkv").apply { writeText("x"); deleteOnExit() }.absolutePath
}
