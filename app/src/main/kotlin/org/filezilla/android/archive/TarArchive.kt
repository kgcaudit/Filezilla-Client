package org.filezilla.android.archive

import java.io.File
import java.io.InputStream

/**
 * A tar archive, read entry by entry.
 *
 * Tar is not compressed: it is headers and file bytes laid end to end in
 * 512-byte blocks. So there is no decoder here, only the walk through those
 * blocks -- which is why it can be pure Kotlin where 7z and rar cannot. A
 * `.cbt` comic is a tar with the extension changed to open it in a reader
 * rather than an unpacker, and this reads both.
 *
 * USTAR and the GNU long-name extension, which is what actual tars use. The
 * ancient pre-USTAR variant with no magic is not accepted, so a file is only
 * treated as a tar when it says it is one -- a comic archive that is really
 * a zip is still opened as the zip it is.
 */
class TarArchive private constructor(
    private val file: File,
    private val located: List<Located>,
) : Archive {

    /** An entry and where in the file its bytes begin. */
    private class Located(val entry: ArchiveEntry, val offset: Long)

    override val entries: List<ArchiveEntry> get() = located.map { it.entry }

    override fun open(entry: ArchiveEntry, password: CharArray?): InputStream {
        if (entry.isDirectory) throw NotAnArchive("${entry.path} is a folder")
        val found = located.firstOrNull { it.entry.path == entry.path }
            ?: throw NotAnArchive("${entry.path} is not in ${file.name}")
        val stream = file.inputStream()
        var toSkip = found.offset
        while (toSkip > 0) {
            val skipped = stream.skip(toSkip)
            if (skipped <= 0) { stream.close(); throw NotAnArchive("truncated: ${entry.path}") }
            toSkip -= skipped
        }
        // Bounded to this entry's bytes, so the reader stops at its end
        // rather than running on into the next header.
        return object : InputStream() {
            private var left = found.entry.size.coerceAtLeast(0)
            override fun read(): Int {
                if (left <= 0) return -1
                val b = stream.read()
                if (b >= 0) left--
                return b
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = stream.read(b, off, minOf(len.toLong(), left).toInt())
                if (n > 0) left -= n
                return n
            }
            override fun close() = stream.close()
        }
    }

    override fun close() = Unit

    companion object {
        private const val BLOCK = 512

        /** `ustar` at offset 257, the magic every USTAR (and GNU) tar carries. */
        fun looksLikeTar(file: File): Boolean {
            if (file.length() < 512) return false
            return runCatching {
                file.inputStream().use { stream ->
                    val head = ByteArray(BLOCK)
                    if (readFully(stream, head) < BLOCK) return false
                    head.copyOfRange(257, 262).contentEquals("ustar".toByteArray(Charsets.US_ASCII))
                }
            }.getOrDefault(false)
        }

        fun open(file: File): TarArchive {
            val located = mutableListOf<Located>()
            file.inputStream().use { stream ->
                var position = 0L
                val header = ByteArray(BLOCK)
                var pendingLongName: String? = null
                while (true) {
                    if (readFully(stream, header) < BLOCK) break
                    position += BLOCK
                    // Two zero blocks end the archive; a single all-zero block
                    // is enough to stop on.
                    if (header.all { it == 0.toByte() }) break

                    val size = octal(header, 124, 12)
                    val type = header[156].toInt().toChar()
                    val dataBlocks = ((size + BLOCK - 1) / BLOCK) * BLOCK

                    when (type) {
                        // GNU long name: this block's data is the real name of
                        // the entry that follows, too long for the 100-byte field.
                        'L' -> {
                            pendingLongName = readString(stream, size.toInt()).trimEnd('\u0000')
                            skip(stream, dataBlocks - size)
                            position += dataBlocks
                        }
                        // pax extended headers and the like: not needed to list
                        // or read the files, so step over them.
                        'x', 'g' -> {
                            skip(stream, dataBlocks)
                            position += dataBlocks
                        }
                        else -> {
                            val rawName = pendingLongName ?: name(header)
                            pendingLongName = null
                            val isDir = type == '5' || rawName.endsWith("/")
                            val clean = rawName.trimEnd('/')
                            if (clean.isNotEmpty()) {
                                located += Located(
                                    ArchiveEntry(
                                        path = if (isDir) "$clean/" else clean,
                                        size = if (isDir) -1 else size,
                                        isDirectory = isDir,
                                        modifiedMillis = octal(header, 136, 12).takeIf { it > 0 }?.times(1000),
                                    ),
                                    offset = position,
                                )
                            }
                            skip(stream, dataBlocks)
                            position += dataBlocks
                        }
                    }
                }
            }
            return TarArchive(file, located)
        }

        /** The name, USTAR prefix (345..499) joined ahead of the 100-byte field. */
        private fun name(header: ByteArray): String {
            val main = cString(header, 0, 100)
            val prefix = cString(header, 345, 155)
            return if (prefix.isEmpty()) main else "$prefix/$main"
        }

        private fun cString(bytes: ByteArray, at: Int, len: Int): String {
            var end = at
            val limit = at + len
            while (end < limit && bytes[end] != 0.toByte()) end++
            return String(bytes, at, end - at, Charsets.UTF_8)
        }

        /** A tar number: octal ASCII, space- or NUL-padded on either side. */
        private fun octal(bytes: ByteArray, at: Int, len: Int): Long {
            val limit = at + len
            var i = at
            while (i < limit && (bytes[i] == ' '.code.toByte() || bytes[i] == 0.toByte())) i++
            var value = 0L
            while (i < limit) {
                val c = bytes[i].toInt()
                if (c < '0'.code || c > '7'.code) break
                value = value * 8 + (c - '0'.code)
                i++
            }
            return value
        }

        private fun readFully(stream: InputStream, into: ByteArray): Int {
            var read = 0
            while (read < into.size) {
                val n = stream.read(into, read, into.size - read)
                if (n < 0) break
                read += n
            }
            return read
        }

        private fun readString(stream: InputStream, len: Int): String {
            val buf = ByteArray(len)
            val n = readFully(stream, buf)
            return String(buf, 0, n.coerceAtLeast(0), Charsets.UTF_8)
        }

        private fun skip(stream: InputStream, count: Long) {
            var left = count
            val scratch = ByteArray(8192)
            while (left > 0) {
                val n = stream.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
                if (n < 0) break
                left -= n
            }
        }
    }
}
