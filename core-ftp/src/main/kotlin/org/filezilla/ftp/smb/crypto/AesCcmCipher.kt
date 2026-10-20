package org.filezilla.ftp.smb.crypto

import com.hierynomus.security.AEADBlockCipher
import com.hierynomus.security.Cipher
import com.hierynomus.security.SecurityException
import java.io.ByteArrayOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-CCM (RFC 3610 / NIST SP 800-38C), the authenticated cipher SMB 3.0 and
 * 3.0.2 encrypt with.
 *
 * SMB 3.1.1 can encrypt with AES-GCM, which Android's JCE provides, so smbj's
 * JCE cipher handles it; SMB 3.0/3.0.2 only have CCM, which Android's JCE does
 * not, and smbj's only CCM is BouncyCastle's -- off-limits on older Android (see
 * [org.filezilla.ftp.smb.SmbClient]). So CCM is built here on the raw AES block
 * cipher `AES/ECB/NoPadding`, the same primitive [AesCmac] uses, with no
 * BouncyCastle.
 *
 * CCM is CBC-MAC for the tag and CTR for the data, both over AES. It is not an
 * online mode -- the message length goes into the first MAC block -- so, like
 * BouncyCastle's own CCM that smbj expects, this buffers everything handed to
 * [updateAAD] and [update] and does the whole computation in [doFinal].
 *
 * Proven by round-tripping against a real encrypted SMB 3.0 share in the SMB
 * integration tests.
 */
class AesCcmCipher : AEADBlockCipher {

    private var encrypting = true
    private lateinit var ecb: javax.crypto.Cipher
    private lateinit var nonce: ByteArray
    private var tagLen = 16
    private val aad = ByteArrayOutputStream()
    private val data = ByteArrayOutputStream()

    override fun init(mode: Cipher.CryptMode, key: ByteArray, spec: GCMParameterSpec) {
        encrypting = mode == Cipher.CryptMode.ENCRYPT
        ecb = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        nonce = spec.iv
        tagLen = spec.tLen / 8
        aad.reset()
        data.reset()
    }

    override fun updateAAD(input: ByteArray, offset: Int, length: Int) {
        aad.write(input, offset, length)
    }

    override fun update(input: ByteArray, offset: Int, length: Int): ByteArray {
        // CCM needs the whole message before it can start, so nothing is
        // produced here; it is all collected and processed in doFinal.
        data.write(input, offset, length)
        return EMPTY
    }

    override fun doFinal(input: ByteArray, offset: Int, length: Int): ByteArray {
        data.write(input, offset, length)
        val associated = aad.toByteArray()
        val message = data.toByteArray()
        aad.reset()
        data.reset()
        return if (encrypting) seal(associated, message) else open(associated, message)
    }

    override fun reset() {
        aad.reset()
        data.reset()
    }

    // ---------------------------------------------------------------- CCM core

    private fun seal(associated: ByteArray, plaintext: ByteArray): ByteArray {
        val tag = cbcMac(associated, plaintext)
        val keystreamZero = encryptBlock(counterBlock(0))
        val ciphertext = ctr(plaintext)
        val sealedTag = ByteArray(tagLen) { (tag[it].toInt() xor keystreamZero[it].toInt()).toByte() }
        return ciphertext + sealedTag
    }

    private fun open(associated: ByteArray, cipherAndTag: ByteArray): ByteArray {
        if (cipherAndTag.size < tagLen) throw SecurityException("the encrypted packet is too short for its tag")
        val ciphertext = cipherAndTag.copyOfRange(0, cipherAndTag.size - tagLen)
        val sealedTag = cipherAndTag.copyOfRange(cipherAndTag.size - tagLen, cipherAndTag.size)
        val plaintext = ctr(ciphertext)
        val tag = cbcMac(associated, plaintext)
        val keystreamZero = encryptBlock(counterBlock(0))
        var diff = 0
        for (i in 0 until tagLen) {
            diff = diff or (tag[i].toInt() xor keystreamZero[i].toInt() xor sealedTag[i].toInt())
        }
        if (diff != 0) throw SecurityException("the encrypted packet's authentication tag did not verify")
        return plaintext
    }

    /** The CBC-MAC of the formatted B0 || AAD || payload, truncated to the tag length. */
    private fun cbcMac(associated: ByteArray, payload: ByteArray): ByteArray {
        val l = 15 - nonce.size
        var block = firstBlock(associated.isNotEmpty(), payload.size, l)
        var x = encryptBlock(block)

        if (associated.isNotEmpty()) {
            // The AAD is prefixed with its length and the whole run is padded to
            // a block boundary. SMB's AAD is well under 2^16-2^8, the 2-byte case.
            val header = ByteArrayOutputStream()
            header.write((associated.size ushr 8) and 0xFF)
            header.write(associated.size and 0xFF)
            header.write(associated)
            x = foldPadded(x, header.toByteArray())
        }
        x = foldPadded(x, payload)
        return x.copyOf(tagLen)
    }

    /** The B0 block: flags, nonce, and the payload length in the low L bytes. */
    private fun firstBlock(hasAad: Boolean, payloadLen: Int, l: Int): ByteArray {
        val block = ByteArray(BLOCK)
        val adata = if (hasAad) 0x40 else 0x00
        block[0] = (adata or (((tagLen - 2) / 2) shl 3) or (l - 1)).toByte()
        System.arraycopy(nonce, 0, block, 1, nonce.size)
        var len = payloadLen
        for (i in 0 until l) {
            block[BLOCK - 1 - i] = (len and 0xFF).toByte()
            len = len ushr 8
        }
        return block
    }

    /** Folds [bytes] into the CBC-MAC state [x] a block at a time, zero-padding the last. */
    private fun foldPadded(x: ByteArray, bytes: ByteArray): ByteArray {
        var state = x
        var i = 0
        while (i < bytes.size) {
            val block = ByteArray(BLOCK)
            val n = minOf(BLOCK, bytes.size - i)
            System.arraycopy(bytes, i, block, 0, n)
            state = encryptBlock(xor(state, block))
            i += BLOCK
        }
        return state
    }

    /** CTR mode over [input], starting at counter 1 (counter 0 masks the tag). */
    private fun ctr(input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var counter = 1
        var i = 0
        while (i < input.size) {
            val keystream = encryptBlock(counterBlock(counter))
            val n = minOf(BLOCK, input.size - i)
            for (j in 0 until n) out[i + j] = (input[i + j].toInt() xor keystream[j].toInt()).toByte()
            counter++
            i += BLOCK
        }
        return out
    }

    /** The CTR block A_i: flags, nonce, and the counter in the low L bytes. */
    private fun counterBlock(counter: Int): ByteArray {
        val l = 15 - nonce.size
        val block = ByteArray(BLOCK)
        block[0] = (l - 1).toByte()
        System.arraycopy(nonce, 0, block, 1, nonce.size)
        var c = counter
        for (i in 0 until l) {
            block[BLOCK - 1 - i] = (c and 0xFF).toByte()
            c = c ushr 8
        }
        return block
    }

    private fun encryptBlock(block: ByteArray): ByteArray = ecb.doFinal(block)

    private companion object {
        const val BLOCK = 16
        val EMPTY = ByteArray(0)
        fun xor(a: ByteArray, b: ByteArray): ByteArray =
            ByteArray(BLOCK) { (a[it].toInt() xor b[it].toInt()).toByte() }
    }
}
