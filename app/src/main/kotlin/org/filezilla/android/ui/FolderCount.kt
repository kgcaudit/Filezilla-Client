package org.filezilla.android.ui

import org.filezilla.ftp.listing.DirectoryEntry
import java.io.File

/**
 * How much is in a folder, without opening it.
 *
 * A row that says only a date leaves the one question a folder raises
 * unanswered -- is there anything in there -- and answering it costs a tap,
 * a listing, and a tap back. Every file manager worth the name says it on
 * the row.
 *
 * On the phone only, and that is a deliberate line. Counting a local folder
 * is one `list()`, a fraction of a millisecond off the main thread. Counting
 * a folder on a server is a `CWD` and a `LIST` -- a round trip -- so a
 * listing showing twenty folders would cost twenty of them before it could
 * finish drawing, on the same connection the user is trying to navigate
 * with. The same reason the filter is not quietly recursive.
 */
object FolderCount {

    /**
     * How many entries [path] holds, or null when that cannot be known.
     *
     * Null rather than zero for a folder that will not open: "비어 있음" for
     * a folder the app simply cannot read would be a claim about its
     * contents, and the row says nothing instead.
     */
    fun of(path: String): Int? {
        val folder = File(path)
        if (!folder.isDirectory) return null
        // list(), not listFiles(): only the number is wanted, and listFiles
        // builds a File for every name to throw them all away.
        return folder.list()?.size
    }
}

/** What one folder's worth of rows adds up to. */
data class FolderSummary(val folders: Int, val files: Int) {
    val isEmpty: Boolean get() = folders == 0 && files == 0
}

/**
 * The rows of one folder, counted.
 *
 * Over what the screen is showing rather than what the server sent, so it
 * agrees with the list above it: with hidden files off, or a filter on, a
 * count of everything would be a number the user cannot find by counting.
 */
fun summarise(rows: List<DirectoryEntry>): FolderSummary = FolderSummary(
    folders = rows.count { it.isDirectory },
    files = rows.count { !it.isDirectory },
)
