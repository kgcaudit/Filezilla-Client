package org.filezilla.android.files

import java.io.File

/** One file an upload will send, and the folders to recreate for it. */
data class LocalFileToSend(
    val path: String,
    val name: String,
    val size: Long?,
    /** Folders to make on the server, empty for a file picked directly. */
    val subPath: List<String>,
)

/**
 * Expands what was picked on the phone into the files to send.
 *
 * The mirror of [org.filezilla.android.ui.FolderDownload], and separate from
 * it for the same reason: picking a folder has to mean sending what is in it,
 * and the shape it had has to survive the trip.
 */
object LocalWalk {

    /** How deep to go, so a link loop cannot walk for ever. */
    const val MAX_DEPTH = 24

    /** How many files one selection may expand to before the walk gives up. */
    const val MAX_FILES = 5_000

    fun filesUnder(path: String): List<LocalFileToSend> {
        val root = File(FilePath.normalize(path))
        val found = mutableListOf<LocalFileToSend>()

        fun walk(file: File, subPath: List<String>) {
            if (found.size >= MAX_FILES || subPath.size >= MAX_DEPTH) return
            // Links are passed over rather than followed: one pointing at its
            // own parent would walk for ever, and the download planner skips
            // them for exactly the same reason.
            if (isLink(file)) return
            if (file.isDirectory) {
                for (child in file.listFiles().orEmpty()) walk(child, subPath + file.name)
                return
            }
            found += LocalFileToSend(
                path = file.absolutePath,
                name = file.name,
                size = file.length().takeIf { it >= 0 },
                subPath = subPath,
            )
        }

        // The picked thing's own name is not part of its subPath when it is a
        // file, and is when it is a folder -- which is what makes sending
        // "Vision" produce a "Vision" on the server.
        if (root.isDirectory) {
            for (child in root.listFiles().orEmpty()) walk(child, listOf(root.name))
        } else if (root.isFile) {
            found += LocalFileToSend(root.absolutePath, root.name, root.length(), emptyList())
        }
        return found
    }

    /**
     * Every folder under [path], including the picked folder itself and
     * including the ones with nothing in them.
     *
     * Separate from [filesUnder] because a folder is not a file, and an
     * upload built only out of files loses the folders that hold none.
     * Copying an empty folder to a server queued nothing at all, so nothing
     * happened and nothing said why -- the folder simply was not there
     * afterwards.
     *
     * Shallowest first, since a folder cannot be made before its parent.
     */
    fun foldersUnder(path: String): List<List<String>> {
        val root = File(FilePath.normalize(path))
        if (!root.isDirectory || isLink(root)) return emptyList()

        val found = mutableListOf<List<String>>()

        fun walk(file: File, subPath: List<String>) {
            if (found.size >= MAX_FILES || subPath.size >= MAX_DEPTH) return
            if (isLink(file) || !file.isDirectory) return
            val here = subPath + file.name
            found += here
            for (child in file.listFiles().orEmpty()) walk(child, here)
        }

        walk(root, emptyList())
        return found
    }

    /** Shared with EmptyFolders, which must not follow one either. */
    internal fun isLink(file: File): Boolean =
        runCatching { file.canonicalPath != file.absolutePath }.getOrDefault(false)
}
