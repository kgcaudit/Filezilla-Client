package org.filezilla.ftp.smb.crypto

import com.hierynomus.security.AEADBlockCipher
import com.hierynomus.security.Cipher
import com.hierynomus.security.DerivationFunction
import com.hierynomus.security.Mac
import com.hierynomus.security.MessageDigest
import com.hierynomus.security.SecurityProvider
import com.hierynomus.security.jce.JceSecurityProvider

/**
 * The crypto smbj uses, chosen so SMB 3 runs on Android with no BouncyCastle.
 *
 * Almost everything SMB needs is in Android's own JCE, so this hands almost
 * everything to smbj's [JceSecurityProvider]: HMAC-SHA256 signing (SMB 2.x),
 * AES-GCM encryption (SMB 3.1.1), the SP800-108 key-derivation, SHA-512 pre-auth
 * integrity, and the NTLM digests. Only the two primitives Android's JCE lacks
 * are supplied here, built on the platform's raw AES block cipher:
 *
 *  - **AES-CMAC** ([AesCmac]) for SMB 3 message signing, requested as `AesCmac`.
 *  - **AES-CCM** ([AesCcmCipher]) for SMB 3.0/3.0.2 encryption, requested as
 *    `AES/CCM/NoPadding`. (SMB 3.1.1's `AES/GCM/NoPadding` goes to the JCE.)
 *
 * The point of doing it this way rather than with smbj's BouncyCastle provider
 * is set out in [org.filezilla.ftp.smb.SmbClient]: BouncyCastle's crypto is
 * shadowed by the platform's stripped copy on older Android and crashes. Nothing
 * here touches BouncyCastle.
 */
class AndroidSmbSecurityProvider : SecurityProvider {

    private val jce = JceSecurityProvider()

    override fun getDigest(name: String): MessageDigest = jce.getDigest(name)

    override fun getMac(name: String): Mac =
        if (name.equals("AesCmac", ignoreCase = true)) AesCmac() else jce.getMac(name)

    override fun getCipher(name: String): Cipher = jce.getCipher(name)

    override fun getAEADBlockCipher(name: String): AEADBlockCipher =
        if (name.contains("CCM", ignoreCase = true)) AesCcmCipher() else jce.getAEADBlockCipher(name)

    override fun getDerivationFunction(name: String): DerivationFunction = jce.getDerivationFunction(name)
}
