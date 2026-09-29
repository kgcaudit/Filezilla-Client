package org.filezilla.android.archive

import java.io.File

/**
 * Joining a split archive back into one file.
 *
 * The counterpart to [ArchiveWriter]'s split: parts named `name.001`,
 * `name.002`, ... laid end to end are the original file again -- the split
 * 7-Zip, Bandizip and ALZip make and read. This finds the whole run from its
 * first part and writes them back into one, which is what a file sent in
 * pieces needs on the far side.
 */
object SplitParts {

    // A three-digit tail is what a part wears: "photos.zip.001". The stem in
    // front is the name the parts rebuild to.
    private val PART = Regex("""^(.*)\.(\d{3})$""")

    /** Whether [name] is the first part of a split -- something.001. */
    fun isFirstPart(name: String): Boolean =
        PART.matchEntire(name)?.groupValues?.get(2) == "001"

    /** The name a first part rebuilds to: `photos.zip.001` -> `photos.zip`. */
    fun baseNameOf(firstPartName: String): String =
        PART.matchEntire(firstPartName)?.groupValues?.get(1) ?: firstPartName

    /**
     * The consecutive parts starting at [first]'s `.001`, in order, stopping at
     * the first missing number. Just [first] when it stands alone -- a gap is
     * where the run ends, so a `.001` and a stray `.003` join only the `.001`.
     */
    fun partsFor(first: File): List<File> {
        val base = baseNameOf(first.name)
        val dir = first.parentFile ?: return listOf(first)
        val out = mutableListOf(first)
        var n = 2
        while (true) {
            val next = File(dir, "%s.%03d".format(base, n))
            if (!next.isFile) break
            out += next
            n++
        }
        return out
    }

    /** How far the join has got, in bytes. */
    fun interface Progress {
        fun at(doneBytes: Long, totalBytes: Long)
    }

    /**
     * Concatenates [parts] into [into]. Returns the bytes written, or -1 if a
     * stop was asked for partway -- in which case [into] holds only part of the
     * file and the caller is expected to remove it.
     */
    fun join(
        parts: List<File>,
        into: File,
        cancelled: () -> Boolean = { false },
        onProgress: Progress = Progress { _, _ -> },
    ): Long {
        val total = parts.sumOf { it.length().coerceAtLeast(0) }
        var done = 0L
        into.outputStream().buffered().use { out ->
            val buffer = ByteArray(64 * 1024)
            for (part in parts) {
                part.inputStream().use { input ->
                    while (true) {
                        if (cancelled()) return -1
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        onProgress.at(done, total)
                    }
                }
            }
        }
        return done
    }
}
