package org.filezilla.android.transfer

import org.filezilla.ftp.journal.RemoteFingerprint
import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.protocol.FtpControlConnection
import org.filezilla.ftp.protocol.FtpFileOperations
import org.filezilla.ftp.protocol.FtpLogger
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.ServerCapabilities
import org.filezilla.ftp.transfer.FtpTransferEngine
import java.io.Closeable

/**
 * One connected control connection, with the browsing operations the UI needs
 * hung off it.
 *
 * Browsing and transferring get separate connections: a transfer occupies its
 * connection for as long as it runs, and the user still wants to look around
 * while a large file is coming down. That is how FileZilla behaves too.
 */
class FtpSession(
    settings: FtpSettings,
    capabilities: ServerCapabilities,
    logger: FtpLogger = FtpLogger.NONE,
) : Closeable {

    private val control = FtpControlConnection(settings, capabilities, logger)
    private val operations = FtpFileOperations(control)
    private val engine = FtpTransferEngine(control, capabilities, logger)

    fun connect() {
        control.connect()
        control.login()
    }

    fun currentDirectory(): String = operations.currentDirectory()

    fun changeDirectory(path: String) = operations.changeDirectory(path)

    fun changeToParent() = operations.changeToParentDirectory()

    fun list(): List<DirectoryEntry> = engine.list()

    fun createDirectory(name: String) = operations.createDirectory(name)

    fun removeDirectory(name: String) = operations.removeDirectory(name)

    fun deleteFile(name: String) = operations.deleteFile(name)

    fun rename(from: String, to: String) = operations.rename(from, to)

    /**
     * What the server says about [remotePath] right now, for
     * [org.filezilla.ftp.journal.ResumeSafety] to compare against what the
     * journal remembers.
     *
     * Taken from a listing of the containing directory rather than `SIZE` and
     * `MDTM`, so that both sides of that comparison always come from the same
     * source: `MLSD` and `MDTM` can disagree about a timestamp's precision,
     * and a difference in precision read as a difference in time would restart
     * a perfectly good transfer.
     */
    fun fingerprint(remotePath: String): RemoteFingerprint {
        val directory = remotePath.substringBeforeLast('/', "")
        val name = remotePath.substringAfterLast('/')
        if (directory.isNotEmpty()) changeDirectory(directory)
        val entry = list().firstOrNull { it.name == name }
        return RemoteFingerprint(
            size = entry?.size?.takeIf { it >= 0 },
            modifiedMillis = entry?.time?.epochMillis,
        )
    }

    override fun close() {
        control.close()
    }
}
