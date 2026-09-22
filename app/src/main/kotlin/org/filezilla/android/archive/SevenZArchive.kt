package org.filezilla.android.archive

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * A 7z archive, listed and read through [SevenZipNative].
 *
 * The listing is the native reader's; the whole-archive extract goes
 * straight to [SevenZipNative.extract] rather than through [ArchiveExtract],
 * because 7z groups files into solid folders -- one file's bytes can need
 * every earlier file in its folder decompressed -- so pulling entries one at
 * a time would decode a folder again for each file in it. This [open] is
 * only for the single-file tap, where decoding the one folder is the price
 * of a preview.
 */
class SevenZArchive private constructor(
    private val file: File,
    override val entries: List<ArchiveEntry>,
) : Archive {

    override fun open(entry: ArchiveEntry, password: CharArray?): InputStream {
        val temp = File.createTempFile("sevenz", "").apply { delete(); mkdirs() }
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
        // Unpacked beside the destination, then moved onto it -- a rename on
        // the one volume, so the caller gets its file without the entry being
        // written once here and copied out again. The temp sits next to dest
        // so the move never crosses a mount and falls back to a copy.
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
        val result = SevenZipNative.extract(
            file, into, setOf(entry.path), password, entry.size.coerceAtLeast(0),
            skipExisting = false,
            object : SevenZipNative.Sink {
                override fun entry(name: String, size: Long, isDirectory: Boolean, modifiedMillis: Long, encrypted: Boolean, unsupported: Boolean) = Unit
                override fun progress(doneBytes: Long, totalBytes: Long, name: String) = Unit
                override fun cancelled() = false
            },
        )
        if (result == SevenZipNative.Result.WRONG_PASSWORD) {
            throw WrongPassword("the password does not open this entry")
        }
        val out = File(into, entry.path)
        if (result != SevenZipNative.Result.OK || !out.isFile) {
            throw NotAnArchive("could not read ${entry.path} from ${file.name}")
        }
        return out
    }

    override fun close() = Unit

    companion object {
        /** `7z\xBC\xAF\x27\x1C`, the six bytes every 7z starts with. */
        private val SIGNATURE = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

        fun looksLikeSevenZ(file: File): Boolean =
            firstBytes(file, 6).contentEquals(SIGNATURE)

        fun open(file: File): SevenZArchive {
            if (!SevenZipNative.available) throw NotAnArchive("this device has no 7z reader")
            return SevenZArchive(file, SevenZipNative.list(file))
        }
    }
}
