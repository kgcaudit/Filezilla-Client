package org.filezilla.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * That a backup round-trips -- the servers and favourites come back as they
 * went in -- and that it is useless without the passphrase.
 *
 * The backup carries the one thing a reinstall cannot recover, with the server
 * passwords in the clear inside it, so two things have to hold: the contents
 * survive the encrypt/decrypt exactly, and a wrong passphrase gets nothing
 * rather than garbage the importer might act on.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsBackupTest {

    // A stand-in keystore cipher: a reversible tag, so the test can see that a
    // credential was decrypted for the file and re-sealed on the way back.
    private val cipher = object : PasswordCipher {
        override fun encrypt(plaintext: String) = "enc:$plaintext"
        override fun decrypt(stored: String) = stored.removePrefix("enc:")
    }

    private fun site(name: String, pos: Int) = SiteEntity(
        id = "id-$name",
        name = name,
        host = "$name.example.com",
        port = 22,
        user = "me",
        passwordCipher = "enc:secret-$name",
        security = "IMPLICIT",
        transferMode = "PASSIVE",
        pinnedCertificate = null,
        protocol = SiteProtocol.SFTP.name,
        knownHostKey = "hostkey-$name",
        privateKeyCipher = "enc:-----BEGIN KEY $name-----",
        initialPath = "/home/$name",
        encoding = null,
        position = pos,
    )

    private val bookmarks = listOf(
        Bookmark(label = "Photos", isLocal = false, path = "/srv/photos", siteId = "id-a"),
        Bookmark(label = "Phone downloads", isLocal = true, path = "/sdcard/Download", siteId = ""),
    )

    @Test
    fun `servers and favourites survive the round trip`() {
        val sites = listOf(site("a", 0), site("b", 1))
        val pass = "correct horse".toCharArray()

        val bytes = SettingsBackup.export(pass.copyOf(), sites, cipher, bookmarks)
        val restored = SettingsBackup.import(bytes, pass.copyOf(), cipher, firstPosition = 5)

        assertEquals(listOf("a", "b"), restored.sites.map { it.name })
        // The password and key came back re-sealed under the keystore tag.
        assertEquals("enc:secret-a", restored.sites[0].passwordCipher)
        assertEquals("enc:-----BEGIN KEY a-----", restored.sites[0].privateKeyCipher)
        assertEquals("SFTP", restored.sites[0].protocol)
        assertEquals("hostkey-b", restored.sites[1].knownHostKey)
        // Renumbered from firstPosition so they land after what is already saved.
        assertEquals(listOf(5, 6), restored.sites.map { it.position })
        // A fresh id, so a restore adds rather than overwriting by a shared id.
        assertNotEquals("id-a", restored.sites[0].id)

        assertEquals(listOf("Photos", "Phone downloads"), restored.bookmarks.map { it.label })
        assertEquals("id-a", restored.bookmarks[0].siteId)
        assertTrue(restored.bookmarks[1].isLocal)
    }

    @Test
    fun `a wrong passphrase is refused rather than decrypted to nonsense`() {
        val bytes = SettingsBackup.export("right".toCharArray(), listOf(site("a", 0)), cipher, emptyList())

        assertThrows(Exception::class.java) {
            SettingsBackup.import(bytes, "wrong".toCharArray(), cipher, firstPosition = 0)
        }
    }

    @Test
    fun `a file that is not a backup is refused`() {
        assertThrows(Exception::class.java) {
            SettingsBackup.import("just some bytes".toByteArray(), "x".toCharArray(), cipher, 0)
        }
    }
}
