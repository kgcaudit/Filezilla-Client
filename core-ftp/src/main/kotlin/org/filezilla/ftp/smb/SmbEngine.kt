package org.filezilla.ftp.smb

import org.filezilla.ftp.io.TransferReader
import org.filezilla.ftp.io.TransferWriter
import org.filezilla.ftp.journal.RemoteFingerprint
import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.protocol.FtpLogger
import org.filezilla.ftp.protocol.LogLevel
import org.filezilla.ftp.transfer.TransferDisposition
import org.filezilla.ftp.transfer.TransferOutcome
import org.filezilla.ftp.transfer.TransferProgressListener
import java.io.Closeable

/**
 * Moves files over SMB, in the shape the app's transfer code already speaks.
 *
 * The browsing lives in [SmbClient]; this wraps it in the engine contract the
 * FTP and SFTP engines meet -- [download]/[upload] over [TransferWriter] and
 * [TransferReader], returning a [TransferOutcome] -- so [SmbResilientTransfer]
 * and the app dispatch look the same as SFTP's.
 *
 * Both directions resume, unlike WebDAV: SMB writes and reads at an explicit
 * offset, so a download continues onto the partial local file and an upload
 * continues onto the partial remote one, with the same guard the SFTP engine
 * uses -- a partial already as big as the other side is complete and skipped,
 * and a local partial bigger than the remote file means the remote changed, so
 * the transfer starts over rather than splicing two files together.
 */
class SmbEngine(
    private val settings: SmbSettings,
    private val logger: FtpLogger = FtpLogger.NONE,
) : Closeable {

    private val client = SmbClient(settings)

    fun connect() = client.connect()

    fun fingerprint(remoteFile: String): RemoteFingerprint = client.fingerprint(remoteFile)

    fun list(dirPath: String): List<DirectoryEntry> = client.list(dirPath)

    fun download(
        remoteFile: String,
        writer: TransferWriter,
        resume: Boolean = true,
        progress: TransferProgressListener? = null,
        forcedResumeOffset: Long? = null,
    ): TransferOutcome = writer.use { doDownload(remoteFile, it, resume, progress, forcedResumeOffset) }

    private fun doDownload(
        remoteFile: String,
        writer: TransferWriter,
        resume: Boolean,
        progress: TransferProgressListener?,
        forcedResumeOffset: Long?,
    ): TransferOutcome {
        val remote = client.fingerprint(remoteFile)
        val remoteSize = remote.size
        val localSize = writer.existingSize

        var resumeOffset = 0L
        when {
            forcedResumeOffset != null -> resumeOffset = forcedResumeOffset
            resume && localSize != null && localSize > 0 -> resumeOffset = when {
                remoteSize == null -> localSize
                localSize == remoteSize -> {
                    logger.log(LogLevel.STATUS, "Local file is already complete, skipping download")
                    remote.modifiedMillis?.let { writer.setModifiedTime(it) }
                    return TransferOutcome(TransferDisposition.ALREADY_COMPLETE, localSize, 0, remoteSize)
                }
                localSize > remoteSize -> {
                    logger.log(
                        LogLevel.STATUS,
                        "Local partial file ($localSize bytes) is larger than the remote file " +
                            "($remoteSize bytes); the remote file changed, restarting download",
                    )
                    0
                }
                else -> localSize
            }
        }

        val out = writer.openAt(resumeOffset)
        if (remoteSize != null && remoteSize > resumeOffset) writer.preallocate(remoteSize - resumeOffset)
        val total = client.download(remoteFile, resumeOffset, out) { position ->
            progress?.onProgress(position - resumeOffset, resumeOffset, remoteSize)
        }
        out.flush()
        remote.modifiedMillis?.let { writer.setModifiedTime(it) }

        val transferred = (total - resumeOffset).coerceAtLeast(0)
        return TransferOutcome(
            disposition = if (resumeOffset > 0) TransferDisposition.RESUMED else TransferDisposition.TRANSFERRED,
            resumeOffset = resumeOffset,
            bytesTransferred = transferred,
            totalSize = remoteSize ?: total,
        )
    }

    fun upload(
        remoteFile: String,
        reader: TransferReader,
        resume: Boolean = true,
        progress: TransferProgressListener? = null,
    ): TransferOutcome = reader.use { doUpload(remoteFile, it, resume, progress) }

    private fun doUpload(
        remoteFile: String,
        reader: TransferReader,
        resume: Boolean,
        progress: TransferProgressListener?,
    ): TransferOutcome {
        client.ensureParentsOf(remoteFile)
        val localSize = reader.size
        val remoteSize = if (resume) client.fingerprint(remoteFile).size else null

        var resumeOffset = 0L
        if (resume && remoteSize != null && remoteSize > 0) {
            resumeOffset = remoteSize
            if (localSize != null && resumeOffset >= localSize) {
                logger.log(LogLevel.DEBUG, "Remote file is already at least as large as the source; nothing to send")
                return TransferOutcome(TransferDisposition.ALREADY_COMPLETE, resumeOffset, 0, remoteSize)
            }
        }

        val input = reader.openAt(resumeOffset)
        client.upload(remoteFile, input, resumeOffset) { sent ->
            progress?.onProgress(sent, resumeOffset, localSize)
        }

        val transferred = (localSize ?: resumeOffset) - resumeOffset
        return TransferOutcome(
            disposition = if (resumeOffset > 0) TransferDisposition.RESUMED else TransferDisposition.TRANSFERRED,
            resumeOffset = resumeOffset,
            bytesTransferred = transferred.coerceAtLeast(0),
            totalSize = localSize ?: (resumeOffset + transferred).coerceAtLeast(0),
        )
    }

    override fun close() = client.close()
}
