package org.filezilla.android.ui

import org.filezilla.ftp.listing.DirectoryEntry

/** One file a download will fetch, and the folders to recreate for it. */
data class PlannedDownload(
    val remotePath: String,
    val size: Long?,
    /** Folders to make under the destination the user chose. Empty for a loose file. */
    val subPath: List<String>,
    /** When the server says it changed, for telling two same-named files apart. */
    val modifiedMillis: Long? = null,
) {
    val displayName: String get() = remotePath.substringAfterLast('/')
}

/** What a selection expands to, and what had to be left out of it. */
data class DownloadPlan(
    val files: List<PlannedDownload> = emptyList(),
    /** Symbolic links passed over; see [FolderDownload.plan]. */
    val skippedLinks: Int = 0,
    /** True when the walk stopped early because the selection was enormous. */
    val truncated: Boolean = false,
    /** True when the walk was called off, so the plan is not the whole tree. */
    val cancelled: Boolean = false,
    /** Folders opened. What the walk cost, and what it has to show for itself. */
    val foldersRead: Int = 0,
)

/** Lists one remote directory. Narrow on purpose, so the walk can be tested. */
fun interface RemoteLister {
    fun list(path: String): List<DirectoryEntry>
}

/**
 * Turns what the user ticked into the list of files to fetch.
 *
 * Selecting a folder has to mean downloading what is in it -- the alternative,
 * which is what this app did before, is that the download button does nothing
 * at all for a folder and says nothing about why.
 */
object FolderDownload {

    /**
     * How deep to walk. A remote tree is not ours to trust: a server can
     * present one far deeper than anything a person meant to download, and a
     * walk with no floor under it would recurse until it ran out of stack.
     */
    const val MAX_DEPTH = 24

    /** How many files one selection may expand to before the walk gives up. */
    const val MAX_FILES = 5_000

    /**
     * Whether planning [picks] has to ask the server anything.
     *
     * Only a folder does: a file already knows its own name and size from the
     * listing that showed it. Worth asking, because the answer decides
     * whether a single-file download pays for a login before it starts.
     */
    fun needsRemoteWalk(picks: List<DirectoryEntry>): Boolean =
        picks.any { it.isDirectory && !it.isLink }

    /**
     * Expands [picks] -- rows of [directory] -- into the files to download.
     *
     * Symbolic links are left out rather than followed. A link can point at
     * its own parent, and following one would walk the same folders forever;
     * the count comes back in [DownloadPlan.skippedLinks] so the user is told
     * rather than quietly given less than they asked for. A listing marks a
     * link as a directory whether or not it is one, so this skips file links
     * too -- passing over a file is the cheaper mistake.
     */
    fun plan(
        lister: RemoteLister,
        directory: String,
        picks: List<DirectoryEntry>,
        /** Asked between folders, so a walk called off stops within one listing. */
        cancelled: () -> Boolean = { false },
        /** Told after each folder, so the count can be shown as it climbs. */
        onFolder: (Int) -> Unit = {},
    ): DownloadPlan {
        val files = mutableListOf<PlannedDownload>()
        var skippedLinks = 0
        var truncated = false
        var stopped = false
        var foldersRead = 0

        fun take(path: String, entry: DirectoryEntry, subPath: List<String>) {
            files += PlannedDownload(
                remotePath = remotePathOf(path, entry.name),
                size = entry.size.takeIf { it >= 0 },
                subPath = subPath,
                modifiedMillis = entry.time?.epochMillis,
            )
        }

        fun walk(path: String, subPath: List<String>) {
            if (subPath.size >= MAX_DEPTH) {
                truncated = true
                return
            }
            if (stopped || cancelled()) {
                stopped = true
                return
            }
            val listing = lister.list(path)
            foldersRead++
            onFolder(foldersRead)
            for (entry in listing) {
                if (stopped) return
                if (files.size >= MAX_FILES) {
                    truncated = true
                    return
                }
                when {
                    entry.isLink -> skippedLinks++
                    entry.isDirectory -> walk(remotePathOf(path, entry.name), subPath + entry.name)
                    else -> take(path, entry, subPath)
                }
            }
        }

        for (pick in picks) {
            if (files.size >= MAX_FILES) {
                truncated = true
                break
            }
            if (stopped || cancelled()) {
                stopped = true
                break
            }
            when {
                pick.isLink -> skippedLinks++
                // The picked folder is recreated by name, so downloading
                // "Vision" gives the user a "Vision" folder rather than its
                // contents strewn across the one they chose.
                pick.isDirectory -> walk(remotePathOf(directory, pick.name), listOf(pick.name))
                else -> take(directory, pick, emptyList())
            }
        }

        return DownloadPlan(files, skippedLinks, truncated, stopped, foldersRead)
    }
}
