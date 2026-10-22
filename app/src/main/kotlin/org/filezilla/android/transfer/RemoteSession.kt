package org.filezilla.android.transfer

import org.filezilla.ftp.journal.RemoteFingerprint
import org.filezilla.ftp.listing.DirectoryEntry
import java.io.Closeable

/**
 * One connected browsing session, whatever protocol is behind it.
 *
 * This is the contract the UI browses through, lifted verbatim out of
 * [FtpSession] so a second protocol can meet it. Everything the app does to
 * look around a server -- change folder, list, make/rename/remove, set
 * permissions -- is here, and nothing that is FTP's alone (data-connection
 * modes, `FEAT`, TLS levels) is: those stay inside the FTP implementation.
 *
 * Blocking on purpose, like the FTP session it was drawn from: a browse borrows
 * one of these, runs its command, and hands it back. [TransferManager.browse]
 * runs the borrow on a thread that coroutine cancellation can interrupt.
 */
interface RemoteSession : Closeable {

    /** Opens the connection and logs in. */
    fun connect()

    fun currentDirectory(): String

    /** Returns where the server says that landed, or null if it did not say. */
    fun changeDirectory(path: String): String?

    fun changeToParent()

    /**
     * Lists the current directory.
     *
     * @param onBatch an optional progress hook: on a protocol that streams its
     *   listing (FTP), it is called as entries arrive with the part parsed so
     *   far, so a large, slow folder can be shown filling in rather than blank
     *   until the end. The other protocols return their whole list at once and
     *   ignore it. The return value is always the complete list.
     */
    fun list(onBatch: ((List<DirectoryEntry>) -> Unit)? = null): List<DirectoryEntry>

    fun createDirectory(name: String)

    fun removeDirectory(name: String)

    fun deleteFile(name: String)

    fun rename(from: String, to: String)

    /** Changes a path's permission bits, taking the octal digits `chmod` wants. */
    fun changeMode(path: String, mode: String)

    /**
     * How many times this session has been asked to change the server.
     *
     * [TransferManager.browse] reads it either side of a borrow to know whether
     * to drop what it had cached about the folder; see [FtpSession.writes] for
     * why it is a count that only ever goes up rather than a flag.
     */
    val writes: Int

    /**
     * What the server says about [remotePath] right now, for resume safety to
     * compare against what the journal remembers.
     */
    fun fingerprint(remotePath: String): RemoteFingerprint
}
