package org.filezilla.android.ui

import org.filezilla.android.files.FilePath
import org.filezilla.ftp.journal.TransferDirection
import org.filezilla.ftp.journal.TransferRecord

/**
 * Whether a transfer that has just finished changed what a pane is showing.
 *
 * Panes only re-list when something they did causes it, so a file that arrived
 * by way of the queue never appeared: copying from the phone to a server put
 * the work on the queue and returned, and the server pane went on showing the
 * listing it had before -- with no sign the paste had done anything until the
 * user re-listed by hand.
 *
 * The two directions are judged differently on purpose. Re-listing a folder on
 * the phone costs a `readdir`, so a download re-lists every local pane rather
 * than trying to work out which folder it landed in -- a download's
 * destination is an opaque platform document reference, not a path that can be
 * compared. Re-listing a server costs a connection, so an upload only touches
 * the pane actually showing the folder it was written to.
 */
fun finishedTransferTouches(
    record: TransferRecord,
    isLocal: Boolean,
    path: String,
    host: String?,
    port: Int?,
    user: String?,
): Boolean = when (record.direction) {
    TransferDirection.DOWNLOAD -> isLocal
    TransferDirection.UPLOAD -> !isLocal &&
        host == record.host &&
        port == record.port &&
        user == record.user &&
        FilePath.parent(record.remotePath) == FilePath.normalize(path)
}
