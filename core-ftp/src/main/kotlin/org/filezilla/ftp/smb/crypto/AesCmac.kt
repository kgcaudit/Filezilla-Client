package org.filezilla.ftp.smb.crypto

import com.hierynomus.security.Mac
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-CMAC (RFC 4493), the message authentication SMB 3 signs with.
 *
 * SMB 2.x signs with HMAC-SHA256, which every Android JCE provides; SMB 3 signs
 * with AES-128-CMAC, which Android's JCE does not, and smbj's only built-in
 * AES-CMAC is BouncyCastle's -- the provider that cannot be trusted on an older
 * Android (see [org.filezilla.ftp.smb.SmbClient]). So it is built here instead,
 * on the one AES primitive Android does give: the raw block cipher
 * `AES/ECB/NoPadding`. Nothing of BouncyCastle is involved.
 *
 * CMAC is a CBC-MAC with two tweaks that close the length-extension hole the
 * plain CBC-MAC has: a pair of subkeys derived from `E(0)`, and the last block
 * XORed with one of them (K1 whole, K2 padded). The implementation is the
 * reference one from RFC 4493, online so a large signed write is not copied
 * whole: full blocks are fed as they arrive, and only the final partial (or
 * last whole) block is held back for the special treatment.
 *
 * Proven against the RFC 4493 test vectors in `AesCmacTest`.
 */
class AesCmac : Mac {

    private lateinit var ecb: Cipher
    private lateinit var k1: ByteArray
    private lateinit var k2: ByteArray

    /** The running CBC-MAC state X, one AES block wide. */
    private val x = ByteArray(BLOCK)

    /**
     * Bytes not yet known to be non-final. CMAC treats the last block apart, so
     * a full block is processed only once more input proves it is not the last;
     * up to a whole block is therefore held here.
     */
    private val pending = ByteArray(BLOCK)
    private var pendingLen = 0

    override fun init(key: ByteArray) {
        require(key.size == BLOCK) { "AES-CMAC here is AES-128: a ${key.size}-byte key is not 16" }
        ecb = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        // Subkeys: L = E(0); K1 = L<<1 (xor Rb if L's top bit set); K2 = K1<<1 (same).
        val l = encryptBlock(ByteArray(BLOCK))
        k1 = shiftLeftXorRb(l)
        k2 = shiftLeftXorRb(k1)
        reset()
    }

    override fun update(input: Byte) = update(byteArrayOf(input), 0, 1)

    override fun update(input: ByteArray) = update(input, 0, input.size)

    override fun update(input: ByteArray, offset: Int, length: Int) {
        var pos = offset
        var left = length
        while (left > 0) {
            // Keep the pending block from ever filling and being consumed here:
            // only input that is certainly followed by more is folded in, so the
            // final block is always still in hand at doFinal.
            if (pendingLen == BLOCK) {
                foldBlock(pending, 0)
                pendingLen = 0
            }
            val take = minOf(BLOCK - pendingLen, left)
            System.arraycopy(input, pos, pending, pendingLen, take)
            pendingLen += take
            pos += take
            left -= take
        }
    }

    override fun doFinal(): ByteArray {
        val last = ByteArray(BLOCK)
        if (pendingLen == BLOCK) {
            // A whole final block: XOR with K1, no padding.
            for (i in 0 until BLOCK) last[i] = (pending[i].toInt() xor k1[i].toInt()).toByte()
        } else {
            // A short (possibly empty) final block: pad with 0x80 0x00..., XOR K2.
            for (i in 0 until pendingLen) last[i] = pending[i]
            last[pendingLen] = 0x80.toByte()
            for (i in 0 until BLOCK) last[i] = (last[i].toInt() xor k2[i].toInt()).toByte()
        }
        val mac = encryptBlock(xor(x, last))
        reset()
        return mac
    }

    override fun reset() {
        x.fill(0)
        pendingLen = 0
    }

    /** X := E(X xor block). */
    private fun foldBlock(block: ByteArray, offset: Int) {
        for (i in 0 until BLOCK) x[i] = (x[i].toInt() xor block[offset + i].toInt()).toByte()
        val enc = encryptBlock(x)
        System.arraycopy(enc, 0, x, 0, BLOCK)
    }

    private fun encryptBlock(block: ByteArray): ByteArray = ecb.doFinal(block)

    private companion object {
        const val BLOCK = 16

        /** One left shift of a 128-bit big-endian value, XOR Rb if a bit fell off the top. */
        fun shiftLeftXorRb(input: ByteArray): ByteArray {
            val out = ByteArray(BLOCK)
            var carry = 0
            for (i in BLOCK - 1 downTo 0) {
                val v = (input[i].toInt() and 0xFF) shl 1 or carry
                out[i] = (v and 0xFF).toByte()
                carry = (v ushr 8) and 1
            }
            if (carry != 0) out[BLOCK - 1] = (out[BLOCK - 1].toInt() xor 0x87).toByte()
            return out
        }

        fun xor(a: ByteArray, b: ByteArray): ByteArray =
            ByteArray(BLOCK) { (a[it].toInt() xor b[it].toInt()).toByte() }
    }
}
