package org.filezilla.android.archive

import java.io.File
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What came of making an archive. */
data class WriteResult(
    val entries: Int = 0,
    val cancelled: Boolean = false,
    /** Files that could not be read, in the order tried. */
    val skipped: List<String> = emptyList(),
    /**
     * When the archive was split, the parts written, in order; empty for a
     * single-file archive. Kept so the caller can name them to the user and
     * clean them all up if the write was stopped or failed.
     */
    val parts: List<File> = emptyList(),
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
     * Names are relative to each source's own parent, so zipping two files and
     * a folder gives `one.txt`, `two.txt`, `folder/...` rather than the whole
     * path from the root of the phone.
     *
     * [flatten] drops that outer folder: a folder's contents go to the archive
     * root instead of under the folder's own name -- the difference between an
     * archive that opens straight onto its pages and one that opens onto a
     * single folder. [wrap], when set, does the opposite, putting everything
     * under one folder of that name; it is how a loose handful of files is
     * given a folder of their own. The two are not combined.
     *
     * [partBytes], when above zero, cuts the archive into parts of at most that
     * many bytes each, named `into.001`, `into.002`, and so on -- the split any
     * of 7-Zip, Bandizip or ALZip makes and reads, where the parts join back
     * into one zip simply by being laid end to end (`cat`, `copy /b`, or opening
     * the `.001`). It is there for a file too big to send whole over a channel
     * that caps each upload. The zip itself is unchanged; it is only carried in
     * pieces. When it is set, [into] itself is never written -- only its parts
     * are -- and the parts written are returned in the result.
     */
    fun zip(
        sources: List<File>,
        into: File,
        flatten: Boolean = false,
        wrap: String? = null,
        partBytes: Long = 0,
        cancelled: () -> Boolean = { false },
        onProgress: Progress = Progress { _, _, _ -> },
    ): WriteResult {
        val target = into.canonicalFile
        val planned = sources.flatMap { source -> walk(source, target, flatten) }
            // A flattened source that is itself the empty folder names nothing;
            // there is no entry to write for it, so it is dropped.
            .filter { (_, name) -> name.isNotEmpty() }
            .let { entries ->
                if (wrap.isNullOrEmpty()) entries else entries.map { (file, name) -> file to "$wrap/$name" }
            }
        val skipped = mutableListOf<String>()
        val totalBytes = planned.sumOf { (file, _) -> if (file.isDirectory) 0L else file.length().coerceAtLeast(0) }
        var done = 0
        var doneBytes = 0L

        // A single file, or a splitter that rolls to the next part on the way
        // through. The split is of the byte stream, not the zip's structure:
        // ZipOutputStream only ever writes forward, so the pieces concatenate
        // back to exactly the bytes a single file would have held.
        val split = if (partBytes > 0) SplittingOutputStream(into, partBytes) else null
        val sink = split ?: into.outputStream().buffered()
        fun partsOf() = split?.parts.orEmpty()

        ZipOutputStream(sink).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            for ((file, name) in planned) {
                if (cancelled()) {
                    return WriteResult(done, cancelled = true, skipped = skipped, parts = partsOf())
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
                    stoppedHere -> return WriteResult(done, cancelled = true, skipped = skipped, parts = partsOf())
                    written.isSuccess -> done++
                    else -> skipped += name
                }
            }
        }
        onProgress.at(totalBytes, totalBytes, "")
        return WriteResult(done, skipped = skipped, parts = partsOf())
    }

    /**
     * Every file under [source], with the name it gets inside the archive.
     *
     * [exclude] is the archive being written. Without it, zipping a folder
     * into that same folder puts the growing zip inside itself: it is a
     * file in the tree being walked, so it is read while it is written,
     * and the result is as large as the disk allows.
     */
    private fun walk(source: File, exclude: File, flatten: Boolean = false): List<Pair<File, String>> {
        // Relative to the parent, the folder keeps its own name in the archive;
        // relative to the folder itself, its contents land at the root. A file
        // is always just its own name -- there is no folder of its to drop.
        val root = if (flatten && source.isDirectory) source else source.parentFile
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

    /**
     * A stream that fills one part file, then the next, cutting whatever is
     * written across the boundary rather than at it -- a single `write` that
     * would run past the end of a part is split, the rest going to the part
     * after. So every part but the last is exactly [partBytes] long, which is
     * what a volume split means and what lets the parts be joined back with no
     * knowledge of where the cuts fell.
     *
     * Parts are named `base.001`, `base.002`, ... : three digits, so they sort
     * in order past nine and past ninety-nine, and the numbering the split
     * tools use. The base file itself is never opened; only its parts are.
     */
    private class SplittingOutputStream(
        private val base: File,
        private val partBytes: Long,
    ) : OutputStream() {
        val parts = mutableListOf<File>()
        private var current: OutputStream? = null
        private var inThisPart = 0L

        private fun rollOver() {
            current?.flush()
            current?.close()
            val part = File(base.parentFile, "%s.%03d".format(base.name, parts.size + 1))
            parts += part
            current = part.outputStream().buffered()
            inThisPart = 0L
        }

        override fun write(b: Int) {
            if (current == null || inThisPart >= partBytes) rollOver()
            current!!.write(b)
            inThisPart++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var pos = off
            var left = len
            while (left > 0) {
                if (current == null || inThisPart >= partBytes) rollOver()
                val room = (partBytes - inThisPart).coerceAtMost(left.toLong()).toInt()
                current!!.write(b, pos, room)
                inThisPart += room
                pos += room
                left -= room
            }
        }

        override fun flush() {
            current?.flush()
        }

        override fun close() {
            current?.flush()
            current?.close()
            current = null
        }
    }
}
