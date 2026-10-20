package org.filezilla.ftp.webdav

import org.filezilla.ftp.io.TransferReader
import org.filezilla.ftp.io.TransferWriter
import org.filezilla.ftp.journal.RemoteFingerprint
import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.transfer.TransferDisposition
import org.filezilla.ftp.transfer.TransferOutcome
import org.filezilla.ftp.transfer.TransferProgressListener
import java.io.Closeable

/**
 * Moves files over WebDAV, in the shape the app's transfer code already speaks.
 *
 * The browsing lives in [WebDavClient]; this wraps it in the engine contract the
 * FTP and SFTP engines meet -- [download]/[upload] over [TransferWriter] and
 * [TransferReader], returning a [TransferOutcome] -- so [WebDavResilientTransfer]
 * and the app dispatch look the same as SFTP's and nothing special is needed
 * downstream.
 *
 * A download resumes from an offset with a ranged GET. An upload replaces the
 * whole file: WebDAV `PUT` has no append, so there is no half-sent file to
 * resume onto, which is why [upload] ignores the resume flag.
 */
class WebDavEngine(private val settings: WebDavSettings) : Closeable {

    private val client = WebDavClient(settings)

    fun connect() = client.connect()

    fun fingerprint(remoteFile: String): RemoteFingerprint = client.fingerprint(remoteFile)

    fun list(dirPath: String): List<DirectoryEntry> = client.list(dirPath)

    fun download(
        remoteFile: String,
        writer: TransferWriter,
        progress: TransferProgressListener?,
        forcedResumeOffset: Long?,
    ): TransferOutcome {
        val total = client.fingerprint(remoteFile).size
        var offset = forcedResumeOffset ?: writer.existingSize ?: 0L
        if (offset < 0) offset = 0

        fun fetchFrom(start: Long): Long {
            writer.openAt(start).use { out ->
                client.download(remoteFile, start, out) { done ->
                    progress?.onProgress(done - start, start, total)
                }
            }
            return start
        }

        val startedAt = try {
            fetchFrom(offset)
        } catch (e: WebDavResumeUnsupported) {
            // The server answered a ranged request with the whole file, so the
            // only safe thing is to take it from the top rather than splice.
            fetchFrom(0)
        }

        val finalSize = total ?: client.fingerprint(remoteFile).size
        return TransferOutcome(
            disposition = if (startedAt > 0) TransferDisposition.RESUMED else TransferDisposition.TRANSFERRED,
            resumeOffset = startedAt,
            bytesTransferred = (finalSize ?: 0L) - startedAt,
            totalSize = finalSize,
        )
    }

    fun upload(
        remoteFile: String,
        reader: TransferReader,
        @Suppress("UNUSED_PARAMETER") resume: Boolean,
        progress: TransferProgressListener?,
    ): TransferOutcome {
        val length = reader.size ?: error("the file's size is needed to upload over WebDAV")
        reader.openAt(0).use { input ->
            client.upload(remoteFile, input, length) { done ->
                progress?.onProgress(done, 0, length)
            }
        }
        return TransferOutcome(
            disposition = TransferDisposition.TRANSFERRED,
            resumeOffset = 0,
            bytesTransferred = length,
            totalSize = length,
        )
    }

    // HttpClient pools and closes its own connections; there is nothing to hold.
    override fun close() {}
}
