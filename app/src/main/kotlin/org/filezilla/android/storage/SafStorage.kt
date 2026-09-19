package org.filezilla.android.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import org.filezilla.ftp.io.TransferReader
import org.filezilla.ftp.io.asTransferReader
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

    /** Size and modification time of a file already in the user's folder. */
    data class ExistingDocument(val size: Long, val modifiedMillis: Long)


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
    fun publish(partial: File, destination: DownloadDestination, displayName: String): Uri? {
        // A folder the user browsed to in a pane, rather than one granted
        // through the picker. Kept as a file:// URI so that the journal's
        // destination column holds one kind of thing and records written
        // before panes existed still decode exactly as they did.
        if (destination.isLocalPath) return publishLocally(partial, destination, displayName)

        val root = DocumentFile.fromTreeUri(context, destination.tree)
            ?: throw IOException("the destination folder is no longer available")
        if (!root.canWrite()) {
            throw IOException("no permission to write to the destination folder")
        }
        val folder = descend(root, destination.subPath)

        var name = displayName
        val existing = folder.findFile(displayName)?.takeIf { it.isFile }
        if (existing != null) {
            when (destination.onConflict) {
                // Nothing to write, and null says so rather than throwing:
                // the transfer succeeded, it simply has nowhere to go. What
                // becomes of the fetched bytes is the caller's to decide.
                ConflictChoice.SKIP -> return null

                // Removed first, because createFile would otherwise hand back
                // "name (1)" and leave the old file in place -- which is the
                // opposite of what overwrite means.
                ConflictChoice.OVERWRITE ->
                    if (!existing.delete()) {
                        throw IOException("could not replace the existing $displayName")
                    }

                // Numbered here rather than by the provider. Left to it,
                // "movie.mkv" became "movie.mkv (1)" -- a name whose extension
                // is now " (1)", so nothing will open it.
                ConflictChoice.KEEP_BOTH -> name = freeNameIn(folder, displayName)
            }
        }

        val created = folder.createFile(mimeTypeFor(name), name)
            ?: throw IOException("could not create $name in the destination folder")

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

    /**
     * Walks down to the sub-folder a download belongs in, creating what is
     * missing.
     *
     * Each level is looked up before it is created: a folder downloaded twice,
     * or resumed after a restart, has to land back in the folder it already
     * has rather than beside a second copy of it.
     */
    /**
     * The same move, into an ordinary folder.
     *
     * The conflict choices mean exactly what they do above, and the numbering
     * comes from the same [numberedName], so a file kept alongside another is
     * named the same way whichever kind of folder it lands in.
     */
    private fun publishLocally(
        partial: File,
        destination: DownloadDestination,
        displayName: String,
    ): Uri? {
        val folder = File(destination.localPath, destination.subPath.joinToString(File.separator))
        if (!folder.isDirectory && !folder.mkdirs()) {
            throw IOException("could not create the destination folder")
        }

        var target = File(folder, displayName)
        if (target.exists()) {
            when (destination.onConflict) {
                ConflictChoice.SKIP -> return null

                ConflictChoice.OVERWRITE ->
                    if (!target.delete()) {
                        throw IOException("could not replace the existing $displayName")
                    }

                ConflictChoice.KEEP_BOTH -> target = freeFileIn(folder, displayName)
            }
        }

        partial.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(target)
    }

    private fun freeFileIn(folder: File, displayName: String): File {
        for (n in 1..MAX_NUMBERED) {
            val candidate = File(folder, numberedName(displayName, n))
            if (!candidate.exists()) return candidate
        }
        throw IOException("too many files named $displayName")
    }

    private fun descend(root: DocumentFile, subPath: List<String>): DocumentFile {
        var folder = root
        for (segment in subPath) {
            val existing = folder.findFile(segment)
            folder = when {
                existing == null -> folder.createDirectory(segment)
                    ?: throw IOException("could not create the folder $segment")
                existing.isDirectory -> existing
                // A file of that name is in the way. Writing into it is not
                // possible and deleting it is not ours to do.
                else -> throw IOException("$segment already exists as a file in the destination folder")
            }
        }
        return folder
    }

    /**
     * What is already at [displayName] in the destination, if anything.
     *
     * Creates nothing: the folders are walked only as far as they exist, so
     * asking about a file cannot leave empty folders behind for a download the
     * user then cancels.
     */
    fun existingDocument(destination: DownloadDestination, displayName: String): ExistingDocument? =
        runCatching {
            if (destination.isLocalPath) {
                val file = File(
                    File(destination.localPath, destination.subPath.joinToString(File.separator)),
                    displayName,
                )
                return if (file.isFile) {
                    ExistingDocument(file.length(), file.lastModified())
                } else {
                    null
                }
            }
            var folder = DocumentFile.fromTreeUri(context, destination.tree) ?: return null
            for (segment in destination.subPath) {
                folder = folder.findFile(segment)?.takeIf { it.isDirectory } ?: return null
            }
            val file = folder.findFile(displayName)?.takeIf { it.isFile } ?: return null
            ExistingDocument(size = file.length(), modifiedMillis = file.lastModified())
        }.getOrNull()

    /** A [TransferReader] over a document the user picked, for uploads. */
    /**
     * A reader over the upload source, whichever kind of place it came from.
     *
     * A file:// source is a path a pane walked to; anything else is a
     * document the user picked. Told apart here so that neither caller has to
     * know which kind it handed over.
     */
    fun readerFor(documentUri: Uri): TransferReader =
        if (documentUri.scheme == "file") {
            File(requireNotNull(documentUri.path) { "a file URI with no path" }).asTransferReader()
        } else {
            SafTransferReader(context, documentUri)
        }

    /**
     * The first name in [folder] that nothing is using.
     *
     * Numbering the file is ours to do. `createFile` takes a name that is
     * already taken and returns one of its own choosing, and what it chose for
     * "movie.mkv" was "movie.mkv (1)" -- the number after the extension, so
     * the file no longer had one and nothing would open it.
     */
    private fun freeNameIn(folder: DocumentFile, displayName: String): String {
        for (n in 1..MAX_NUMBERED) {
            val candidate = numberedName(displayName, n)
            if (folder.findFile(candidate) == null) return candidate
        }
        // Past the limit, fall back to the provider's own numbering rather
        // than refusing the download outright. An ugly name beats losing it.
        return displayName
    }

    /**
     * The type the file is saved as, which decides what opens it.
     *
     * Asked of the platform's own extension table rather than a list kept
     * here: it knows mkv, srt, smi and several hundred others, and a type
     * this class has never heard of is the common case.
     */
    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return FALLBACK_MIME_TYPE
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: FALLBACK_MIME_TYPE
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

/** An honest generic type, for a file whose extension means nothing here. */
private const val FALLBACK_MIME_TYPE = "application/octet-stream"

/** How many numbered names to try before giving up and letting the provider pick. */
private const val MAX_NUMBERED = 999

/**
 * "movie.mkv" and 1 gives "movie (1).mkv".
 *
 * The number goes before the extension, because the extension is what decides
 * whether anything can open the file. Put after it -- which is what the
 * platform does when left to itself -- "movie.mkv" becomes "movie.mkv (1)",
 * whose extension is " (1)".
 *
 * Top-level and tested, because the cases that break it are the ones that
 * never come up while writing it: a name with no extension, a dotfile, a
 * double extension.
 */
fun numberedName(displayName: String, n: Int): String {
    val dot = displayName.lastIndexOf('.')
    // A leading dot is a hidden file, not an extension: ".gitignore" numbers
    // as ".gitignore (1)", not " (1).gitignore".
    if (dot <= 0) return "$displayName ($n)"
    val base = displayName.substring(0, dot)
    val extension = displayName.substring(dot)
    return "$base ($n)$extension"
}
