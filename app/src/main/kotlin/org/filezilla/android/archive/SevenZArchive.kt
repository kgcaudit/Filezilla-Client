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
        if (entry.isDirectory) throw NotAnArchive("${entry.path} is a folder")
        val temp = File.createTempFile("sevenz", "")
        temp.delete()
        temp.mkdirs()
        val result = SevenZipNative.extract(
            file, temp, setOf(entry.path), entry.size.coerceAtLeast(0),
            skipExisting = false,
            object : SevenZipNative.Sink {
                override fun entry(name: String, size: Long, isDirectory: Boolean, modifiedMillis: Long, encrypted: Boolean, unsupported: Boolean) = Unit
                override fun progress(doneBytes: Long, totalBytes: Long, name: String) = Unit
                override fun cancelled() = false
            },
        )
        val out = File(temp, entry.path)
        if (result != SevenZipNative.Result.OK || !out.isFile) {
            temp.deleteRecursively()
            throw NotAnArchive("could not read ${entry.path} from ${file.name}")
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
