package org.filezilla.android.archive

import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What came of making an archive. */
data class WriteResult(
    val entries: Int = 0,
    val cancelled: Boolean = false,
    /** Files that could not be read, in the order tried. */
    val skipped: List<String> = emptyList(),
)

/**
 * Making an archive, which is always a zip.
 *
 * ALZ and EGG are read here and neither is written, and that is on
 * purpose twice over. ESTsoft's licence for the EGG module says outright
 * that it may not be used to build a compressor. And an ALZ would be a
 * file fewer things can open than the zip it replaced -- outside Korea
 * nothing reads one, and inside it ALZip reads zip perfectly well. So
 * "compress" means zip, and the button says zip rather than offering a
 * choice that has only one good answer.
 *
 * Names are written UTF-8, which [ZipOutputStream] flags properly, so a
 * Korean name comes out of this readable by anything modern. It is the
 * *old* archives that need the CP949 guessing in [ZipArchive]; there is
 * no reason to write another one.
 */
object ArchiveWriter {

    /**
     * How far the archive has got, in bytes, and the file being added now.
     *
     * Bytes, for the same reason unpacking counts them: a folder can be one
     * huge file, and a file count sits at "0 of 1" the whole time it is
     * written. Total is 0 when nothing has a size, and the bar is left
     * indeterminate.
     */
    fun interface Progress {
        fun at(doneBytes: Long, totalBytes: Long, name: String)
    }

    /**
     * Writes [sources] into a new zip at [into].
     *
     * Names are relative to each source's own parent, so zipping two
     * files and a folder gives `one.txt`, `two.txt`, `folder/...` rather
     * than the whole path from the root of the phone.
     */
    fun zip(
        sources: List<File>,
        into: File,
        cancelled: () -> Boolean = { false },
        onProgress: Progress = Progress { _, _, _ -> },
    ): WriteResult {
        val target = into.canonicalFile
        val planned = sources.flatMap { source -> walk(source, target) }
        val skipped = mutableListOf<String>()
        val totalBytes = planned.sumOf { (file, _) -> if (file.isDirectory) 0L else file.length().coerceAtLeast(0) }
        var done = 0
        var doneBytes = 0L

        ZipOutputStream(into.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            for ((file, name) in planned) {
                if (cancelled()) {
                    return WriteResult(done, cancelled = true, skipped = skipped)
                }
                onProgress.at(doneBytes, totalBytes, name)
                var stoppedHere = false
                val written = runCatching {
                    if (file.isDirectory) {
                        zip.putNextEntry(ZipEntry("$name/").also { it.time = file.lastModified() })
                        zip.closeEntry()
                    } else {
                        zip.putNextEntry(ZipEntry(name).also { it.time = file.lastModified() })
                        file.inputStream().use { source ->
                            val end = copyCounting(source, zip, doneBytes, cancelled) {
                                onProgress.at(it, totalBytes, name)
                            }
                            if (end < 0) stoppedHere = true else doneBytes = end
                        }
                        zip.closeEntry()
                    }
                }
                when {
                    stoppedHere -> return WriteResult(done, cancelled = true, skipped = skipped)
                    written.isSuccess -> done++
                    else -> skipped += name
                }
            }
        }
        onProgress.at(totalBytes, totalBytes, "")
        return WriteResult(done, skipped = skipped)
    }

    /**
     * Every file under [source], with the name it gets inside the archive.
     *
     * [exclude] is the archive being written. Without it, zipping a folder
     * into that same folder puts the growing zip inside itself: it is a
     * file in the tree being walked, so it is read while it is written,
     * and the result is as large as the disk allows.
     */
    private fun walk(source: File, exclude: File): List<Pair<File, String>> {
        val root = source.parentFile
        fun nameOf(file: File): String =
            if (root == null) file.name
            else file.canonicalPath.removePrefix(root.canonicalPath).trimStart(File.separatorChar)
                .replace(File.separatorChar, '/')

        if (!source.exists()) return emptyList()
        if (source.isFile) {
            return if (source.canonicalFile == exclude) emptyList() else listOf(source to nameOf(source))
        }

        val out = mutableListOf<Pair<File, String>>()
        val stack = ArrayDeque(listOf(source))
        val seen = mutableSetOf<String>()
        while (stack.isNotEmpty()) {
            val folder = stack.removeFirst()
            // A symbolic link back up the tree would otherwise be walked
            // for ever; the canonical path is what tells one visit from
            // the same folder reached twice.
            if (!seen.add(runCatching { folder.canonicalPath }.getOrElse { folder.path })) continue
            val children = folder.listFiles().orEmpty()
            // An empty folder is the one case that needs a record of its
            // own: everywhere else the folder is implied by the names of
            // the files in it, and an empty one has none.
            if (children.isEmpty()) {
                out += folder to nameOf(folder)
                continue
            }
            for (child in children) {
                if (child.canonicalFile == exclude) continue
                if (child.isDirectory) stack += child else out += child to nameOf(child)
            }
        }
        return out
    }
}
