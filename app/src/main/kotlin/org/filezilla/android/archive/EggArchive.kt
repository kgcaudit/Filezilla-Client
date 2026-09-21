package org.filezilla.android.archive

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.nio.charset.Charset
import java.util.Collections
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * EGG, ESTsoft's newer format, read from the published specification.
 *
 * ALZip writes both EGG and ALZ, and EGG is what it writes by default
 * now, so an app that reads only ALZ reads the older half of what Korean
 * users have. Two sources were used and they do not agree, which is
 * worth recording because the disagreement decided the code:
 *
 *  - unegg (ESTsoft's own extractor, C++) shows the chunk layout, but it
 *    handles only the simplest form of each. Its extra-field reader
 *    throws unless the flag byte is exactly 0 or 1, so a name stored in
 *    a code page, a relative path, or an encrypted name -- all of which
 *    the format allows -- make it give up on the file.
 *  - the EGG Format Specification 1.0 (ESTsoft, 2009-2016) gives the
 *    flag bits their meanings, and a good deal that unegg has no code
 *    for at all: the Posix file information header, solid archives, the
 *    locale field, the parent-path id.
 *
 * Where they differ the specification wins, because what it describes is
 * what ALZip may write, not what one reader happens to handle.
 *
 * Three things here are unlike ALZ and zip:
 *
 *  - A name is UTF-8 *unless* the filename header's flag says otherwise,
 *    and then a two-byte locale says which code page (949 for Korean).
 *    So the encoding question that makes ALZ and zip awkward is answered
 *    by the archive itself, when the writer bothers to answer it.
 *  - A file's bytes are a list of *blocks*, each separately compressed;
 *    its content is their concatenation. And in a solid archive the
 *    blocks belong to the archive rather than to any one file, with each
 *    file a slice of the joined stream.
 *  - Times are Windows FILETIME, a real instant in UTC, rather than DOS
 *    local time -- so unlike ALZ and zip there is no zone to assume.
 *
 * Store and deflate are read. bzip2, AZO (ESTsoft's own) and LZMA are
 * listed and marked out of reach rather than hidden: see
 * [ArchiveEntry.unreadable].
 *
 * Reading only, and that is also the licence: ESTsoft permits the format
 * and its decompression to be used and redistributed freely for
 * non-commercial purposes, and says outright that it may not be used to
 * build a compressor. Nothing here writes an EGG.
 */
class EggArchive private constructor(
    private val parts: List<RandomAccessFile>,
    override val entries: List<ArchiveEntry>,
    private val records: Map<String, Record>,
) : Archive {

    /** One compressed run. A file is these, in order, joined. */
    internal data class Block(
        val method: Int,
        val offset: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
    )

    internal data class Encryption(val method: Int, val verify: ByteArray, val crc: Int)

    /**
     * Where an entry's bytes are.
     *
     * [solidAt] is -1 in an ordinary archive, where [blocks] are the
     * entry's own. In a solid archive [blocks] are the whole archive's
     * and [solidAt] is how far into the joined stream this entry begins.
     */
    internal data class Record(
        val blocks: List<Block>,
        val encryption: Encryption?,
        val solidAt: Long = -1,
        val solidLength: Long = 0,
    )

    override fun open(entry: ArchiveEntry, password: CharArray?): InputStream {
        val record = records[entry.path] ?: throw NotAnArchive("no such entry: ${entry.path}")
        when (entry.unreadable) {
            ArchiveEntry.Unreadable.ENCRYPTED_METHOD ->
                throw WrongPassword("this entry uses an encryption this app does not do")
            ArchiveEntry.Unreadable.COMPRESSION_METHOD ->
                throw NotAnArchive("compression method ${record.blocks.firstOrNull()?.method} is not read yet")
            null -> Unit
        }

        // One cipher for the whole entry, not one per block: the keystream
        // runs on through the packed bytes of every block in order, which
        // is why the blocks below are read in order and never skipped.
        val crypto = record.encryption?.let { encryption ->
            val key = password ?: throw WrongPassword("this entry needs a password")
            ZipCrypto(key).also { cipher ->
                // Twelve check bytes, kept in the header here rather than
                // in front of the data as zip and alz keep them. The last
                // one has to come out as the top byte of the CRC.
                val header = encryption.verify.copyOf()
                cipher.decrypt(header)
                if (header[11] != (encryption.crc ushr 24).toByte()) {
                    throw WrongPassword("the password does not open this entry")
                }
            }
        }

        val joined = SequenceInputStream(Collections.enumeration(
            record.blocks.map { block ->
                val raw = Joined(parts, block.offset, block.compressedSize)
                val plain = crypto?.decrypting(raw) ?: raw
                when (block.method) {
                    METHOD_STORE -> plain
                    METHOD_DEFLATE -> InflaterInputStream(plain, Inflater(true), 64 * 1024)
                    else -> throw NotAnArchive("compression method ${block.method} is not read yet")
                }
            }
        ))
        if (record.solidAt < 0) return joined

        // A solid archive compressed every file as one run, so this
        // entry's bytes can only be reached by decompressing what comes
        // before them. Slow for the last file of a large archive, and
        // there is no way round it: that is what solid means.
        var skipped = 0L
        while (skipped < record.solidAt) {
            val stepped = joined.skip(record.solidAt - skipped)
            if (stepped <= 0) throw NotAnArchive("this egg ends before ${entry.path} begins")
            skipped += stepped
        }
        return Limited(joined, record.solidLength)
    }

    override fun close() = parts.forEach { runCatching { it.close() } }.let {}

    /** The first [length] bytes of [source] and no more. */
    private class Limited(private val source: InputStream, private var length: Long) : InputStream() {
        override fun read(): Int {
            if (length <= 0) return -1
            val one = source.read()
            if (one >= 0) length--
            return one
        }

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            if (length <= 0) return -1
            val got = source.read(buffer, offset, minOf(count.toLong(), length).toInt())
            if (got > 0) length -= got
            return got
        }

        override fun available(): Int = minOf(length, source.available().toLong()).toInt()
        override fun close() = source.close()
    }

    companion object {
        /** `EGGA`, little endian. */
        private const val SIG_EGG = 0x41474745
        private const val SIG_SPLIT = 0x24F5A262
        private const val SIG_SOLID = 0x24E5A060
        private const val SIG_FILE = 0x0A8590E3
        private const val SIG_FILENAME = 0x0A8591AC
        private const val SIG_WINDOWS_FILE_INFO = 0x2C86950B
        private const val SIG_POSIX_FILE_INFO = 0x1EE922E5
        private const val SIG_ENCRYPT = 0x08D1470F
        private const val SIG_BLOCK = 0x02B50C13
        private const val SIG_END = 0x08E28222

        internal const val METHOD_STORE = 0
        internal const val METHOD_DEFLATE = 1
        internal const val METHOD_BZIP2 = 2
        internal const val METHOD_AZO = 3
        internal const val METHOD_LZMA = 4

        internal const val ENCRYPT_KEY_XOR = 0
        internal const val ENCRYPT_AES128 = 1
        internal const val ENCRYPT_AES256 = 2
        internal const val ENCRYPT_LEA128 = 5
        internal const val ENCRYPT_LEA256 = 6

        /** Bit 1 of an extra field's flag byte: set means a four-byte length. */
        private const val FLAG_LONG_SIZE = 0x01

        /** Bit 4 of a filename's flag byte: set means a code page, clear means UTF-8. */
        private const val FLAG_CODE_PAGE = 0x08

        /** Bit 5: set means the name is relative to the file named by an id. */
        private const val FLAG_RELATIVE_PATH = 0x10

        /** Bit 8 of the Windows attribute byte. Not the DOS 0x10. */
        private const val ATTRIBUTE_DIRECTORY = 0x80

        private const val POSIX_TYPE_MASK = 0xF000
        private const val POSIX_DIRECTORY = 0x4000

        private const val CRYPTO_HEADER_LENGTH = 12

        /** True when [file] starts with the EGG signature. */
        fun looksLikeEgg(file: File): Boolean = runCatching {
            RandomAccessFile(file, "r").use { raw ->
                val head = ByteArray(4)
                raw.readFully(head)
                head.intAt(0) == SIG_EGG
            }
        }.getOrDefault(false)

        fun open(file: File): EggArchive {
            if (!file.isFile) throw NotAnArchive("${file.name} is not there")
            val parts = listOf(RandomAccessFile(file, "r"))
            return runCatching { read(parts) }.getOrElse { failure ->
                parts.forEach { runCatching { it.close() } }
                throw if (failure is NotAnArchive || failure is WrongPassword) failure
                else NotAnArchive("${file.name} is not an egg: ${failure.message}")
            }
        }

        /** What one file header said, before the entry it becomes. */
        private class Pending {
            var id = 0
            var size = 0L
            var path: String? = null
            var parentId: Int? = null
            var modified: Long? = null
            var isDirectory = false
            var encryption: Encryption? = null
            val blocks = mutableListOf<Block>()
        }

        private fun read(parts: List<RandomAccessFile>): EggArchive {
            val reader = Joined.Reader(parts)
            if (reader.readInt() != SIG_EGG) throw NotAnArchive("not an egg file")
            reader.skip(2) // version
            reader.skip(4) // the id of this volume, which split archives chain on
            reader.skip(4) // reserved

            var solid = false
            prefix@ while (true) {
                when (reader.readIntOrNull() ?: break@prefix) {
                    SIG_END -> break@prefix
                    SIG_SOLID -> {
                        extra(reader)
                        solid = true
                    }
                    SIG_SPLIT -> {
                        val field = extra(reader)
                        // Prev and next volume ids. A split archive spreads
                        // one file's headers across volumes -- the
                        // specification's own example puts the name in the
                        // second and the timestamp in the third -- so there
                        // is nothing sensible to show from this one alone.
                        val previous = if (field.size >= 4) field.intAt(0) else 0
                        val next = if (field.size >= 8) field.intAt(4) else 0
                        if (previous != 0 || next != 0) {
                            throw NotAnArchive("this egg is one volume of a split archive, which is not read yet")
                        }
                    }
                    // Anything else: skipped, which is what the
                    // specification asks for -- "handling only currently
                    // recognized signatures and skipping the rest" is how
                    // it keeps older readers working on newer archives.
                    else -> extra(reader)
                }
            }

            val pendings = mutableListOf<Pending>()
            files@ while (true) {
                when (reader.readIntOrNull() ?: break@files) {
                    SIG_END -> break@files
                    SIG_FILE -> pendings += readFile(reader)
                    SIG_BLOCK -> {
                        // Only in a solid archive, where the blocks come
                        // after the last file header rather than after each.
                        val block = readBlock(reader)
                        pendings.lastOrNull()?.blocks?.add(block)
                            ?: throw NotAnArchive("a block before any file")
                    }
                    else -> extra(reader)
                }
            }

            val entries = mutableListOf<ArchiveEntry>()
            val records = LinkedHashMap<String, Record>()
            val pathById = HashMap<Int, String>()
            val shared = if (solid) pendings.flatMap { it.blocks } else emptyList()
            var solidAt = 0L

            for (pending in pendings) {
                val name = pending.path ?: throw NotAnArchive("a file in this egg has no name")
                val under = pending.parentId?.let { pathById[it] }
                val full = if (under == null) name else under.trimEnd('/') + "/" + name
                val path = if (pending.isDirectory) full.trimEnd('/') + "/" else full
                pathById[pending.id] = path

                val blocks = if (solid) shared else pending.blocks
                val encryption = pending.encryption
                entries += ArchiveEntry(
                    path = path,
                    size = if (pending.isDirectory) -1 else pending.size,
                    compressedSize = when {
                        pending.isDirectory -> -1
                        // In a solid archive no one file has a compressed
                        // size of its own; saying -1 is truer than
                        // apportioning the block's.
                        solid -> -1
                        else -> pending.blocks.sumOf { it.compressedSize }
                    },
                    isDirectory = pending.isDirectory,
                    modifiedMillis = pending.modified,
                    encrypted = encryption != null,
                    unreadable = when {
                        pending.isDirectory -> null
                        // AES and LEA are real encryption, unlike this
                        // format's older one, and neither is done here.
                        // Saying so per entry beats an archive that lists
                        // and then fails on the extract.
                        encryption != null && encryption.method != ENCRYPT_KEY_XOR ->
                            ArchiveEntry.Unreadable.ENCRYPTED_METHOD
                        blocks.all { it.method == METHOD_STORE || it.method == METHOD_DEFLATE } -> null
                        else -> ArchiveEntry.Unreadable.COMPRESSION_METHOD
                    },
                )
                records[path] = if (solid && !pending.isDirectory) {
                    Record(shared, encryption, solidAt, pending.size).also { solidAt += pending.size }
                } else {
                    Record(pending.blocks, encryption)
                }
            }

            return EggArchive(parts, entries, records)
        }

        private fun readFile(reader: Joined.Reader): Pending {
            val pending = Pending()
            pending.id = reader.readInt()
            pending.size = reader.readSize(8)

            fields@ while (true) {
                when (reader.readIntOrNull() ?: break@fields) {
                    SIG_END -> break@fields
                    SIG_FILENAME -> readName(reader, pending)
                    SIG_WINDOWS_FILE_INFO -> extra(reader).let { field ->
                        if (field.size >= 9) {
                            pending.modified = fileTimeToMillis(field.longAt(0))
                            pending.isDirectory = field[8].toInt() and ATTRIBUTE_DIRECTORY != 0
                        }
                    }
                    // unegg has no code for this one at all; it is in the
                    // specification, and an archive written on a phone or
                    // a server is where it would turn up.
                    SIG_POSIX_FILE_INFO -> extra(reader).let { field ->
                        if (field.size >= 20) {
                            val mode = field.intAt(0)
                            pending.isDirectory = mode and POSIX_TYPE_MASK == POSIX_DIRECTORY
                            pending.modified = field.longAt(12) * 1000L
                        }
                    }
                    SIG_ENCRYPT -> pending.encryption = readEncryption(reader)
                    else -> extra(reader)
                }
            }

            blocks@ while (true) {
                val signature = reader.readIntOrNull() ?: break@blocks
                if (signature != SIG_BLOCK) {
                    // The end of this file's blocks: the next file, or the
                    // end of the archive. Put back for the caller to read.
                    reader.skip(-4)
                    break@blocks
                }
                pending.blocks += readBlock(reader)
            }
            return pending
        }

        /**
         * The filename header, whose flag byte decides how to read it.
         *
         * Bit 4 clear means UTF-8; set means a code page, and then a
         * two-byte locale says which -- 949 for Korean, 932 for Japanese,
         * 0 for "whatever the machine that wrote it used", which on a
         * Korean copy of ALZip is 949 again. Bit 5 set means the name is
         * relative to another entry, named by a four-byte id.
         */
        private fun readName(reader: Joined.Reader, pending: Pending) {
            val flags = reader.readByte()
            val size = if (flags and FLAG_LONG_SIZE != 0) reader.readSize(4).toInt() else reader.readShort()
            val field = reader.readBytes(size)

            var at = 0
            var charset = Charsets.UTF_8
            if (flags and FLAG_CODE_PAGE != 0) {
                val locale = if (field.size >= 2) field.shortAt(0) else 0
                at += 2
                charset = charsetFor(locale)
            }
            if (flags and FLAG_RELATIVE_PATH != 0) {
                if (field.size >= at + 4) pending.parentId = field.intAt(at)
                at += 4
            }
            if (at > field.size) throw NotAnArchive("a filename header is shorter than its own fields")
            pending.path = String(field, at, field.size - at, charset).replace('\\', '/')
        }

        /**
         * The code page a locale number means.
         *
         * Zero is "the system's", which is not this phone's -- it is the
         * one on the machine that wrote the archive. For a file with a
         * locale field at all, written by ALZip, that is Korean; and a
         * Korean name read as CP949 is the only reading that can come out
         * right. Falling back to UTF-8 here would produce the mojibake
         * this whole field exists to prevent.
         */
        private fun charsetFor(locale: Int): Charset = when (locale) {
            932 -> runCatching { Charset.forName("windows-31j") }.getOrElse { AlzArchive.NAMES }
            else -> AlzArchive.NAMES
        }

        private fun readBlock(reader: Joined.Reader): Block {
            val method = reader.readByte()
            reader.skip(1) // the writer's hint at the method, which is advice
            val uncompressed = reader.readSize(4)
            val compressed = reader.readSize(4)
            reader.skip(4) // crc
            if (reader.readInt() != SIG_END) throw NotAnArchive("a block header does not end")
            val offset = reader.position
            reader.skip(compressed)
            return Block(method, offset, compressed, uncompressed)
        }

        /**
         * The encrypt header, whose length the method decides.
         *
         * The specification's table gives the size as 17 and then lists
         * the AES and LEA fields that cannot fit in 17; unegg reads the
         * stored size and immediately overwrites it from the method. Both
         * say the same thing about what is actually written, so the
         * method is what is counted on here.
         */
        private fun readEncryption(reader: Joined.Reader): Encryption {
            extraSize(reader)
            val method = reader.readByte()
            val length = when (method) {
                ENCRYPT_KEY_XOR -> 16
                ENCRYPT_AES128, ENCRYPT_LEA128 -> 20
                ENCRYPT_AES256, ENCRYPT_LEA256 -> 28
                else -> throw NotAnArchive("unknown encryption $method")
            }
            val data = reader.readBytes(length)
            val verify = if (method == ENCRYPT_KEY_XOR) data.copyOf(CRYPTO_HEADER_LENGTH) else ByteArray(0)
            val crc = if (method == ENCRYPT_KEY_XOR) data.intAt(CRYPTO_HEADER_LENGTH) else 0
            return Encryption(method, verify, crc)
        }

        /** The payload of a chunk that carries its own length. */
        private fun extra(reader: Joined.Reader): ByteArray = reader.readBytes(extraSize(reader))

        /**
         * A chunk's length, in the width its flag byte asks for.
         *
         * Bit 1 set means four bytes rather than two. The other bits
         * belong to the chunk and are not length, which is why they are
         * masked off rather than refused -- unegg refuses them, and that
         * is exactly why it cannot read a name in a code page.
         */
        private fun extraSize(reader: Joined.Reader): Int {
            val flags = reader.readByte()
            return if (flags and FLAG_LONG_SIZE != 0) reader.readSize(4).toInt() else reader.readShort()
        }

        /**
         * Windows FILETIME to epoch milliseconds.
         *
         * Hundred-nanosecond ticks since 1601-01-01 UTC. Unlike the DOS
         * time in ALZ and zip this is a real instant, so there is no zone
         * to assume and nothing to get wrong on a phone that travels.
         */
        internal fun fileTimeToMillis(ticks: Long): Long? {
            if (ticks <= 0) return null
            return ticks / 10_000L - 11_644_473_600_000L
        }
    }
}

private fun ByteArray.longAt(at: Int): Long {
    var value = 0L
    for (i in 7 downTo 0) value = (value shl 8) or (this[at + i].toLong() and 0xFF)
    return value
}

private fun ByteArray.intAt(at: Int): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8) or
        ((this[at + 2].toInt() and 0xFF) shl 16) or ((this[at + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.shortAt(at: Int): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)
