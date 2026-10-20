package org.filezilla.ftp.smb.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [AesCmac] against the RFC 4493 test vectors.
 *
 * These are the worked examples in the standard itself, so matching them is
 * proof the SMB 3 signing primitive is the real AES-CMAC and not something that
 * merely looks like it. The empty, one-block, part-block and whole-block cases
 * are all here, because the last block is the one CMAC treats specially.
 */
class AesCmacTest {

    private val key = hex("2b7e151628aed2a6abf7158809cf4f3c")

    private val message = hex(
        "6bc1bee22e409f96e93d7e117393172a" +
            "ae2d8a571e03ac9c9eb76fac45af8e51" +
            "30c81c46a35ce411e5fbc1191a0a52ef" +
            "f69f2445df4f9b17ad2b417be66c3710",
    )

    private fun mac(input: ByteArray): ByteArray =
        AesCmac().apply { init(key); update(input) }.doFinal()

    @Test
    fun `empty message`() {
        assertEquals("bb1d6929e95937287fa37d129b756746", hex(mac(ByteArray(0))))
    }

    @Test
    fun `one whole block`() {
        assertEquals("070a16b46b4d4144f79bdd9dd04a287c", hex(mac(message.copyOf(16))))
    }

    @Test
    fun `a part block, so the tail is padded`() {
        assertEquals("dfa66747de9ae63030ca32611497c827", hex(mac(message.copyOf(40))))
    }

    @Test
    fun `four whole blocks`() {
        assertEquals("51f0bebf7e3b9d92fc49741779363cfe", hex(mac(message.copyOf(64))))
    }

    @Test
    fun `fed one byte at a time, the streaming path agrees`() {
        val cmac = AesCmac().apply { init(key) }
        for (i in 0 until 40) cmac.update(message[i])
        assertEquals("dfa66747de9ae63030ca32611497c827", hex(cmac.doFinal()))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
}
