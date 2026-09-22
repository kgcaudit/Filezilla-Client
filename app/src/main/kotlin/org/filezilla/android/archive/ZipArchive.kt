package org.filezilla.android.archive

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.zip.Inflater

/**
 * Zip, read from the central directory rather than through [java.util.zip.ZipFile].
 *
 * The decompression is still the platform's -- [Inflater] is a wrapper
 * over the system zlib, so nothing is added to the app -- but the
 * archive's own structure is walked here, for two reasons.
 *
 * A modern `ZipFile` refuses to open an archive containing *any*
 * encrypted entry: "invalid CEN header (encrypted entry)". One entry
 * somebody put a password on and the other nine become unreadable too,
 * with an error about a header. That check also arrived at different
 * times on different Android versions, so the same archive behaves
 * differently on two phones.
 *
 * And the flag word, which is the whole of the encoding question, is not
 * exposed by `ZipEntry` at all. A zip name is bytes plus bit 11: set
 * means UTF-8, clear means whatever the machine that wrote it used, and
 * in Korea that has been CP949 for twenty years. Reading the flag is the
 * difference between an archive that opens and a list of mojibake.
 *
 * (zip4j, the usual answer, applies its charset to everything and ignores
 * the flag. Set it to CP949 and every modern archive is mojibake; leave
 * it and every Korean one is.)
 */
