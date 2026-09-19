package org.filezilla.android.ui

import org.filezilla.ftp.journal.TransferDirection
import org.filezilla.ftp.journal.TransferRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which panes a finished transfer sends back for a fresh listing.
 *
 * The bug: a copy from the phone to a server completed and the server pane
 * went on showing the listing it had before it, so the file looked as though
 * it had never been sent.
 */
class TransferRefreshTest {

    private fun upload(remotePath: String) = TransferRecord(
        id = "t",
        direction = TransferDirection.UPLOAD,
        host = "example.org",
        port = 21,
        user = "bob",
        remotePath = remotePath,
        localPath = "/storage/emulated/0/Download/report.pdf",
    )

    private val download = TransferRecord(
        id = "d",
        direction = TransferDirection.DOWNLOAD,
        host = "example.org",
        port = 21,
        user = "bob",
        remotePath = "/pub/report.pdf",
        localPath = "/data/data/app/files/report.pdf.part",
    )

    @Test
    fun `an upload refreshes the pane showing the folder it landed in`() {
        assertTrue(
            finishedTransferTouches(
                upload("/pub/report.pdf"),
                isLocal = false,
                path = "/pub",
                host = "example.org",
                port = 21,
                user = "bob",
            ),
        )
    }

    /** A trailing slash is the same folder, and the pane keeps one at the root. */
    @Test
    fun `the root is matched however it is written`() {
        assertTrue(
            finishedTransferTouches(
                upload("/report.pdf"),
                isLocal = false,
                path = "/",
                host = "example.org",
                port = 21,
                user = "bob",
            ),
        )
    }

    @Test
    fun `an upload leaves a pane showing some other folder alone`() {
        assertFalse(
            finishedTransferTouches(
                upload("/pub/report.pdf"),
                isLocal = false,
                path = "/pub/archive",
                host = "example.org",
                port = 21,
                user = "bob",
            ),
        )
    }

    /** Re-listing costs a connection, so it must be the right server. */
    @Test
    fun `an upload does not reach the same path on a different server`() {
        assertFalse(
            finishedTransferTouches(
                upload("/pub/report.pdf"),
                isLocal = false,
                path = "/pub",
                host = "elsewhere.org",
                port = 21,
                user = "bob",
            ),
        )
    }

    @Test
    fun `an upload does not reach the same server under a different login`() {
        assertFalse(
            finishedTransferTouches(
                upload("/pub/report.pdf"),
                isLocal = false,
                path = "/pub",
                host = "example.org",
                port = 21,
                user = "alice",
            ),
        )
    }

    @Test
    fun `an upload never refreshes a pane showing the phone`() {
        assertFalse(
            finishedTransferTouches(
                upload("/pub/report.pdf"),
                isLocal = true,
                path = "/pub",
                host = null,
                port = null,
                user = null,
            ),
        )
    }

    /**
     * A download's destination is a platform document reference rather than a
     * path, so there is nothing to compare; every local pane re-lists, which
     * on the phone is a directory read and costs nothing.
     */
    @Test
    fun `a download refreshes the panes showing the phone`() {
        assertTrue(
            finishedTransferTouches(
                download,
                isLocal = true,
                path = "/storage/emulated/0/Music",
                host = null,
                port = null,
                user = null,
            ),
        )
    }

    @Test
    fun `a download does not refresh a server`() {
        assertFalse(
            finishedTransferTouches(
                download,
                isLocal = false,
                path = "/pub",
                host = "example.org",
                port = 21,
                user = "bob",
            ),
        )
    }
}
