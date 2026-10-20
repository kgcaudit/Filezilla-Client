package org.filezilla.android.transfer

import org.filezilla.android.files.FilePath
import org.filezilla.ftp.journal.RemoteFingerprint
import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.webdav.WebDavClient
import org.filezilla.ftp.webdav.WebDavSettings

/**
 * A [RemoteSession] over WebDAV, the counterpart of [FtpSession] and
 * [SftpSession].
 *
 * WebDAV has no notion of a working directory -- every request names a full
 * path -- so unlike the FTP and SFTP sessions this one keeps the current folder
 * itself and splices it onto each name. Paths are the app's own, rooted at "/"
 * (the server root); [WebDavClient] turns them into URLs. There is no chmod over
 * WebDAV, so [changeMode] does nothing, and the editor does not offer it.
 */
class WebDavSession(settings: WebDavSettings) : RemoteSession {

    private val client = WebDavClient(settings)
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

    override fun createDirectory(name: String) = writing { client.makeCollection(resolve(name)) }

    override fun removeDirectory(name: String) = writing { client.delete(resolve(name)) }

    override fun deleteFile(name: String) = writing { client.delete(resolve(name)) }

    override fun rename(from: String, to: String) = writing { client.move(resolve(from), resolve(to)) }

    // WebDAV has no permission bits to set.
    override fun changeMode(path: String, mode: String) = Unit

    override var writes: Int = 0
        private set

    private inline fun <T> writing(block: () -> T): T {
        writes++
        return block()
    }

    override fun fingerprint(remotePath: String): RemoteFingerprint = client.fingerprint(remotePath)

    override fun close() = Unit

    /**
     * A path as given, or resolved against the current folder when relative.
     *
     * The mutating operations take this -- not a bare name spliced onto the
     * current folder -- because callers pass absolute paths: the scheduled sync
     * walks a tree and deletes each entry by its full path, exactly as it does
     * over FTP and SFTP (whose servers resolve an absolute argument). A
     * cwd-relative-only version quietly mangled those into the wrong path.
     */
    private fun resolve(path: String): String =
        if (path.startsWith("/")) FilePath.normalize(path) else FilePath.child(cwd, path)
}
