package org.filezilla.ftp.smb.crypto

import com.hierynomus.security.Cipher
import com.hierynomus.security.SecurityException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import javax.crypto.spec.GCMParameterSpec
import kotlin.random.Random

/**
 * [AesCcmCipher] against the RFC 3610 test vector and round trips.
 *
 * Packet Vector #1 from RFC 3610 is a worked known-answer: the same key, nonce,
 * header and payload must produce exactly the ciphertext and tag printed in the
 * standard, which is proof the CCM is real. The round trips then cover the shape
 * SMB actually uses -- an 11-byte nonce and a 16-byte tag -- and that a tampered
 * packet is refused rather than returned.
 */
class AesCcmTest {

    @Test
    fun `RFC 3610 packet vector 1`() {
        val key = hex("c0c1c2c3c4c5c6c7c8c9cacbcccdcecf")
        val nonce = hex("00000003020100a0a1a2a3a4a5") // 13 bytes, so L = 2
        val aad = hex("0001020304050607")
        val payload = hex("08090a0b0c0d0e0f101112131415161718191a1b1c1d1e")

        val cipher = AesCcmCipher()
        cipher.init(Cipher.CryptMode.ENCRYPT, key, GCMParameterSpec(64, nonce))
        cipher.updateAAD(aad, 0, aad.size)
        cipher.update(payload, 0, payload.size)
        val out = cipher.doFinal(ByteArray(0), 0, 0)

        assertEquals(
            "588c979a61c663d2f066d0c2c0f989806d5f6b61dac38417e8d12cfdf926e0",
            hex(out),
            "ciphertext followed by the 8-byte tag, as printed in RFC 3610",
        )
    }

    @Test
    fun `an SMB-shaped packet round-trips`() {
        val key = Random(7).nextBytes(16)
        val nonce = Random(8).nextBytes(11) // SMB's AES-128-CCM nonce length
        val aad = Random(9).nextBytes(32)
        val payload = Random(10).nextBytes(5000)

        val sealed = AesCcmCipher().run {
            init(Cipher.CryptMode.ENCRYPT, key, GCMParameterSpec(128, nonce))
            updateAAD(aad, 0, aad.size)
            doFinal(payload, 0, payload.size)
        }
        assertEquals(payload.size + 16, sealed.size, "ciphertext plus a 16-byte tag")

        val opened = AesCcmCipher().run {
            init(Cipher.CryptMode.DECRYPT, key, GCMParameterSpec(128, nonce))
            updateAAD(aad, 0, aad.size)
            doFinal(sealed, 0, sealed.size)
        }
        assertArrayEquals(payload, opened)
    }

    @Test
    fun `a tampered packet is refused`() {
        val key = Random(1).nextBytes(16)
        val nonce = Random(2).nextBytes(11)
        val aad = Random(3).nextBytes(16)
        val payload = Random(4).nextBytes(64)

        val sealed = AesCcmCipher().run {
            init(Cipher.CryptMode.ENCRYPT, key, GCMParameterSpec(128, nonce))
            updateAAD(aad, 0, aad.size)
            doFinal(payload, 0, payload.size)
        }
        sealed[0] = (sealed[0].toInt() xor 0x01).toByte()

        assertThrows(SecurityException::class.java) {
            AesCcmCipher().run {
                init(Cipher.CryptMode.DECRYPT, key, GCMParameterSpec(128, nonce))
                updateAAD(aad, 0, aad.size)
                doFinal(sealed, 0, sealed.size)
            }
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
}
