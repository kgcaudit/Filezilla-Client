package org.filezilla.ftp.io

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * A [TransferWriter] over a plain file, used for the partial-download file.
 *
 * Positioned writes go through [RandomAccessFile], which is what makes resume
 * exact: the stream starts writing at the resume offset and nothing before it
 * is touched.
 */
class FileTransferWriter(
    private val file: File,
    /**
     * Reserve space for the incoming bytes up front.
     *
     * Off by default, and deliberately so. Preallocating extends the file to
     * its final length immediately, which makes [existingSize] report space
     * that holds no data yet. That is harmless while this writer is alive
     * because [close] truncates back to what was actually written -- but if
     * the process is killed first, as Android will do to a backgrounded app,
     * the oversized file survives and the next resume would start past the
     * real end and splice a hole into the middle of the file. Turn it on only
     * where the true byte count is also recorded somewhere that survives the
     * process.
     */
    private val preallocateSpace: Boolean = false,
) : TransferWriter {

    private var raf: RandomAccessFile? = null
    private var startOffset = 0L
    private var written = 0L

    override val existingSize: Long?
        get() = if (file.isFile) file.length() else null

    override fun openAt(offset: Long): OutputStream {
        require(offset >= 0) { "negative resume offset: $offset" }
        file.parentFile?.mkdirs()
        val handle = RandomAccessFile(file, "rw")
        // Anything already past the resume point is stale: a previous attempt
        // wrote it but the offset says it is not accounted for.
        if (handle.length() > offset) handle.setLength(offset)
        handle.seek(offset)
        raf = handle
        startOffset = offset
        written = 0
        return CountingOutput(handle) { written = it }
    }

    override fun preallocate(bytes: Long) {
        if (!preallocateSpace) return
        val handle = raf ?: return
        runCatching {
            val position = handle.filePointer
            val target = position + bytes
            if (handle.length() < target) handle.setLength(target)
            handle.seek(position)
        }
    }

    override fun setModifiedTime(epochMillis: Long): Boolean = file.setLastModified(epochMillis)

    /**
     * Truncates the file back to the bytes actually received, then releases the
     * handle. This is what keeps [existingSize] honest for the next resume, so
     * the engine closes the writer even when a transfer fails partway.
     */
    override fun close() {
        val handle = raf ?: return
        runCatching {
            val valid = startOffset + written
            if (handle.length() > valid) handle.setLength(valid)
        }
        runCatching { handle.close() }
        raf = null
    }

    private class CountingOutput(
        private val handle: RandomAccessFile,
        private val onWritten: (Long) -> Unit,
    ) : OutputStream() {
        private var count = 0L

        override fun write(b: Int) {
            handle.write(b)
            count++
            onWritten(count)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            handle.write(b, off, len)
            count += len
            onWritten(count)
        }

        // The handle outlives this stream; FileTransferWriter.close() owns it.
        override fun close() = Unit
    }
}

/** A [TransferReader] over a plain file. */
class FileTransferReader(private val file: File) : TransferReader {

    private val streams = mutableListOf<InputStream>()

    override val size: Long? get() = if (file.isFile) file.length() else null

    override val modifiedTime: Long? get() = file.lastModified().takeIf { it > 0 }

    override fun openAt(offset: Long): InputStream {
        require(offset >= 0) { "negative resume offset: $offset" }
        val handle = RandomAccessFile(file, "r")
        handle.seek(offset)
        val stream = RandomAccessFileInputStream(handle)
        streams += stream
        return stream
    }

    override fun close() {
        streams.forEach { runCatching { it.close() } }
        streams.clear()
    }

    private class RandomAccessFileInputStream(private val handle: RandomAccessFile) : InputStream() {
        override fun read(): Int = handle.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = handle.read(b, off, len)
        override fun close() = handle.close()
    }
}

/** Convenience for the common case of a local file target. */
fun File.asTransferWriter(preallocateSpace: Boolean = false): TransferWriter =
    FileTransferWriter(this, preallocateSpace)

/** Convenience for the common case of a local file source. */
fun File.asTransferReader(): TransferReader = FileTransferReader(this)
