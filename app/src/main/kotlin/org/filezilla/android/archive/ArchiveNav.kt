package org.filezilla.android.archive

import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.listing.EntryTime
import org.filezilla.ftp.listing.TimeAccuracy

/**
 * Being inside an archive, as a place a pane is looking at.
 *
 * The whole point of this file is that an archive is browsed like a
 * folder rather than in a window of its own: the pane keeps its real
 * source and the folder it was in, and gains one of these while it is
 * showing the inside of an archive. Backing out of the archive root
 * clears it and the pane is where it was.
 */
data class ArchiveSession(
    /** The archive on disk -- a phone file, or a server file fetched to cache. */
    val file: java.io.File,
    /** The archive's own name, for the breadcrumb. */
    val name: String,
    /** The real folder to return to when backing out of the archive. */
    val home: String,
    val entries: List<ArchiveEntry>,
    /** The folder inside the archive, without a trailing slash; "" is the root. */
    val at: String = "",
)

/**
 * The rows and the walking-about inside an archive, without any Compose.
 *
 * The rows are handed back as [DirectoryEntry] -- the same type a folder's
 * rows are -- so the list, the sort, the filter and the selection are the
 * ones the browser already has. Nothing here is a second file list; it is
 * the archive dressed as the one there is.
 */
object ArchiveNav {

    /** A path that cannot be a real one, marking a breadcrumb that stays in the archive. */
    const val SCHEME = "\u0000archive\u0000"

    /**
     * Whether a tapped file is worth opening in the archive viewer.
     *
     * Extension first, not the bytes: a `.docx`, a `.jar`, a `.apk` are all
     * zips underneath, and opening every one of them in an archive list
     * rather than in the app that reads it is exactly the surprise a tap
     * should not spring. An app package is never browsed -- it is
     * installed -- and that is checked by the caller before this. What is
     * left is the three extensions this app actually browses.
     */
    fun browsable(name: String): Boolean = Archives.looksLikeArchive(name)

    /** The rows directly inside [session]'s current folder, as folder rows. */
    fun rows(session: ArchiveSession): List<DirectoryEntry> =
        ArchiveBrowsing.rowsIn(session.entries, session.at).map { row ->
            DirectoryEntry(
                name = row.name,
                size = if (row.isDirectory) -1 else row.size,
                isDirectory = row.isDirectory,
                time = row.modifiedMillis?.let { EntryTime(it, TimeAccuracy.MINUTES) },
            )
        }

    /** The archive entry a row in the current folder stands for, or null. */
    fun entryFor(session: ArchiveSession, name: String): ArchiveEntry? {
        val path = if (session.at.isEmpty()) name else session.at + "/" + name
        return session.entries.firstOrNull { !it.isDirectory && it.path == path }
    }

    /** Descending into a folder named [name] within the current one. */
    fun into(session: ArchiveSession, name: String): ArchiveSession =
        session.copy(at = if (session.at.isEmpty()) name else session.at + "/" + name)

    /**
     * One step back: a shallower folder, or null when already at the root.
     *
     * Null is the signal to leave the archive entirely -- the caller lists
     * [ArchiveSession.home] again -- so backing out of an archive is the
     * same gesture as walking up out of a folder.
     */
    fun up(session: ArchiveSession): ArchiveSession? =
        ArchiveBrowsing.upFrom(session.at)?.let { session.copy(at = it) }


    /** Where a breadcrumb tap lands: the archive folder to show, or null to leave. */
    fun target(path: String): String? = if (path.startsWith(SCHEME)) path.removePrefix(SCHEME) else null
}
