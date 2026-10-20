package org.filezilla.android.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A password-protected export of the saved servers and favourites.
 *
 * The one thing on this device that cannot be downloaded again: the servers a
 * person has set up, and the folders they bookmarked. They live in a database
 * keyed to this install, so a new phone -- or a reinstall -- starts from
 * nothing. This writes them to a file the user keeps.
 *
 * The file is encrypted under a passphrase the user chooses, not the device
 * keystore, precisely so it can leave the device and be opened on another one.
 * Inside, the server passwords and SSH keys are in the clear -- that is the
 * point of a backup -- so the passphrase is the whole of its protection:
 * PBKDF2 stretches it into a key, and AES-GCM seals the contents under that.
 * A wrong passphrase does not decrypt to garbage; GCM's tag rejects it, and the
 * import fails cleanly.
 */
object SettingsBackup {

    /** A backup made with these servers and bookmarks, as encrypted bytes. */
    fun export(
        passphrase: CharArray,
        sites: List<SiteEntity>,
        passwords: PasswordCipher,
        bookmarks: List<Bookmark>,
    ): ByteArray {
        val root = JSONObject()
        root.put("version", FORMAT_VERSION)

        val siteArray = JSONArray()
        for (site in sites) {
            siteArray.put(
                JSONObject().apply {
                    put("name", site.name)
                    put("host", site.host)
                    put("port", site.port)
                    put("user", site.user)
                    // Decrypted here, to be re-sealed under the keystore on the
                    // machine it is restored to.
                    put("password", passwords.decrypt(site.passwordCipher).orEmpty())
                    put("security", site.security)
                    put("transferMode", site.transferMode)
                    put("protocol", site.protocol)
                    putOpt("pinnedCertificate", site.pinnedCertificate)
                    putOpt("knownHostKey", site.knownHostKey)
                    putOpt("privateKey", site.privateKeyCipher?.let { passwords.decrypt(it) })
                    putOpt("initialPath", site.initialPath)
                    putOpt("encoding", site.encoding)
                    put("position", site.position)
                },
            )
        }
        root.put("sites", siteArray)

        val bookmarkArray = JSONArray()
        for (mark in bookmarks) {
            bookmarkArray.put(
                JSONObject().apply {
                    put("label", mark.label)
                    put("isLocal", mark.isLocal)
                    put("path", mark.path)
                    putOpt("siteId", mark.siteId)
                },
            )
        }
        root.put("bookmarks", bookmarkArray)

        return seal(root.toString().toByteArray(Charsets.UTF_8), passphrase)
    }

    /** What a restore produced, ready to be written to the database. */
    data class Restored(val sites: List<SiteEntity>, val bookmarks: List<Bookmark>)

    /**
     * Opens a backup [bytes] with [passphrase], re-sealing each credential under
     * this device's keystore via [passwords] and renumbering the servers from
     * [firstPosition] so they land after whatever is already saved. Throws when
     * the passphrase is wrong or the file is not a backup.
     */
    fun import(
        bytes: ByteArray,
        passphrase: CharArray,
        passwords: PasswordCipher,
        firstPosition: Int,
    ): Restored {
        val json = JSONObject(String(open(bytes, passphrase), Charsets.UTF_8))

        val sites = mutableListOf<SiteEntity>()
        val siteArray = json.optJSONArray("sites") ?: JSONArray()
        for (i in 0 until siteArray.length()) {
            val o = siteArray.getJSONObject(i)
            val privateKey = o.optString("privateKey", "").ifBlank { null }
            sites += SiteEntity(
                // A fresh id, so a restore onto a device that still has the old
                // rows adds rather than silently overwriting by a shared id.
                id = UUID.randomUUID().toString(),
                name = o.optString("name"),
                host = o.optString("host"),
                port = o.optInt("port"),
                user = o.optString("user"),
                passwordCipher = passwords.encrypt(o.optString("password", "")),
                security = o.optString("security"),
                transferMode = o.optString("transferMode"),
                pinnedCertificate = o.optString("pinnedCertificate", "").ifBlank { null },
                protocol = o.optString("protocol", SiteProtocol.FTP.name),
                knownHostKey = o.optString("knownHostKey", "").ifBlank { null },
                privateKeyCipher = privateKey?.let { passwords.encrypt(it) },
                initialPath = o.optString("initialPath", "").ifBlank { null },
                encoding = o.optString("encoding", "").ifBlank { null },
                position = firstPosition + i,
            )
        }

        val bookmarks = mutableListOf<Bookmark>()
        val bookmarkArray = json.optJSONArray("bookmarks") ?: JSONArray()
        for (i in 0 until bookmarkArray.length()) {
            val o = bookmarkArray.getJSONObject(i)
            bookmarks += Bookmark(
                label = o.optString("label"),
                isLocal = o.optBoolean("isLocal"),
                path = o.optString("path"),
                // Empty for a phone folder; a server id otherwise.
                siteId = o.optString("siteId", ""),
            )
        }

        return Restored(sites, bookmarks)
    }

    // ------------------------------------------------------------- crypto

    private fun seal(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keyFrom(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        val ciphertext = cipher.doFinal(plaintext)
        return MAGIC + salt + iv + ciphertext
    }

    private fun open(bytes: ByteArray, passphrase: CharArray): ByteArray {
        require(bytes.size > MAGIC.size + SALT_BYTES + IV_BYTES) { "not a backup file" }
        require(bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) { "not a backup file" }
        var offset = MAGIC.size
        val salt = bytes.copyOfRange(offset, offset + SALT_BYTES).also { offset += SALT_BYTES }
        val iv = bytes.copyOfRange(offset, offset + IV_BYTES).also { offset += IV_BYTES }
        val ciphertext = bytes.copyOfRange(offset, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, keyFrom(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        // Throws AEADBadTagException on a wrong passphrase; the caller turns
        // that into a clean "wrong password or damaged file".
        return cipher.doFinal(ciphertext)
    }

    private fun keyFrom(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ROUNDS, KEY_BITS)
        val bits = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bits, "AES")
    }

    // "OLOBAK" + a format byte, so a future change to the layout is told apart
    // from this one rather than decrypted as if it were it.
    private val MAGIC = byteArrayOf('O'.code.toByte(), 'L'.code.toByte(), 'O'.code.toByte(), 'B'.code.toByte(), 'A'.code.toByte(), 'K'.code.toByte(), 1)
    private const val FORMAT_VERSION = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val PBKDF2_ROUNDS = 120_000
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
}
