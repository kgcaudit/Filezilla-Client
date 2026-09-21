package org.filezilla.android.archive

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.zip.Inflater

/**
 * ALZ, read from the format rather than from a library.
 *
 * ALZ is ESTsoft's, it is everywhere in Korea, and no Java library reads
 * it. The reference is unalz (zlib licence, kippler@gmail.com), which is
 * C++ -- but reading it shows there is nothing in the format that needs C:
 *
 *     inflateInit2(&stream, -MAX_WBITS)   raw deflate, which is Inflater
 *     BZ2_bzDecompressInit(&stream, 3, 0) plain bzip2, not a variant
 *
 * The "modified bzip2" its header comments mention turns out to be a
 * change to the file handling for split archives, not to the bitstream.
 * So this is a port of the format, not a binding to the code: no native
 * library, no second copy of zlib, nothing per-architecture.
 *
 * Reading only. Nothing else opens an ALZ outside Korea and little enough
 * inside it, so writing one would be making a file fewer things can read.
 */
class AlzArchive private constructor(
    private val parts: List<RandomAccessFile>,
    override val entries: List<ArchiveEntry>,
    private val records: Map<String, Record>,
) : Archive {

    /** Where an entry's bytes are and what has to be done to them. */
    internal data class Record(
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val crc: Int,
        val dataOffset: Long,
        val encrypted: Boolean,
        val cryptoHeader: ByteArray,
    )

    override fun open(entry: ArchiveEntry, password: CharArray?): InputStream {
        val record = records[entry.path] ?: throw NotAnArchive("no such entry: ${entry.path}")
        val raw = Joined(parts, record.dataOffset, record.compressedSize)

        val plain = if (record.encrypted) {
            val key = password ?: throw WrongPassword("this entry needs a password")
            // The CRC's top byte is the check: the twelfth decrypted byte of
            // the header must equal it. One byte, so a wrong password gets
            // through once in 256 tries -- which is the format's bargain,
            // not one made here, and the CRC at the end catches the rest.
            ZipCrypto(key).let { crypto ->
                val header = record.cryptoHeader.copyOf()
                crypto.decrypt(header)
                if (header[11] != (record.crc ushr 24).toByte()) {
                    throw WrongPassword("the password does not open this entry")
                }
                crypto.decrypting(raw)
            }
        } else {
            raw
        }

        return when (record.method) {
            METHOD_STORE -> plain
            METHOD_DEFLATE -> inflating(plain)
            else -> throw NotAnArchive("compression method ${record.method} is not read yet")
        }
    }

    override fun close() = parts.forEach { runCatching { it.close() } }.let {}

    companion object {
        /** `ALZ\x01`, `BLZ\x01`, `CLZ\x01`, `CLZ\x02`, little endian. */
        private const val SIG_FILE_HEADER = 0x015A4C41
        private const val SIG_LOCAL_FILE = 0x015A4C42
        private const val SIG_CENTRAL_DIRECTORY = 0x015A4C43
        private const val SIG_END_OF_CENTRAL = 0x025A4C43

        internal const val METHOD_STORE = 0
        internal const val METHOD_BZIP2 = 1
        internal const val METHOD_DEFLATE = 2

        private const val DESCRIPTOR_ENCRYPTED = 0x01

        /** Twelve bytes of PKZIP's traditional encryption header. */
        internal const val CRYPTO_HEADER_LENGTH = 12

        /**
         * Names are CP949, always.
         *
         * Not a guess and not a fallback: unalz says so outright --
         * "alz 는 949 만 지원" -- and the format carries no flag to say
         * otherwise, unlike zip. So there is nothing to detect here.
         */
        internal val NAMES: Charset = Charset.forName("x-windows-949")

        /** True when [file] starts with the ALZ signature. */
        fun looksLikeAlz(file: File): Boolean =
            firstBytes(file, 4).let { it.size == 4 && it.intAt(0) == SIG_FILE_HEADER }

        /**
         * Opens [file], taking in its `.a00`, `.a01` … if it has them.
         *
         * A split archive is one archive whose bytes run across several
         * files, and an entry can begin in one and end in the next. So the
         * parts are joined here rather than at every read.
         */
        fun open(file: File): AlzArchive {
            val parts = partsOf(file).map { RandomAccessFile(it, "r") }
            if (parts.isEmpty()) throw NotAnArchive("${file.name} is not there")
            return runCatching { read(parts) }.getOrElse { failure ->
                parts.forEach { runCatching { it.close() } }
                throw failure
            }
        }

        /** `x.alz` plus `x.a00`, `x.a01` …, in order, while they exist. */
        internal fun partsOf(file: File): List<File> {
            if (!file.isFile) return emptyList()
            val stem = file.path.substringBeforeLast('.')
            val rest = generateSequence(0) { it + 1 }
                .map { File("%s.a%02d".format(stem, it)) }
                .takeWhile { it.isFile }
                .toList()
            return listOf(file) + rest
        }

        private fun read(parts: List<RandomAccessFile>): AlzArchive {
            val reader = Joined.Reader(parts)
            if (reader.readInt() != SIG_FILE_HEADER) throw NotAnArchive("not an alz file")
            reader.skip(4) // The header's one unknown field.

            val entries = mutableListOf<ArchiveEntry>()
            val records = mutableMapOf<String, Record>()

            while (true) {
                val signature = reader.readIntOrNull() ?: break
                if (signature == SIG_CENTRAL_DIRECTORY || signature == SIG_END_OF_CENTRAL) break
                if (signature != SIG_LOCAL_FILE) throw NotAnArchive("unknown record %08x".format(signature))

                val nameLength = reader.readShort()
                val attribute = reader.readByte()
                val dosTime = reader.readInt()
                val descriptor = reader.readByte()
                reader.skip(1) // Unknown, one byte, always present.

                val encrypted = descriptor and DESCRIPTOR_ENCRYPTED != 0
                // The top nibble says how wide the size fields are: 0x10,
                // 0x20, 0x40, 0x80 for one, two, four and eight bytes. Zero
                // means the entry carries no sizes at all, which is how a
                // directory is written.
                val sizeWidth = descriptor / 0x10

                var method = METHOD_STORE
                var crc = 0
                var compressed = 0L
                var uncompressed = 0L
                if (sizeWidth != 0) {
                    method = reader.readByte()
                    reader.skip(1)
                    crc = reader.readInt()
                    compressed = reader.readSize(sizeWidth)
                    uncompressed = reader.readSize(sizeWidth)
                }

                val name = String(reader.readBytes(nameLength), NAMES).replace('\\', '/')
                val cryptoHeader = if (encrypted) reader.readBytes(CRYPTO_HEADER_LENGTH) else ByteArray(0)

                val isDirectory = attribute and ATTRIBUTE_DIRECTORY != 0
                val dataOffset = reader.position
                reader.skip(compressed)

                val path = if (isDirectory) name.trimEnd('/') + "/" else name
                entries += ArchiveEntry(
                    path = path,
                    size = if (sizeWidth == 0) -1 else uncompressed,
                    compressedSize = if (sizeWidth == 0) -1 else compressed,
                    isDirectory = isDirectory,
                    modifiedMillis = dosTimeToMillis(dosTime),
                    encrypted = encrypted,
                    unreadable = when {
                        isDirectory -> null
                        method == METHOD_STORE || method == METHOD_DEFLATE -> null
                        else -> ArchiveEntry.Unreadable.COMPRESSION_METHOD
                    },
                )
                records[path] = Record(method, compressed, uncompressed, crc, dataOffset, encrypted, cryptoHeader)
            }

            return AlzArchive(parts, entries, records)
        }

        private const val ATTRIBUTE_DIRECTORY = 0x10

        /**
         * DOS date and time, which is what the format stores.
         *
         * Seconds are in units of two, the year counts from 1980, and the
         * whole thing is local time with no zone -- so it is read as the
         * phone's local time, which is what every other tool does with it.
         */
        internal fun dosTimeToMillis(dos: Int): Long? {
            if (dos == 0) return null
            val second = (dos and 0x1F) * 2
            val minute = (dos ushr 5) and 0x3F
            val hour = (dos ushr 11) and 0x1F
            val day = (dos ushr 16) and 0x1F
            val month = (dos ushr 21) and 0x0F
            val year = ((dos ushr 25) and 0x7F) + 1980
            if (month < 1 || month > 12 || day < 1 || day > 31) return null
            return runCatching {
                java.util.GregorianCalendar(year, month - 1, day, hour, minute, second)
                    .also { it.set(java.util.Calendar.MILLISECOND, 0) }
                    .timeInMillis
            }.getOrNull()
        }
    }
}
