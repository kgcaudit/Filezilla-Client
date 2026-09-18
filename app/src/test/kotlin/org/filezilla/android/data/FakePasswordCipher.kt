package org.filezilla.android.data

import java.util.Base64

/**
 * A stand-in for the keystore, which cannot be driven on a plain JVM.
 *
 * Reversible and obviously not secret, so a test can tell ciphertext from
 * plaintext without pretending to test AES. What the tests here are actually
 * about is the migration and the round trip through the row -- whether the
 * password survives, and whether the plaintext stops existing in the file.
 */
class FakePasswordCipher : PasswordCipher {

    /** Set to make every decrypt fail, as a destroyed keystore key would. */
    var keyIsGone: Boolean = false

    override fun encrypt(plaintext: String): String =
        PREFIX + Base64.getEncoder().encodeToString(plaintext.toByteArray())

    override fun decrypt(stored: String): String? {
        if (keyIsGone) return null
        if (!stored.startsWith(PREFIX)) return null
        return runCatching {
            String(Base64.getDecoder().decode(stored.removePrefix(PREFIX)))
        }.getOrNull()
    }

    private companion object {
        const val PREFIX = "fake:"
    }
}
