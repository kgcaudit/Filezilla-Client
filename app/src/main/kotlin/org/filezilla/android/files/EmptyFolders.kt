package org.filezilla.android.files

import java.io.File

/**
 * Clearing away the folders a move has emptied.
 *
 * A move across devices is carried by the queue one file at a time, and each
 * file is removed from the phone as it arrives. The folders those files sat
 * in are not transfers, so nothing removes them -- and a folder that was
 * empty to begin with never had a file to be carried by at all. Cutting a
 * folder and pasting it on a server therefore left the whole shape of it
 * behind, which is what the user saw.
 *
 * The rule is deliberately timid: **only a folder with nothing whatever in
 * it is removed**, deepest first, so emptying a child can empty its parent.
 * A folder still holding a file is a folder whose file did not get there, and
 * it stays exactly as it is. Nothing here can delete a file, so the worst a
 * mistake can cost is an empty folder.
 */
object EmptyFolders {

    /**
     * Removes [root] and everything under it that is empty, deepest first.
     *
     * Returns the folders actually removed, which is what the caller reports
     * and what the tests read. [root] itself is included when it ends up
     * empty; a [root] that is a file, or is not there at all, is left alone
     * and gives back nothing.
     */
    fun prune(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        val removed = mutableListOf<String>()
        sweep(root, removed)
        return removed
    }

    /** True once [directory] is gone. */
    private fun sweep(directory: File, removed: MutableList<String>): Boolean {
        // listFiles is null when the directory cannot be read, which is not
        // the same as being empty -- and treating it as empty would be asking
        // to delete a folder whose contents we could not see.
        val children = directory.listFiles() ?: return false
        for (child in children) {
            // Not followed into. A symbolic link to a folder full of files
            // would look empty from the link's own side, and deleting the
            // link would be removing something the move never touched.
            if (!child.isDirectory || LocalWalk.isLink(child)) continue
            sweep(child, removed)
        }
        val empty = directory.listFiles()?.isEmpty() ?: false
        if (!empty) return false
        val gone = runCatching { directory.delete() }.getOrDefault(false)
        if (gone) removed += directory.absolutePath
        return gone
    }
}
