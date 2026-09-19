package org.filezilla.android.files

import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.listing.EntryTime
import org.filezilla.ftp.listing.TimeAccuracy
import java.io.File
import java.io.IOException

/**
 * The phone's own storage, as a pane sees it.
 *
 * Rows come back as [DirectoryEntry] -- the type the FTP listing already uses
 * -- so that one row composable draws both sides. A local file has more to
 * say than a listing line does, not less, so nothing is lost by it.
 */
class LocalFileSource(override val label: String) : FileSource {

    override fun list(path: String): List<DirectoryEntry> {
        val directory = File(FilePath.normalize(path))
        // Told apart on purpose. "Not a folder", "no permission" and "empty"
        // look identical from a null return, and the screen has a different
        // thing to say about each.
        if (!directory.exists()) throw IOException("${directory.name} is no longer there")
        if (!directory.isDirectory) throw IOException("${directory.name} is not a folder")
        val children = directory.listFiles()
            ?: throw IOException("${directory.name} cannot be read")
        return children.map(::entryOf)
    }
}

/**
 * One file as a listing row.
 *
 * A directory's `length()` is not a size -- it is whatever the filesystem
 * keeps its index in -- so it is reported as unknown, which is what the FTP
 * side does and what the row already knows how to draw.
 */
internal fun entryOf(file: File): DirectoryEntry {
    val directory = file.isDirectory
    return DirectoryEntry(
        name = file.name,
        size = if (directory) -1 else file.length(),
        isDirectory = directory,
        // A symlink is followed for "is it a folder" but flagged, so a walk
        // can refuse to follow it -- a link to its own parent is an endless
        // walk, which is the reason the download planner skips them too.
        isLink = runCatching { file.canonicalPath != file.absolutePath }.getOrDefault(false),
        time = file.lastModified().takeIf { it > 0 }
            ?.let { EntryTime(it, TimeAccuracy.SECONDS) },
    )
}
