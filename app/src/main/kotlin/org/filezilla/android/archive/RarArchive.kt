package org.filezilla.android.archive

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * A RAR archive, listed and read through [RarNative].
 *
 * The listing is the native reader's; the whole-archive extract goes
 * straight to [RarNative.extract] rather than through [ArchiveExtract],
 * because RAR is often solid -- one file's bytes need every earlier file
 * decompressed -- so pulling entries one at a time would be O(n^2). This
 * [open] is only for the single-file tap, where extracting the one entry
 * (and, in a solid archive, what precedes it) is the price of a preview.
 */
class RarArchive private constructor(
    private val file: File,
    override val entries: List<ArchiveEntry>,
) : Archive {

    override fun open(entry: ArchiveEntry, password: CharArray?): InputStream {
        val temp = File.createTempFile("rar", "").apply { delete(); mkdirs() }
        val out = try {
            extractOne(entry, temp, password)
        } catch (failure: Throwable) {
            temp.deleteRecursively()
            throw failure
        }
        // Deleted when the reader is done with it, so a preview leaves
        // nothing behind in the cache.
        return object : FilterInputStream(out.inputStream()) {
            override fun close() {
                super.close()
                temp.deleteRecursively()
            }
        }
    }

    override fun extractTo(entry: ArchiveEntry, dest: File, password: CharArray?): Boolean {
        // Unpacked beside the destination and moved onto it -- a rename on the
        // one volume, so the caller gets its file without the entry being
        // written here and copied out again.
        val temp = File(dest.parentFile, dest.name + ".x." + java.util.UUID.randomUUID()).apply { mkdirs() }
        return try {
            val out = extractOne(entry, temp, password)
            dest.delete()
            out.renameTo(dest)
        } finally {
            temp.deleteRecursively()
        }
    }

    /** Unpacks the one [entry] under [into], or throws; returns the file written. */
    private fun extractOne(entry: ArchiveEntry, into: File, password: CharArray?): File {
        if (entry.isDirectory) throw NotAnArchive("${entry.path} is a folder")
        val result = RarNative.extract(
            file, into, setOf(entry.path), password, entry.size.coerceAtLeast(0),
            skipExisting = false,
            object : RarNative.Sink {
                override fun entry(name: String, size: Long, isDirectory: Boolean, modifiedMillis: Long, encrypted: Boolean) = Unit
                override fun progress(doneBytes: Long, totalBytes: Long, name: String) = Unit
                override fun cancelled() = false
            },
        )
        if (result == RarNative.Result.WRONG_PASSWORD) {
            throw WrongPassword("the password does not open this entry")
        }
        val out = File(into, entry.path)
        if (result != RarNative.Result.OK || !out.isFile) {
            throw NotAnArchive("could not read ${entry.path} from ${file.name}")
        }
        return out
    }

    override fun close() = Unit

    companion object {
        /** `Rar!\x1A\x07\x00` (RAR 4) and `Rar!\x1A\x07\x01\x00` (RAR 5). */
        private val SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)

        fun looksLikeRar(file: File): Boolean =
            firstBytes(file, 6).contentEquals(SIGNATURE)

        fun open(file: File): RarArchive {
            if (!RarNative.available) throw NotAnArchive("this device has no rar reader")
            return RarArchive(file, RarNative.list(file))
        }
    }
}
