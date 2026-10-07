package org.filezilla.android.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.filezilla.android.files.ExifFacts
import org.filezilla.android.files.ImageExif
import java.io.File

/**
 * The photo whose EXIF the info sheet is showing, with the facts already
 * read and whether this copy is the user's own file to edit.
 */
data class PhotoInfo(
    val file: File,
    val facts: ExifFacts,
    /** True when the file is a real local image whose EXIF can be written. */
    val editable: Boolean,
)

/**
 * The photo info sheet: reading an image's EXIF, and the few edits the app
 * makes to it -- rotate, clear the location, clear it all.
 *
 * Lifted out of [MainViewModel]. Self-contained: it reads and writes a file's
 * tags through [ImageExif] and tells the media scanner, and knows nothing of
 * panes or servers. The view model, which holds the open image as an
 * [ImageRef], resolves that to a [File] and hands it to [open].
 *
 * [scope] is the view model's own, so a running read or edit is cancelled when
 * the view model goes.
 */
class PhotoController(
    private val app: Application,
    private val scope: CoroutineScope,
) {

    var info by mutableStateOf<PhotoInfo?>(null)
        private set

    /**
     * Bumped after an edit so the open image re-decodes and shows the change.
     *
     * A rotate only rewrites a tag, so the file is the same path with the same
     * size -- nothing the pager keys its decode on moves. This is the one thing
     * that does, and the page reads it, so turning a photo turns it on screen.
     */
    var revision by mutableStateOf(0)
        private set

    /** Opens the info sheet for [file], reading its EXIF off the main thread. */
    fun open(file: File) {
        scope.launch {
            val facts = withContext(Dispatchers.IO) { ImageExif.read(file) }
            info = PhotoInfo(file = file, facts = facts, editable = canEdit(file))
        }
    }

    fun close() {
        info = null
    }

    /**
     * Whether [file] is one whose EXIF the app may write back.
     *
     * A writable format, a real file the user owns, and not a throwaway copy of
     * a server file under the cache -- editing one of those would change nothing
     * the user keeps. So a remote photo is read-only here, as the sheet says.
     */
    private fun canEdit(file: File): Boolean {
        if (!ImageExif.canEdit(file.name)) return false
        val cache = app.cacheDir.absolutePath
        if (file.absolutePath.startsWith(cache)) return false
        return file.canWrite()
    }

    fun rotate(clockwise: Boolean) = edit { ImageExif.rotate(it, clockwise) }

    fun clearLocation() = edit { ImageExif.clearLocation(it) }

    fun clearAll() = edit { ImageExif.clearAll(it) }

    /**
     * Runs one edit on the open photo, then re-reads the facts, redraws the
     * page and lets the gallery know -- so the sheet, the image and the rest of
     * the phone all see the change at once.
     */
    private fun edit(edit: (File) -> Boolean) {
        val open = info?.takeIf { it.editable } ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { edit(open.file) }
            if (!ok) return@launch
            val facts = withContext(Dispatchers.IO) { ImageExif.read(open.file) }
            info = open.copy(facts = facts)
            revision++
            // The file changed under MediaStore's feet; a scan keeps the gallery
            // and every other app's idea of it in step with the new bytes.
            runCatching {
                android.media.MediaScannerConnection.scanFile(
                    app, arrayOf(open.file.absolutePath), null, null,
                )
            }
        }
    }
}
