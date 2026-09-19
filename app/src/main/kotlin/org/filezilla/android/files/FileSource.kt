package org.filezilla.android.files

import org.filezilla.ftp.listing.DirectoryEntry

/**
 * One side of the screen, whatever is behind it.
 *
 * A pane shows a folder and its rows, and does not care whether they came off
 * the phone or off a server. Keeping that behind one interface is what lets
 * the pane be written once; the alternative -- a local screen and a remote
 * screen that look alike -- is two screens that drift apart, which is how the
 * single-file download path came to skip a check the other paths made.
 *
 * Blocking rather than suspending, like [org.filezilla.android.transfer.FtpSession],
 * which it will wrap: the FTP side is a blocking socket, and a suspending
 * face over it would only hide where the thread goes.
 */
interface FileSource {

    /** A name for the place, for the pane's header. */
    val label: String

    /** The rows of [path], unsorted; ordering is the pane's to decide. */
    fun list(path: String): List<DirectoryEntry>
}
