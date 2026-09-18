package org.filezilla.android.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.filezilla.ftp.io.TransferReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The bridge between the engine's plain files and the Storage Access
 * Framework, which is the only way an app can write where the user asked on a
 * modern Android.
 *
 * Downloads go to a *tree* URI -- a folder the user granted -- and the file is
 * created in it only once the transfer is complete. Creating it up front and
 * streaming into it would put a growing, unopenable file in the user's
 * Downloads folder for the whole transfer, and would leave it there if the app
 * were killed.
 */
class SafStorage(private val context: Context) {

    /**
     * Takes a lasting grant on a folder the user picked, so a transfer that
     * finishes tomorrow can still write to it.
     *
     * Without this the permission dies with the activity that asked for it,
     * and a queued transfer picked up after a restart would fail at the last
     * step, having already spent the data.
     */
    fun persistTreePermission(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
    }

    fun persistReadPermission(documentUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                documentUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    /** True when the app still holds a grant for [treeUri]. */
    fun hasTreePermission(treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission
        }

    /** Human-readable name of a granted folder, for the UI. */
    fun displayNameOfTree(treeUri: Uri): String? =
        runCatching { DocumentFile.fromTreeUri(context, treeUri)?.name }.getOrNull()

    /** Name and size of a document the user picked to upload. */
    fun describeDocument(documentUri: Uri): Pair<String, Long>? =
        runCatching {
            val doc = DocumentFile.fromSingleUri(context, documentUri) ?: return null
            val name = doc.name ?: return null
            name to doc.length()
        }.getOrNull()

    /**
     * Moves a finished partial file into the user's folder.
     *
     * @return the URI of the created document.
     */
    fun publish(partial: File, treeUri: Uri, displayName: String): Uri {
        val folder = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("the destination folder is no longer available")
        if (!folder.canWrite()) {
            throw IOException("no permission to write to the destination folder")
        }

        val created = folder.createFile(mimeTypeFor(displayName), displayName)
            ?: throw IOException("could not create $displayName in the destination folder")

        try {
            context.contentResolver.openOutputStream(created.uri, "w")?.use { out ->
                FileInputStream(partial).use { input -> input.copyTo(out) }
            } ?: throw IOException("could not open ${created.uri} for writing")
        } catch (e: IOException) {
            // A half-written document in the user's folder is worse than none:
            // it looks like the finished file. The partial file is kept, so
            // the transfer can be published again without re-downloading.
            runCatching { created.delete() }
            throw e
        }
        return created.uri
    }

    /** A [TransferReader] over a document the user picked, for uploads. */
    fun readerFor(documentUri: Uri): TransferReader = SafTransferReader(context, documentUri)

    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        // Only the handful worth naming: the point of the fallback is that a
        // wrong specific type is worse than an honest generic one, because the
        // provider may append an extension to match it.
        return when (extension) {
            "txt", "log", "md" -> "text/plain"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}

/**
 * Reads an upload source out of a SAF document.
 *
 * Resuming an upload means starting to read partway in, so this seeks the
 * file descriptor rather than skipping bytes through the stream: on a large
 * file over a metered connection, reading and discarding gigabytes to get to
 * the offset is not an acceptable way to resume.
 */
private class SafTransferReader(
    private val context: Context,
    private val uri: Uri,
) : TransferReader {

    private val streams = mutableListOf<InputStream>()

    override val size: Long?
        get() = DocumentFile.fromSingleUri(context, uri)?.length()?.takeIf { it > 0 }

    override val modifiedTime: Long?
        get() = DocumentFile.fromSingleUri(context, uri)?.lastModified()?.takeIf { it > 0 }

    override fun openAt(offset: Long): InputStream {
        require(offset >= 0) { "negative resume offset: $offset" }
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("could not open $uri for reading")
        val file = FileInputStream(descriptor.fileDescriptor)
        if (offset > 0) {
            // Not every provider backs a document with a seekable file. If
            // this one does not, say so rather than silently uploading from
            // the wrong place -- which would corrupt the remote file.
            try {
                file.channel.position(offset)
            } catch (e: IOException) {
                runCatching { file.close() }
                runCatching { descriptor.close() }
                throw IOException("the source document cannot be read from an offset", e)
            }
        }
        // The descriptor has to outlive nothing but the stream, and has to die
        // with it: a leaked ParcelFileDescriptor holds the provider's file
        // open for as long as the process lives.
        val stream = CloseBoth(file) { descriptor.close() }
        streams += stream
        return stream
    }

    override fun close() {
        streams.forEach { runCatching { it.close() } }
        streams.clear()
    }

    /** Closes the descriptor along with the stream that wraps it. */
    private class CloseBoth(
        private val delegate: InputStream,
        private val alsoClose: () -> Unit,
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() {
            runCatching { delegate.close() }
            alsoClose()
        }
    }
}
