package org.filezilla.android.ui

import org.filezilla.android.files.FilePath
import org.filezilla.android.files.Meta

/** A tree enumerated for a mirror, and what had to be left out of it. */
data class ScanResult(
    /**
     * Everything below the root, keyed by path relative to it -- the exact
     * shape [org.filezilla.android.files.SyncDiff.diff] wants. The root itself
     * is not a key; it is the thing the paths are relative to.
     */
    val entries: Map<String, Meta> = emptyMap(),
    /** Links passed over rather than followed; see [SyncScan.scan]. */
    val skippedLinks: Int = 0,
    /** True when a cap stopped the walk, so the map is not the whole tree. */
    val truncated: Boolean = false,
    /** True when the user called it off, so the map is not the whole tree. */
    val cancelled: Boolean = false,
    /** Folders opened, which is what the scan cost on a server. */
    val foldersRead: Int = 0,
)

/**
 * Enumerates one whole tree into the map a mirror compares against.
 *
 * The counterpart to [DeepSearch] for folder sync: the same breadth-first walk
 * behind the same [RemoteLister], so the phone and a server are one algorithm
 * with a different lister -- but where a search keeps only the rows whose name
 * matches, this keeps every row, as a relative path with the size, time and
 * kind [org.filezilla.android.files.SyncDiff] needs to decide what to do with
 * it. Both sides of a mirror are scanned this way; the diff does the rest.
 *
 * Everything that makes the search safe on a real server is kept: a floor
 * under the recursion, a ceiling on how many folders it will open, links left
 * unfollowed so a link to its own parent cannot loop forever, and a folder that
 * will not open passed over rather than ending the walk.
 */
object SyncScan {

    /** How deep to go. The same floor [DeepSearch] and [FolderDownload] use. */
    const val MAX_DEPTH = 24

    /**
     * How many folders may be opened before the scan gives up. The real budget
     * on a server, where each folder is a round trip -- the same ceiling the
     * deep search walks under.
     */
    const val MAX_FOLDERS = 2_000

    /** How many entries one scan may collect before it is "narrow it down". */
    const val MAX_ENTRIES = 20_000

    /** A folder waiting to be walked: where it is, its relative path, its depth. */
    private data class Folder(val path: String, val rel: String, val depth: Int)

    /**
     * Walks [root] and returns everything below it, relative to [root].
     *
     * [lister] is asked for one folder at a time and may throw; a folder that
     * cannot be read is passed over, because on a real server some will not be
     * readable and giving up at the first would make the mirror useless -- the
     * cost is that such a folder looks empty, which errs towards copying rather
     * than deleting.
     *
     * Links are not followed and not recorded: a mirror that chased one out of
     * the tree would copy or delete files the user never chose. [showHidden]
     * matches the rest of the app -- a scan that skipped dotfiles the browser
     * shows would quietly leave them unmirrored.
     */
    fun scan(
        root: String,
        lister: RemoteLister,
        showHidden: Boolean = false,
        cancelled: () -> Boolean = { false },
        onFolder: (Int) -> Unit = {},
    ): ScanResult {
        val entries = mutableMapOf<String, Meta>()
        var skippedLinks = 0
        var truncated = false
        var foldersRead = 0

        // Each queued folder carries how the server names it, its path relative
        // to the root (so each entry is recorded by the path the diff compares
        // on), and how deep it sits (so the recursion has a floor, the same one
        // the deep search uses).
        val queue = ArrayDeque(listOf(Folder(root, "", 0)))
        while (queue.isNotEmpty()) {
            if (cancelled()) {
                return ScanResult(entries, skippedLinks, truncated, cancelled = true, foldersRead = foldersRead)
            }
            if (foldersRead >= MAX_FOLDERS || entries.size >= MAX_ENTRIES) {
                truncated = true
                break
            }
            val (folder, relFolder, depth) = queue.removeFirst()
            val rows = runCatching { lister.list(folder) }.getOrNull() ?: continue
            foldersRead++
            onFolder(foldersRead)

            for (row in rows) {
                // Navigation, not content: recording ".." would walk the mirror
                // back up out of the folder the user chose.
                if (row.name == "." || row.name == "..") continue
                if (!showHidden && row.name.startsWith(".")) continue
                // A listing marks a link as a directory whether or not it is
                // one, so this passes over file links too -- the cheaper mistake.
                if (row.isLink) {
                    skippedLinks++
                    continue
                }
                if (entries.size >= MAX_ENTRIES) {
                    truncated = true
                    break
                }

                val rel = if (relFolder.isEmpty()) row.name else "$relFolder/${row.name}"
                entries[rel] = Meta(
                    isDir = row.isDirectory,
                    size = row.size.takeIf { it >= 0 },
                    mtimeMillis = row.time?.epochMillis,
                )
                if (row.isDirectory && depth + 1 < MAX_DEPTH) {
                    queue.addLast(Folder(FilePath.child(folder, row.name), rel, depth + 1))
                }
            }
        }

        return ScanResult(entries, skippedLinks, truncated, foldersRead = foldersRead)
    }
}
