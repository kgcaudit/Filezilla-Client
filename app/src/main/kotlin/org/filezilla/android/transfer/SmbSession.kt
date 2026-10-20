package org.filezilla.android.transfer

import org.filezilla.android.files.FilePath
import org.filezilla.ftp.journal.RemoteFingerprint
import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.smb.SmbClient
import org.filezilla.ftp.smb.SmbSettings

/**
 * A [RemoteSession] over SMB, the counterpart of [FtpSession], [SftpSession] and
 * [WebDavSession].
 *
 * Like WebDAV, it keeps the current folder itself and splices it onto each name;
 * [SmbClient] maps those app paths onto the share (and the site's base folder
 * within it) and talks smbj. There is no chmod over SMB, so [changeMode] does
 * nothing and the editor does not offer it.
 */
class SmbSession(settings: SmbSettings) : RemoteSession {

    private val client = SmbClient(settings)
    private var cwd = "/"

    override fun connect() = client.connect()

    override fun currentDirectory(): String = cwd

    override fun changeDirectory(path: String): String {
        cwd = resolve(path)
        return cwd
    }

    override fun changeToParent() {
        cwd = FilePath.parent(cwd) ?: "/"
    }

    override fun list(): List<DirectoryEntry> = client.list(cwd)

    override fun createDirectory(name: String) = writing { client.makeDirectory(childOf(name)) }

    override fun removeDirectory(name: String) = writing { client.removeDirectory(childOf(name)) }

    override fun deleteFile(name: String) = writing { client.deleteFile(childOf(name)) }

    override fun rename(from: String, to: String) = writing { client.rename(childOf(from), childOf(to)) }

    // SMB has no POSIX permission bits to set from here.
    override fun changeMode(path: String, mode: String) = Unit

    override var writes: Int = 0
        private set

    private inline fun <T> writing(block: () -> T): T {
        writes++
        return block()
    }

    override fun fingerprint(remotePath: String): RemoteFingerprint = client.fingerprint(remotePath)

    override fun close() = client.close()

    /** A path as given, or resolved against the current folder when relative. */
    private fun resolve(path: String): String =
        if (path.startsWith("/")) FilePath.normalize(path) else FilePath.child(cwd, path)

    private fun childOf(name: String): String = FilePath.child(cwd, name)
}
