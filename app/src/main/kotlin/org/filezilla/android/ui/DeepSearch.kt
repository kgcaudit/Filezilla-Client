package org.filezilla.android.ui

import org.filezilla.android.files.FilePath
import org.filezilla.ftp.listing.DirectoryEntry

/** One thing a search found, and the folder it is in. */
data class SearchHit(
    val folder: String,
    val entry: DirectoryEntry,
) {
    val path: String get() = FilePath.child(folder, entry.name)
}

/** What a finished or abandoned search has to say for itself. */
data class SearchOutcome(
    val hits: List<SearchHit> = emptyList(),
    /** True when a limit was reached, so the answer is not the whole answer. */
    val truncated: Boolean = false,
    /** How many folders were opened to find this, which is the cost paid. */
    val foldersRead: Int = 0,
    /** True when the user called it off partway. */
    val cancelled: Boolean = false,
)

/**
 * Looking for a name below the folder on screen.
 *
 * Kept apart from the filter, which is a pure predicate over the rows already
 * on the screen and stays that way. That filter is right as far as it goes:
 * it is instant, needs no network, and leaves the result a folder listing
 * that can still be selected and pasted from. What it cannot do is see into
 * anything, and the magnifier the app puts it behind promises exactly that.
 *
 * So this is the deliberate, paid-for version, and everything about it is
 * shaped by the cost. On a server, each folder is a `CWD` and a `LIST` -- a
 * round trip -- so a tree of five hundred folders is five hundred of them.
 * That is fine as something a person asks for once and can call off; it
 * would not be fine on every keystroke, which is why the filter above is not
 * simply made recursive.
 *
 * Breadth first, on purpose. The thing being looked for is far more often
 * one or two levels down than twenty, so the useful answers arrive first --
 * and a search that is called off, or that runs into a limit, has then given
 * back the part most likely to be wanted rather than one deep branch.
 *
 * One algorithm for both sides. The phone and the server differ only in what
 * lists a folder, so they are the same walk with a different [RemoteLister],
 * and the awkward cases -- links, depth, a tree bigger than anyone meant to
 * search -- are decided once.
 */
object DeepSearch {

    /**
     * How deep to go. The same floor the download walk uses, and for the
     * same reason: a tree is not ours to trust, and a walk with nothing
     * under it recurses until the stack runs out.
     */
    const val MAX_DEPTH = 24

    /** Enough results to be useful; past this the answer is "narrow it". */
    const val MAX_HITS = 500

    /**
     * How many folders may be opened. This is the real budget on a server,
     * where each one is a round trip, and it is what stops a search started
     * at the root of a NAS from running for an hour.
     */
    const val MAX_FOLDERS = 2_000

    /**
     * Walks below [root] for entries whose name contains [needle].
     *
     * [lister] is asked for one folder at a time and may throw -- a folder
     * that cannot be read is passed over rather than ending the search,
     * because on a real server some of them will not be readable and
     * stopping at the first one would make the feature useless.
     *
     * [onHit] is called as each result is found, so the screen can fill as
     * the walk goes rather than after it. [cancelled] is asked between
     * folders; a search called off returns what it had.
     */
    fun walk(
        root: String,
        lister: RemoteLister,
        needle: String,
        showHidden: Boolean = false,
        cancelled: () -> Boolean = { false },
        onHit: (SearchHit) -> Unit = {},
    ): SearchOutcome {
        val wanted = needle.trim()
        if (wanted.isEmpty()) return SearchOutcome()

        val hits = mutableListOf<SearchHit>()
        var truncated = false
        var foldersRead = 0

        // The queue is the breadth: a folder and how deep it sits.
        val queue = ArrayDeque(listOf(root to 0))
        while (queue.isNotEmpty()) {
            if (cancelled()) {
                return SearchOutcome(hits.toList(), truncated, foldersRead, cancelled = true)
            }
            if (foldersRead >= MAX_FOLDERS || hits.size >= MAX_HITS) {
                truncated = true
                break
            }
            val (folder, depth) = queue.removeFirst()
            // A folder that will not open is not a reason to stop. On a
            // server some of them are not readable by this user, and giving
            // up at the first would mean the search never got past it.
            val rows = runCatching { lister.list(folder) }.getOrNull() ?: continue
            foldersRead++

            for (entry in rows) {
                // Navigation, not content: the walk would otherwise climb
                // back up through ".." and never end.
                if (entry.name == "." || entry.name == "..") continue
                if (!showHidden && entry.name.startsWith(".")) continue

                if (entry.name.contains(wanted, ignoreCase = true)) {
                    if (hits.size >= MAX_HITS) {
                        truncated = true
                        break
                    }
                    val hit = SearchHit(folder, entry)
                    hits += hit
                    onHit(hit)
                }

                // Links are not followed. One can point at its own parent,
                // and following it walks the same folders for ever. A
                // listing marks a link as a directory whether or not it is
                // one, so this passes over file links too -- the cheaper
                // mistake of the two.
                if (entry.isDirectory && !entry.isLink && depth + 1 < MAX_DEPTH) {
                    queue.addLast(FilePath.child(folder, entry.name) to depth + 1)
                }
            }
        }

        return SearchOutcome(hits.toList(), truncated, foldersRead)
    }
}
