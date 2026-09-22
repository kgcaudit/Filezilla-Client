package org.filezilla.android.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reading a tar, and the comic archives that are tars underneath.
 *
 * Unlike 7z and rar, tar is not compressed, so this is a pure-Kotlin reader
 * and its listing and extraction run right here rather than only on a phone.
 */
class TarArchiveTest {

    private fun fixture(name: String) = File("src/test/resources/archives/$name")

    @Test
    fun `a tar is recognised by its ustar magic`() {
        assertTrue(TarArchive.looksLikeTar(fixture("plain.tar")))
        assertFalse(TarArchive.looksLikeTar(fixture("plain.zip")))
        assertFalse(TarArchive.looksLikeTar(fixture("plain.egg")))
    }

    @Test
    fun `it lists files, folders and korean names`() {
        val entries = TarArchive.open(fixture("plain.tar")).entries
        val files = entries.filter { !it.isDirectory }.map { it.path }
        assertTrue("pages/001.txt" in files)
        assertTrue("pages/002.txt" in files)
        assertTrue("메모.txt" in files)
    }

    @Test
    fun `it reads an entry's bytes back exactly`() {
        val tar = TarArchive.open(fixture("plain.tar"))
        val entry = tar.entries.first { it.path == "pages/002.txt" }
        val text = tar.open(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals("second page\n", text)
    }

    @Test
    fun `a GNU long name is read whole`() {
        val tar = TarArchive.open(fixture("longname.tar"))
        val entry = tar.entries.first { !it.isDirectory }
        assertTrue("the 200-plus character name should survive", entry.path.length > 100)
        assertEquals("deep\n", tar.open(entry).use { it.readBytes().toString(Charsets.UTF_8) })
    }

    @Test
    fun `the comic extensions are browsable archives`() {
        for (name in listOf("story.cbz", "story.cbr", "story.cb7", "story.cbt")) {
            assertTrue(name, ArchiveNav.browsable(name))
            assertTrue(name, Archives.looksLikeArchive(name))
        }
    }

    @Test
    fun `a cbt is opened as the tar it is, by its bytes`() {
        // The comic extension only decides whether to look inside; the kind is
        // read from the bytes. A tar renamed .cbt opens through TarArchive.
        val cbt = File.createTempFile("story", ".cbt").apply {
            fixture("plain.tar").copyTo(this, overwrite = true)
            deleteOnExit()
        }
        assertEquals(Archives.Kind.TAR, Archives.kindOf(cbt))
        assertTrue(Archives.open(cbt).entries.any { it.path == "메모.txt" })
    }
}
