package org.filezilla.ftp.protocol

/**
 * Which folders have to exist before a file can be written into one.
 *
 * FTP has no "create the parents too". `STOR /a/b/c.mkv` into a server that
 * has no `/a/b` is answered `550 ... No such file or directory`, and that is
 * all the server will ever say about it -- it does not create the folder and
 * it does not say which part of the path was missing.
 *
 * Which is what uploading a folder did. The queue built one remote path per
 * file with the folders the file sat in spliced back in, and nothing ever
 * made those folders, so every file in every subfolder failed with the same
 * 550 three times over and gave up.
 *
 * Kept apart from the commands because the decision -- how far up the path
 * the server already agrees with, and what has to be made below that -- is
 * arithmetic, and arithmetic can be read and tested without a server.
 */
object RemoteDirectories {

    /**
     * The folders above [file], shallowest first.
     *
     * `/a/b/c.mkv` gives `/a` and `/a/b`. A file at the root has none, and
     * neither does a bare name: there is nothing above it to make.
     */
    fun ancestorsOf(file: String): List<String> {
        val absolute = file.startsWith("/")
        val parts = file.split("/").filter { it.isNotEmpty() }
        if (parts.size < 2) return emptyList()

        val folders = parts.dropLast(1)
        return folders.indices.map { i ->
            val joined = folders.take(i + 1).joinToString("/")
            if (absolute) "/$joined" else joined
        }
    }

    /**
     * What to create, in the order to create it.
     *
     * [exists] is asked about the deepest folder first and then about each
     * one above it, stopping at the first the server agrees with -- so an
     * upload into a folder that is already there costs one question, which
     * is the usual case and the one worth being cheap.
     *
     * Everything below that point is returned shallowest first, because
     * `MKD` will not make a folder whose own parent is missing either.
     */
    fun plan(file: String, exists: (String) -> Boolean): List<String> {
        val ancestors = ancestorsOf(file)
        for (i in ancestors.indices.reversed()) {
            if (exists(ancestors[i])) return ancestors.drop(i + 1)
        }
        return ancestors
    }
}
