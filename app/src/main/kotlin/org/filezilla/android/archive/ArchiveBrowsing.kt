package org.filezilla.android.archive

/** One row of the list shown inside an archive. */
data class ArchiveRow(
    val path: String,
    val isDirectory: Boolean,
    val size: Long = -1,
    val modifiedMillis: Long? = null,
    val encrypted: Boolean = false,
    val unreadable: ArchiveEntry.Unreadable? = null,
    /** Files under a folder, counted through the whole tree. */
    val count: Int = 0,
) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
}

/** What a selection adds up to, for the line above the buttons. */
data class ArchivePicked(val files: Int, val bytes: Long, val locked: Int)

/**
 * Walking about inside an archive without unpacking it.
 *
 * The folders here are mostly not in the archive. ALZip records none at
 * all -- a real archive of one folder full of files contains only the
 * files, each with the folder in its name -- and a zip records them only
 * when the tool that wrote it felt like it. So the tree is derived from
 * the names, every time, and an archive that does record its folders
 * looks exactly the same as one that does not.
 *
 * Separate from the Compose because this is where being wrong shows: a
 * folder that lists nothing, a "select all" that misses the files inside
 * folders, a size that counts a folder's entries twice.
 */
object ArchiveBrowsing {

    /** Every folder in [entries], recorded or implied by a name. */
    fun foldersIn(entries: List<ArchiveEntry>): Set<String> =
        ArchivePaths.foldersIn(entries).toSet()

    /**
     * The rows directly inside [at], folders first.
     *
     * [at] is a folder path without a trailing slash; the empty string is
     * the top of the archive.
     */
    fun rowsIn(entries: List<ArchiveEntry>, at: String = ""): List<ArchiveRow> {
        val folders = foldersIn(entries)
        val here = folders
            .filter { it.substringBeforeLast('/', "") == at && it != at }
            .map { folder ->
                val under = entries.count { !it.isDirectory && isUnder(it.path, folder) }
                ArchiveRow(path = "$folder/", isDirectory = true, count = under)
            }
        val files = entries
            .filterNot { it.isDirectory }
            .filter { it.parent == at }
            .map { entry ->
                ArchiveRow(
                    path = entry.path,
                    isDirectory = false,
                    size = entry.size,
                    modifiedMillis = entry.modifiedMillis,
                    encrypted = entry.encrypted,
                    unreadable = entry.unreadable,
                )
            }
        val byName = org.filezilla.android.files.NaturalOrder.by<ArchiveRow> { it.name }
        return here.sortedWith(byName) + files.sortedWith(byName)
    }

    /**
     * The paths a selection really covers.
     *
     * A picked folder brings everything under it, because that is what
     * picking a folder means in the rest of this app -- and because a
     * "extract selected" that unpacked an empty folder and none of its
     * contents would be the same bug as the one ALZip's missing directory
     * records already caused once.
     */
    fun expand(entries: List<ArchiveEntry>, picks: Set<String>): Set<String> {
        if (picks.isEmpty()) return emptySet()
        val folders = picks.filter { it.endsWith("/") }.map { it.trimEnd('/') }
        val out = LinkedHashSet<String>()
        for (entry in entries) {
            if (entry.path in picks || folders.any { isUnder(entry.path, it) }) out += entry.path
        }
        return out
    }

    /** What [picks] amounts to: files, bytes, and how many need a password. */
    fun picked(entries: List<ArchiveEntry>, picks: Set<String>): ArchivePicked {
        val covered = expand(entries, picks)
        val files = entries.filter { !it.isDirectory && it.path in covered }
        return ArchivePicked(
            files = files.size,
            bytes = files.sumOf { it.size.coerceAtLeast(0) },
            locked = files.count { it.encrypted },
        )
    }

    /** Every row of [at], for the "select all" that acts on what is shown. */
    fun pathsIn(entries: List<ArchiveEntry>, at: String = ""): Set<String> =
        rowsIn(entries, at).map { it.path }.toSet()

    /** The folder above [at], or null at the top. */
    fun upFrom(at: String): String? =
        if (at.isEmpty()) null else at.substringBeforeLast('/', "")

    private fun isUnder(path: String, folder: String): Boolean =
        folder.isEmpty() || path.startsWith("$folder/")
}
