package org.filezilla.android.files

/**
 * Path arithmetic for both panes.
 *
 * One implementation on purpose, even though one side is an FTP server and
 * the other is the phone: both speak POSIX paths, and two nearly-identical
 * copies of this would drift until walking up out of a folder behaved
 * differently depending on which pane you were in.
 *
 * Everything here is a string operation with no filesystem behind it, which
 * is what makes the cases that actually break it -- the root, a trailing
 * slash, a name with a slash in it -- testable without a device or a server.
 */
object FilePath {

    const val SEPARATOR = '/'
    const val ROOT = "/"

    /** The path of [name] inside [directory]. */
    fun child(directory: String, name: String): String =
        if (directory.isEmpty() || directory == ROOT) "$ROOT$name"
        else "${directory.trimEnd(SEPARATOR)}$SEPARATOR$name"

    /**
     * The folder containing [path], or null when [path] is already the top.
     *
     * Null rather than the path itself, so a caller cannot loop forever
     * walking up, and so a screen can tell that there is no "up" to offer.
     */
    fun parent(path: String): String? {
        val trimmed = normalize(path)
        if (trimmed == ROOT) return null
        val cut = trimmed.lastIndexOf(SEPARATOR)
        return when {
            cut <= 0 -> ROOT
            else -> trimmed.substring(0, cut)
        }
    }

    /** The last segment: what the folder is called, rather than where it is. */
    fun name(path: String): String {
        val trimmed = normalize(path)
        if (trimmed == ROOT) return ROOT
        return trimmed.substringAfterLast(SEPARATOR)
    }

    /**
     * A path in one form: absolute, no trailing slash, no empty or `.`
     * segments, and `..` resolved by removing the segment before it.
     *
     * `..` is resolved here rather than trusted to the filesystem because a
     * remote listing is not ours to trust: a server that returns an entry
     * named `..` in the middle of a path must not be able to walk a download
     * out of the folder the user chose.
     */
    fun normalize(path: String): String {
        val segments = mutableListOf<String>()
        for (segment in path.split(SEPARATOR)) {
            when (segment) {
                "", "." -> Unit
                ".." -> segments.removeLastOrNull()
                else -> segments += segment
            }
        }
        return if (segments.isEmpty()) ROOT else ROOT + segments.joinToString(SEPARATOR.toString())
    }

    /** The segments of [path], top first. Empty for the root. */
    fun segments(path: String): List<String> {
        val trimmed = normalize(path)
        if (trimmed == ROOT) return emptyList()
        return trimmed.removePrefix(ROOT).split(SEPARATOR)
    }

    /**
     * True when [path] is [ancestor] or sits under it.
     *
     * Compared segment by segment rather than with `startsWith`, which would
     * call `/storage/emulated/0/Downloads` a child of `/storage/emulated/0/Down`.
     */
    fun isWithin(path: String, ancestor: String): Boolean {
        val under = segments(path)
        val over = segments(ancestor)
        if (under.size < over.size) return false
        return over.indices.all { under[it] == over[it] }
    }
}
