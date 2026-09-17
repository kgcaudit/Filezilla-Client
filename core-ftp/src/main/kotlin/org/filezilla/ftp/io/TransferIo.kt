package org.filezilla.ftp.io

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * Where a download is written.
 *
 * This is the seam that keeps the engine free of Android APIs, mirroring
 * `fz::writer_factory_holder` in FileZilla. The whole of resume rests on
 * [openAt] being able to start writing at an arbitrary offset, so an
 * implementation that cannot do that must not be used for resumable
 * transfers -- on Android that means writing the partial file to app-private
 * storage with a `RandomAccessFile` and moving it to the user's chosen
 * location once complete, rather than writing through a Storage Access
 * Framework document whose provider may not support positioned writes.
 */
interface TransferWriter : Closeable {

    /** Size of the partial file already on disk, or null when there is none. */
    val existingSize: Long?

    /** Opens the target for writing, positioned at [offset]. */
    fun openAt(offset: Long): OutputStream

    /**
     * Hints that [bytes] more will be written, so the filesystem can reserve
     * them. Port of `OPTION_PREALLOCATE_SPACE` (`filetransfer.cpp:129-135`).
     * Best effort: failure to preallocate is not fatal.
     */
    fun preallocate(bytes: Long) {}

    /** Applies the server's timestamp once the transfer completed. */
    fun setModifiedTime(epochMillis: Long): Boolean = false
}

/** Where an upload is read from, mirroring `fz::reader_factory_holder`. */
interface TransferReader : Closeable {

    /** Total size of the source, or null when it cannot be determined. */
    val size: Long?

    /** Source modification time, used for `MFMT` when preserving timestamps. */
    val modifiedTime: Long?

    /** Opens the source for reading, positioned at [offset]. */
    fun openAt(offset: Long): InputStream
}
