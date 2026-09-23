package org.filezilla.android.ui

import org.filezilla.android.files.NaturalOrder
import org.filezilla.ftp.listing.DirectoryEntry

/** What the listing is ordered by. */
enum class SortKey { NAME, SIZE, DATE, TYPE }

/** How the listing is drawn. */
enum class ViewMode { LIST, GRID }

/**
 * The user's standing preferences for the file list.
 *
 * Kept apart from [BrowseState] because these outlive a connection: they are
 * how this person likes to see a directory, not what is in one.
 */
data class BrowseOptions(
    val sortKey: SortKey = SortKey.NAME,
    val ascending: Boolean = true,
    /** Directories above files, which is what almost everyone expects. */
    val foldersFirst: Boolean = true,
    /** Dotfiles. Off by default, since on a server they are mostly noise. */
    val showHidden: Boolean = false,
    val viewMode: ViewMode = ViewMode.LIST,
)

/**
 * Turns what the server sent into what the screen shows.
 *
 * A pure function, and deliberately so: sorting and filtering is the kind of
 * logic that goes subtly wrong -- a comparator that ignores the direction
 * flag, a filter that is case sensitive only for the first character, folders
 * that stop being first when sorting by size -- and none of that is visible
 * from a screenshot. It is tested instead.
 */
object BrowseListing {

    fun arrange(
        entries: List<DirectoryEntry>,
        options: BrowseOptions,
        filter: String,
    ): List<DirectoryEntry> {
        val needle = filter.trim()
        var rows = entries.asSequence()

        // "." and ".." are navigation, not content: the screen has an up
        // button for that, and a server that lists them should not make the
        // user scroll past them.
        rows = rows.filterNot { it.name == "." || it.name == ".." }

        if (!options.showHidden) {
            rows = rows.filterNot { it.name.startsWith(".") }
        }
        if (needle.isNotEmpty()) {
            rows = rows.filter { it.name.contains(needle, ignoreCase = true) }
        }

        // Case-insensitive and value-aware, so "album" and "Photos" sort where
        // a reader expects and a page named "10" comes after "2", not before.
        val byName = NaturalOrder.by<DirectoryEntry> { it.name }
        val byKey = when (options.sortKey) {
            SortKey.NAME -> byName

            // A directory has no meaningful size, so size order falls back to
            // the name for them rather than scattering them by -1.
            SortKey.SIZE -> compareBy<DirectoryEntry> { if (it.isDirectory) -1L else it.size }
                .then(byName)

            // Entries with no timestamp sort together at one end instead of
            // being interleaved arbitrarily.
            SortKey.DATE -> compareBy<DirectoryEntry> { it.time?.epochMillis ?: Long.MIN_VALUE }
                .then(byName)

            SortKey.TYPE -> compareBy<DirectoryEntry> { extensionOf(it) }
                .then(byName)
        }

        val directed = if (options.ascending) byKey else byKey.reversed()

        // Folders-first is applied after the direction, so reversing the sort
        // does not drop directories to the bottom.
        val comparator = if (options.foldersFirst) {
            compareByDescending<DirectoryEntry> { it.isDirectory }.then(directed)
        } else {
            directed
        }

        return rows.sortedWith(comparator).toList()
    }

    /** Lowercased extension, or empty for a directory or a name without one. */
    fun extensionOf(entry: DirectoryEntry): String =
        if (entry.isDirectory) "" else entry.name.substringAfterLast('.', "").lowercase()
}
