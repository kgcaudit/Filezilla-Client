package org.filezilla.android.archive

import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.CRC32

/**
 * PKZIP's traditional encryption, which ALZ uses unchanged.
 *
 * Three keys, stirred with the same CRC-32 table the archive already
 * needs, producing one byte of keystream at a time. unalz implements
 * exactly this; so does every zip tool written before 2003.
 *
 * It is weak -- a known-plaintext attack recovers the keys from a few
 * bytes -- and that is worth saying once here rather than pretending
 * otherwise. It is not weak because of anything done in this file: it is
 * the encryption the format specifies, and the choice is between reading
 * these archives and not reading them.
 */
internal class ZipCrypto(password: CharArray) {

    private val keys = intArrayOf(305419896, 591751049, 878082192)

    init {
        // The password's bytes, not its characters. Korean passwords are
        // rare in these files but not impossible, and CP949 is what made
        // the archive.
        for (byte in String(password).toByteArray(AlzArchive.NAMES)) update(byte)
    }

    private fun update(byte: Byte) {
        keys[0] = crc32(keys[0], byte)
        keys[1] += keys[0] and 0xFF
        keys[1] = keys[1] * 134775813 + 1
        keys[2] = crc32(keys[2], (keys[1] ushr 24).toByte())
    }

    private fun keystream(): Byte {
        val temp = (keys[2] or 2) and 0xFFFF
        return ((temp * (temp xor 1)) ushr 8).toByte()
    }

    /** Decrypts in place, which is how both the header and the data are read. */
    fun decrypt(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset) {
        for (i in offset until offset + count) {
            val plain = (bytes[i].toInt() xor keystream().toInt()).toByte()
            update(plain)
            bytes[i] = plain
        }
    }

    /** The rest of a stream, decrypted as it is read. */
    fun decrypting(source: InputStream): InputStream = object : FilterInputStream(source) {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
        }

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            val got = source.read(buffer, offset, count)
            if (got > 0) decrypt(buffer, offset, got)
            return got
        }
    }

    private companion object {
        /**
         * One byte through the CRC-32 table.
         *
         * Java's CRC32 is for whole streams and keeps its own running
         * value, which is the opposite of what a key schedule wants, so
         * the table is built once and stepped by hand.
         */
        private val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor -306674912 else c ushr 1 }
            c
        }

        fun crc32(value: Int, byte: Byte): Int =
            TABLE[(value xor byte.toInt()) and 0xFF] xor (value ushr 8)
    }
}
