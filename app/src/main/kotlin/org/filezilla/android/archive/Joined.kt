package org.filezilla.android.archive

import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Several files read as though they were one.
 *
 * An ALZ can be split -- `holiday.alz`, `holiday.a00`, `holiday.a01` --
 * and an entry is free to begin in one part and finish in the next. Every
 * read would otherwise have to know that, so it is dealt with once, here,
 * and everything above reads a single run of bytes at an offset.
 */
internal class Joined(
    private val parts: List<RandomAccessFile>,
    private val start: Long,
    private val length: Long,
) : InputStream() {

    private var read = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
    }

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        if (read >= length) return -1
        val want = minOf(count.toLong(), length - read).toInt()
        val got = readAt(parts, start + read, buffer, offset, want)
        if (got <= 0) return -1
        read += got
        return got
    }

    override fun available(): Int = (length - read).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Walks through the archive's bytes once, in order, across the parts. */
    class Reader(private val parts: List<RandomAccessFile>) {

        var position = 0L
            private set

        private val total = parts.sumOf { it.length() }

        fun skip(bytes: Long) {
            position += bytes
        }

        fun skip(bytes: Int) = skip(bytes.toLong())

        fun readBytes(count: Int): ByteArray {
            val out = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val got = readAt(parts, position + filled, out, filled, count - filled)
                if (got <= 0) throw NotAnArchive("the file ends in the middle of a record")
                filled += got
            }
            position += count
            return out
        }

        fun readByte(): Int = readBytes(1)[0].toInt() and 0xFF

        fun readShort(): Int = readBytes(2).let {
            (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8)
        }

        fun readInt(): Int = readBytes(4).let {
            (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) or
                ((it[2].toInt() and 0xFF) shl 16) or ((it[3].toInt() and 0xFF) shl 24)
        }

        /** The next signature, or null at the end. A truncated tail is an end. */
        fun readIntOrNull(): Int? = if (position + 4 > total) null else readInt()

        /**
         * A size field, whose width the descriptor byte decided.
         *
         * One, two, four or eight bytes, little endian. Read as a Long
         * throughout: an eight-byte size is a real size in a format that
         * has always allowed archives over four gigabytes.
         */
        fun readSize(width: Int): Long {
            val bytes = readBytes(width)
            var value = 0L
            for (i in bytes.indices.reversed()) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
            return value
        }
    }
}

/**
 * Random access across a set of volumes, as though they were one file.
 *
 * A split zip is `name.z01`, `name.z02`, ... `name.zip`, or `name.zip.001`,
 * `.002`, ...; its central directory addresses bytes by a disk number and an
 * offset within that disk. This turns that pair into one absolute position
 * over the concatenation and reads there, so the reader above works in a
 * single run of bytes and never has to know how many parts there are.
 */
internal class Volumes(val parts: List<RandomAccessFile>) {

    // The absolute position each part begins at: prefix[i] is the sum of the
    // lengths before part i, so a disk number indexes straight into it.
    private val prefix: LongArray = LongArray(parts.size + 1).also {
        for (i in parts.indices) it[i + 1] = it[i] + parts[i].length()
    }

    val length: Long get() = prefix.last()

    /** The absolute position of [offset] within disk [disk]. */
    fun absolute(disk: Int, offset: Long): Long =
        (if (disk in parts.indices) prefix[disk] else 0L) + offset

    /** Fills [into] from [offset], across a part edge if need be. */
    fun readFully(offset: Long, into: ByteArray) {
        var filled = 0
        while (filled < into.size) {
            val got = readAt(parts, offset + filled, into, filled, into.size - filled)
            if (got <= 0) throw NotAnArchive("the archive ends in the middle of a record")
            filled += got
        }
    }

    /** One read from [offset]; may be short at a part's edge, or -1 at the end. */
    fun read(offset: Long, into: ByteArray): Int = readAt(parts, offset, into, 0, into.size)

    fun close() = parts.forEach { runCatching { it.close() } }
}

/**
 * Reads at an absolute offset across the parts, as though joined.
 *
 * Returns what one read gave, which may be short at a part's edge; the
 * callers loop, because a short read is normal here rather than an error.
 */
private fun readAt(
    parts: List<RandomAccessFile>,
    offset: Long,
    buffer: ByteArray,
    at: Int,
    count: Int,
): Int {
    var remaining = offset
    for (part in parts) {
        val size = part.length()
        if (remaining < size) {
            part.seek(remaining)
            return part.read(buffer, at, minOf(count.toLong(), size - remaining).toInt())
        }
        remaining -= size
    }
    return -1
}
