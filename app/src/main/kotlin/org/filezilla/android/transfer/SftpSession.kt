package org.filezilla.android.transfer

import org.filezilla.ftp.journal.RemoteFingerprint
import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.protocol.FtpLogger
import org.filezilla.ftp.sftp.SftpEngine
import org.filezilla.ftp.sftp.SftpSettings

/**
 * A [RemoteSession] over SFTP, the counterpart of [FtpSession].
 *
 * All the real work is [SftpEngine]'s; this only adds what the browsing pool
 * needs around it -- the [writes] counter [TransferManager.browse] reads to
 * know when to drop a cached listing. SFTP has no separate control connection,
 * so browsing and transferring both run over the one engine; a transfer still
 * gets its own engine, as an FTP transfer gets its own control connection, so
 * a download does not freeze the pane it was tapped from.
 */
class SftpSession(
    settings: SftpSettings,
    logger: FtpLogger = FtpLogger.NONE,
) : RemoteSession {

    private val engine = SftpEngine(settings, logger)

    override fun connect() = engine.connect()

    override fun currentDirectory(): String = engine.currentDirectory()

    override fun changeDirectory(path: String): String? = engine.changeDirectory(path)

    override fun changeToParent() = engine.changeToParent()

    override fun list(): List<DirectoryEntry> = engine.list()

    override fun createDirectory(name: String) = writing { engine.createDirectory(name) }

    override fun removeDirectory(name: String) = writing { engine.removeDirectory(name) }

    override fun deleteFile(name: String) = writing { engine.deleteFile(name) }

    override fun rename(from: String, to: String) = writing { engine.rename(from, to) }

    override fun changeMode(path: String, mode: String) = writing { engine.changeMode(path, mode) }

    /** See [FtpSession.writes] for why this is a count that only goes up. */
    override var writes: Int = 0
        private set

    private inline fun <T> writing(block: () -> T): T {
        writes++
        return block()
    }

    override fun fingerprint(remotePath: String): RemoteFingerprint = engine.fingerprint(remotePath)

    override fun close() = engine.close()
}
