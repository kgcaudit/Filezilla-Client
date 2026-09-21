package org.filezilla.android.archive

import java.io.File

/**
 * Where an entry is allowed to land when it is unpacked.
 *
 * An archive's folder structure is nothing but the paths in its entry
 * names -- ALZip writes no directory records at all, so `말똥가리/알집.txt`
 * is the only evidence the folder exists. Unpacking therefore means
 * making the folders a name asks for, and a name is not ours: it came out
 * of a file somebody else wrote.
 *
 * A name of `../../../../data/data/other.app/databases/x` would, followed
 * literally, put a chosen file inside another app. That is an old trick
 * and it works on any program that joins a downloaded name to a local
 * folder without looking. So every destination goes through here and
 * anything that would land outside is refused -- not sanitised into
 * something else, because a quietly renamed file is a file the user
 * cannot find.
 */
object ArchivePaths {

    /**
     * The file [path] names inside [root], or null when it escapes.
     *
     * Compared after resolving, so `a/../../b` is caught by where it ends
     * up rather than by what it looks like -- a check on the spelling is a
     * check somebody can spell their way around.
     */
    fun resolve(root: File, path: String): File? {
        if (path.isBlank()) return null
        val cleaned = path.replace('\\', '/').trimEnd('/')
        if (cleaned.isEmpty()) return null
        // An absolute name ignores the folder it was asked to go in.
        if (cleaned.startsWith("/")) return null
        // A Windows drive, which is absolute somewhere else.
        if (cleaned.length >= 2 && cleaned[1] == ':') return null

        val inside = File(root, cleaned).canonicalFile
        val base = root.canonicalFile
        return if (inside == base || inside.path.startsWith(base.path + File.separator)) inside else null
    }

    /**
     * The folders [entries] imply, deepest last, so they can be made in
     * order.
     *
     * Derived from the paths rather than read from the archive, because
     * ALZip does not record them -- and a reader that waited for a
     * directory entry would unpack a folder's contents into nothing.
     */
    fun foldersIn(entries: List<ArchiveEntry>): List<String> {
        val folders = sortedSetOf<String>()
        for (entry in entries) {
            val path = if (entry.isDirectory) entry.path.trimEnd('/') else entry.parent
            var at = path
            while (at.isNotEmpty()) {
                folders += at
                at = at.substringBeforeLast('/', "")
            }
        }
        return folders.sortedBy { it.count { c -> c == '/' } }
    }
}