class ZipArchive private constructor(
    private val volumes: Volumes,
    override val entries: List<ArchiveEntry>,
    private val records: Map<String, Record>,
) : Archive {

    internal data class Record(
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    override fun open(entry: ArchiveEntry, password: CharArray?): InputStream {
        if (entry.unreadable == ArchiveEntry.Unreadable.ENCRYPTED_METHOD) {
            // No decryption here and nothing to fall back on. Said plainly
            // rather than handed back as a stream that fails further in.
            throw WrongPassword("this app cannot open a password-protected zip")
        }
        val record = records[entry.path] ?: throw NotAnArchive("no such entry: ${entry.path}")

        // The local header's name and extra fields may be sized
        // differently from the central one's, so where the data starts is
        // read from the local header and never assumed.
        val header = ByteArray(LOCAL_HEADER_LENGTH)
        volumes.readFully(record.localHeaderOffset, header)
        if (header.intAt(0) != LOCAL_FILE_HEADER) throw NotAnArchive("entry ${entry.path} is not where it said")
        val dataAt = record.localHeaderOffset + LOCAL_HEADER_LENGTH +
            header.shortAt(26) + header.shortAt(28)

        val raw = Joined(volumes.parts, dataAt, record.compressedSize)
        return when (record.method) {
            METHOD_STORE -> raw
            METHOD_DEFLATE -> inflating(raw)
            else -> throw NotAnArchive("compression method ${record.method} is not read")
        }
    }

    override fun close() = volumes.close()

    companion object {
        private const val LOCAL_FILE_HEADER = 0x04034B50
        private const val CENTRAL_FILE_HEADER = 0x02014B50
        private const val END_OF_CENTRAL_DIRECTORY = 0x06054B50
        private const val ZIP64_END_RECORD = 0x06064B50
        private const val ZIP64_LOCATOR = 0x07064B50

        /** The zip64 locator is a fixed 20 bytes; its bytes 8..15 are the record's offset. */
        private const val ZIP64_LOCATOR_LENGTH = 20

        /** Enough of the zip64 end record to reach the count (byte 32) and offset (byte 48). */
        private const val ZIP64_END_LENGTH = 56
        private const val LOCAL_HEADER_LENGTH = 30
        private const val CENTRAL_HEADER_LENGTH = 46

        internal const val METHOD_STORE = 0
        internal const val METHOD_DEFLATE = 8

        private const val FLAG_ENCRYPTED = 0x0001
        private const val FLAG_UTF8_NAME = 0x0800

        /** Where the end record may sit: its own size plus the longest comment. */
        private const val END_RECORD_SEARCH = 22 + 0xFFFF

        /** A four-byte field holding this means the real value is in the Zip64 extra. */
        private const val NEEDS_ZIP64 = 0xFFFFFFFFL

        /**
         * What names are read as when the entry does not say.
         *
         * Only ever applied to entries whose UTF-8 flag is clear, so a
         * modern archive is untouched. CP949 rather than EUC-KR because it
         * is the superset Windows actually writes.
         */
        internal val FALLBACK: Charset = Charset.forName("x-windows-949")

        private val SIGNATURES = listOf(
            byteArrayOf(0x50, 0x4B, 0x03, 0x04),
            byteArrayOf(0x50, 0x4B, 0x05, 0x06),
            byteArrayOf(0x50, 0x4B, 0x07, 0x08),
        )

        fun looksLikeZip(file: File): Boolean =
            firstBytes(file, 4).let { head -> SIGNATURES.any { it.contentEquals(head) } }

        /**
         * The parts of a split zip, first to last, or the file itself.
         *
         * Two shapes are read. A raw byte split -- `name.zip.001`, `.002`,
         * ... -- is opened from the first part, and is just the whole zip cut
         * into pieces. A standard split -- `name.z01`, `.z02`, ... and
         * `name.zip` last -- puts the central directory in the final `.zip`
         * and addresses the rest by disk number; it opens from any of its
         * parts. A lone `.zip` is its own one-part set.
         */
        fun volumesOf(file: File): List<File> {
            val parent = file.parentFile ?: return listOf(file)
            val name = file.name
            Regex("""(?i)^(.+\.zip)\.(\d{3,})$""").matchEntire(name)?.let { m ->
                if (m.groupValues[2].toInt() != 1) return listOf(file)
                val parts = gather(parent, m.groupValues[1], ".", m.groupValues[2].length, start = 1)
                return parts.ifEmpty { listOf(file) }
            }
            val base = when {
                name.lowercase().endsWith(".zip") && File(parent, name.dropLast(4) + ".z01").isFile ->
                    name.dropLast(4)
                Regex("""(?i)^(.+)\.z\d{2,}$""").matches(name) -> name.substringBeforeLast('.')
                else -> return listOf(file)
            }
            val parts = gather(parent, base, ".z", 2, start = 1)
            val last = File(parent, "$base.zip")
            return if (parts.isNotEmpty() && last.isFile) parts + last else listOf(file)
        }

        /** Consecutive parts `$stem$sep<n padded to width>` that exist, from [start]. */
        private fun gather(parent: File, stem: String, sep: String, width: Int, start: Int): List<File> {
            val parts = mutableListOf<File>()
            var n = start
            while (true) {
                val part = File(parent, stem + sep + n.toString().padStart(width, '0'))
                if (!part.isFile) break
                parts += part
                n++
            }
            return parts
        }

        /** Whether [file] is (part of) a split zip spread over several files. */
        fun isSplitZip(file: File): Boolean = volumesOf(file).size > 1

        /**
         * Opens [file], reading unflagged names as [names].
         *
         * [names] is offered rather than fixed because an archive can be
         * wrong about itself: a tool that wrote UTF-8 names without
         * setting the flag exists, and the only cure is to be told.
         */
        fun open(file: File, names: Charset = FALLBACK): ZipArchive {
            val volumes = Volumes(volumesOf(file).map { RandomAccessFile(it, "r") })
            return runCatching { read(volumes, names) }.getOrElse { failure ->
                runCatching { volumes.close() }
                throw if (failure is NotAnArchive) failure else NotAnArchive("${file.name} is not a zip: ${failure.message}")
            }
        }

        private fun read(volumes: Volumes, names: Charset): ZipArchive {
            val length = volumes.length
            val window = minOf(length, END_RECORD_SEARCH.toLong()).toInt()
            val tail = ByteArray(window)
            volumes.readFully(length - window, tail)

            // Backwards, because the end record is last and a comment is
            // free to contain something that looks like one.
            var end = -1
            for (i in tail.size - 22 downTo 0) {
                if (tail.intAt(i) == END_OF_CENTRAL_DIRECTORY) {
                    end = i
                    break
                }
            }
            if (end < 0) throw NotAnArchive("no end-of-central-directory record")

            var count = tail.shortAt(end + 10)
            // The directory's place is a disk number and an offset within it.
            // For a single zip both resolve to the plain offset; for a split
            // one the disk picks the part and the offset the place inside it.
            var cdDisk = tail.shortAt(end + 6)
            var cdOffset = tail.intAt(end + 16).toLong() and 0xFFFFFFFFL

            // A zip over four gigabytes -- or one a tool chose to write in
            // the newer form -- puts 0xFFFF and 0xFFFFFFFF here and the
            // real figures in the zip64 end record. Its locator sits just
            // before this record and points at it. Without this the
            // placeholder reads as an offset past the end of the file, and
            // a big comic zip opens onto nothing.
            if (count == 0xFFFF || cdOffset == NEEDS_ZIP64 || cdDisk == 0xFFFF) {
                val locator = end - ZIP64_LOCATOR_LENGTH
                if (locator >= 0 && tail.intAt(locator) == ZIP64_LOCATOR) {
                    val z64Abs = volumes.absolute(tail.intAt(locator + 4), tail.longAt(locator + 8))
                    val header = ByteArray(ZIP64_END_LENGTH)
                    if (z64Abs >= 0 && z64Abs + ZIP64_END_LENGTH <= length) {
                        volumes.readFully(z64Abs, header)
                        if (header.intAt(0) == ZIP64_END_RECORD) {
                            count = header.longAt(32).toInt()
                            cdDisk = header.intAt(20)
                            cdOffset = header.longAt(48)
                        }
                    }
                }
            }
            val start = volumes.absolute(cdDisk, cdOffset)
            if (start >= length) throw NotAnArchive("the central directory is outside the file")

            val entries = mutableListOf<ArchiveEntry>()
            val records = LinkedHashMap<String, Record>(count)
            var pos = start

            repeat(count) {
                if (pos + CENTRAL_HEADER_LENGTH > length) return@repeat
                val header = ByteArray(CENTRAL_HEADER_LENGTH)
                volumes.readFully(pos, header)
                pos += CENTRAL_HEADER_LENGTH
                if (header.intAt(0) != CENTRAL_FILE_HEADER) return@repeat

                val flag = header.shortAt(8)
                val method = header.shortAt(10)
                val dosTime = header.intAt(12)
                val nameLength = header.shortAt(28)
                val extraLength = header.shortAt(30)
                val commentLength = header.shortAt(32)
                val diskStart = header.shortAt(34)
                val external = header.intAt(38)

                var compressed = header.intAt(20).toLong() and 0xFFFFFFFFL
                var uncompressed = header.intAt(24).toLong() and 0xFFFFFFFFL
                var offset = header.intAt(42).toLong() and 0xFFFFFFFFL

                if (pos + nameLength + extraLength > length) return@repeat
                val nameBytes = ByteArray(nameLength)
                volumes.readFully(pos, nameBytes)
                pos += nameLength
                // The flag decides; the fallback applies only without it.
                val name = String(nameBytes, if (flag and FLAG_UTF8_NAME != 0) Charsets.UTF_8 else names)

                val extra = ByteArray(extraLength)
                if (extraLength > 0) volumes.readFully(pos, extra)
                pos += extraLength
                // Over four gigabytes the real figures live in an extra
                // field, and the ones above are all ones. Reading those as
                // sizes would claim a four-gigabyte archive and truncate.
                if (uncompressed == NEEDS_ZIP64 || compressed == NEEDS_ZIP64 || offset == NEEDS_ZIP64) {
                    val zip64 = zip64In(extra)
                    var at = 0
                    if (uncompressed == NEEDS_ZIP64) uncompressed = zip64.getOrElse(at++) { uncompressed }
                    if (compressed == NEEDS_ZIP64) compressed = zip64.getOrElse(at++) { compressed }
                    if (offset == NEEDS_ZIP64) offset = zip64.getOrElse(at) { offset }
                }
                pos += commentLength

                val encrypted = flag and FLAG_ENCRYPTED != 0
                val isDirectory = name.endsWith("/") || (external and 0x10) != 0
                entries += ArchiveEntry(
                    path = name,
                    size = if (isDirectory) -1 else uncompressed,
                    compressedSize = if (isDirectory) -1 else compressed,
                    isDirectory = isDirectory,
                    modifiedMillis = AlzArchive.dosTimeToMillis(dosTime),
                    encrypted = encrypted,
                    unreadable = when {
                        isDirectory -> null
                        encrypted -> ArchiveEntry.Unreadable.ENCRYPTED_METHOD
                        method == METHOD_STORE || method == METHOD_DEFLATE -> null
                        else -> ArchiveEntry.Unreadable.COMPRESSION_METHOD
                    },
                )
                // The local header's place is a disk and an offset within it,
                // turned into one absolute position over the joined parts.
                records[name] = Record(method, compressed, uncompressed, volumes.absolute(diskStart, offset))
            }

            if (entries.isEmpty() && count > 0) throw NotAnArchive("the central directory could not be read")
            return ZipArchive(volumes, entries, records)
        }

        /** The eight-byte values in a Zip64 extra field, in the order stored. */
        private fun zip64In(extra: ByteArray): List<Long> {
            var at = 0
            while (at + 4 <= extra.size) {
                val id = extra.shortAt(at)
                val size = extra.shortAt(at + 2)
                if (id == 0x0001) {
                    val values = mutableListOf<Long>()
                    var read = at + 4
                    while (read + 8 <= at + 4 + size && read + 8 <= extra.size) {
                        values += extra.longAt(read)
                        read += 8
                    }
                    return values
                }
                at += 4 + size
            }
            return emptyList()
        }

    }
}
