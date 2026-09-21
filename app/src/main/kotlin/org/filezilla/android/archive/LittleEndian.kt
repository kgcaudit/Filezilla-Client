package org.filezilla.android.archive

/**
 * Little-endian reads out of a byte array.
 *
 * Every one of these formats stores its numbers little-endian, and each
 * reader was carrying its own copy of these three lines -- zip had two.
 * They live here once so a reader is the format's own logic and not the
 * byte-shuffling under it.
 */

internal fun ByteArray.intAt(at: Int): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8) or
        ((this[at + 2].toInt() and 0xFF) shl 16) or ((this[at + 3].toInt() and 0xFF) shl 24)

internal fun ByteArray.shortAt(at: Int): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.longAt(at: Int): Long {
    var value = 0L
    for (i in 7 downTo 0) value = (value shl 8) or (this[at + i].toLong() and 0xFF)
    return value
}

/**
 * The first [count] bytes of [file], or fewer when it is shorter.
 *
 * The one thing every reader does before anything else -- read the magic
 * at the front -- written once. Returns a short array rather than throwing
 * on a tiny or unreadable file, so a signature check is a plain
 * [ByteArray.contentEquals] or [intAt] against a known length.
 */
internal fun firstBytes(file: java.io.File, count: Int): ByteArray = runCatching {
    file.inputStream().use { source ->
        val head = ByteArray(count)
        val got = source.read(head)
        if (got == count) head else head.copyOf(maxOf(got, 0))
    }
}.getOrDefault(ByteArray(0))

/**
 * A raw-deflate stream, read through a 64 KB buffer.
 *
 * zip, alz and egg all wrap their deflated entries this way. The buffer
 * size is the fix that made unpacking fast on a phone -- the default is
 * 512 bytes, one storage read per half-kilobyte -- and it lives here so
 * the three readers cannot drift apart on it.
 */
internal fun inflating(source: java.io.InputStream): java.io.InputStream =
    java.util.zip.InflaterInputStream(source, java.util.zip.Inflater(true), 64 * 1024)

/**
 * Copies [source] into [sink] in 64 KB steps, counting bytes and stopping.
 *
 * Returns the running total once the source is drained, or -1 when
 * [cancelled] ended it part way -- so a four-gigabyte entry is both
 * watchable and stoppable. Progress is pushed at most once a megabyte:
 * a file that size is sixty thousand buffers, and telling the screen
 * that often is what drops frames. Both unpacking and packing copy this
 * way, so the buffer and the rate are set in one place.
 */
internal inline fun copyCounting(
    source: java.io.InputStream,
    sink: java.io.OutputStream,
    startedAt: Long,
    cancelled: () -> Boolean,
    onBytes: (Long) -> Unit,
): Long {
    val buffer = ByteArray(64 * 1024)
    var done = startedAt
    var sinceReport = 0L
    while (true) {
        if (cancelled()) return -1
        val n = source.read(buffer)
        if (n < 0) break
        sink.write(buffer, 0, n)
        done += n
        sinceReport += n
        if (sinceReport >= 1_000_000L) { onBytes(done); sinceReport = 0L }
    }
    return done
}
