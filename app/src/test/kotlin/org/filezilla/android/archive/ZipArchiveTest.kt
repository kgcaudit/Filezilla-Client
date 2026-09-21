package org.filezilla.android.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reading zip, and the one thing that decides whether it is worth reading.
 *
 * A zip entry's name is bytes plus a flag. Bit 11 set means UTF-8; clear
 * means whatever the machine that wrote it used, which in Korea has been
 * CP949 for twenty years. Both kinds are here, written byte by byte so
 * the flag says exactly what is intended, because the popular libraries
 * get exactly this wrong -- zip4j applies its charset to everything and
 * ignores the flag, so it reads one kind or the other and never both.
 */
class ZipArchiveTest {

    private fun fixture(name: String) = File("src/test/resources/archives/$name")

    private fun contents(archive: Archive, path: String): String =
        archive.open(archive.entries.first { it.path == path }).use { String(it.readBytes()) }

    @Test
    fun `a zip64 archive opens through its zip64 end record`() {
        // The tail a zip over four gigabytes has: the ordinary end record
        // carries 0xFFFFFFFF where the central directory offset would be,
        // and the real offset is in the zip64 end record the locator
        // points at. A reader that stops at the ordinary record reads the
        // placeholder as an offset and calls the central directory
        // out-of-bounds -- which is the tap on a big comic zip that showed
        // nothing.
        ZipArchive.open(fixture("zip64.zip")).use { zip ->
            assertEquals(listOf("page.txt"), zip.entries.map { it.path })
            assertEquals(
                "hello from a zip64 archive\n".repeat(3),
                contents(zip, "page.txt"),
            )
        }
    }

    @Test
    fun `a zip is recognised by its first four bytes`() {
        assertTrue(ZipArchive.looksLikeZip(fixture("plain.zip")))
        assertFalse(ZipArchive.looksLikeZip(fixture("plain.alz")))
    }

    @Test
    fun `stored and deflated entries both come back whole`() {
        ZipArchive.open(fixture("plain.zip")).use { zip ->
            assertEquals(listOf("hello.txt", "deflated.txt"), zip.entries.map { it.path })
            assertEquals("hello from zip\n", contents(zip, "hello.txt"))
            assertEquals("repeat ".repeat(500), contents(zip, "deflated.txt"))
        }
    }

    @Test
    fun `a korean zip with no flag is read as CP949`() {
        // What Korean Windows makes. Read as UTF-8 the names are nothing
        // at all, which is the mojibake everybody recognises.
        ZipArchive.open(fixture("korean_cp949.zip")).use { zip ->
            assertEquals(
                listOf("한글파일.txt", "사진/", "사진/여름 휴가.jpg", "보고서(최종).hwp"),
                zip.entries.map { it.path },
            )
            assertEquals("한글 내용입니다\n", contents(zip, "한글파일.txt"))
        }
    }

    @Test
    fun `a korean zip that flags UTF-8 is read as UTF-8`() {
        // The same archive the modern way. Both have to work, and this is
        // the half a fixed CP949 reader breaks.
        ZipArchive.open(fixture("korean_utf8.zip")).use { zip ->
            assertEquals(
                listOf("한글파일.txt", "사진/", "사진/여름 휴가.jpg", "보고서(최종).hwp"),
                zip.entries.map { it.path },
            )
            assertEquals("한글 내용입니다\n", contents(zip, "한글파일.txt"))
        }
    }

    @Test
    fun `the fallback never overrules the flag`() {
        // Told to read names as something else entirely, a flagged
        // archive is still UTF-8: the flag is the archive saying what it
        // did, and it wins over anything assumed from outside.
        ZipArchive.open(fixture("korean_utf8.zip"), Charsets.ISO_8859_1).use { zip ->
            assertEquals("한글파일.txt", zip.entries.first().path)
        }
    }

    @Test
    fun `a folder entry is a folder`() {
        ZipArchive.open(fixture("korean_cp949.zip")).use { zip ->
            val folder = zip.entries.first { it.path == "사진/" }
            assertTrue(folder.isDirectory)
            assertEquals("사진", folder.name)
            assertEquals("사진", zip.entries.first { it.name == "여름 휴가.jpg" }.parent)
        }
    }

    @Test
    fun `sizes are what the archive recorded`() {
        ZipArchive.open(fixture("plain.zip")).use { zip ->
            val deflated = zip.entries.first { it.path == "deflated.txt" }
            assertEquals(3500, deflated.size)
            assertTrue(deflated.compressedSize in 1..100)
        }
    }

    @Test
    fun `an encrypted entry is marked, not left to fail later`() {
        // java.util.zip has no decryption. Saying so in the list is the
        // difference between "this app cannot open that one" and an
        // archive that looks broken.
        ZipArchive.open(fixture("encrypted.zip")).use { zip ->
            val entry = zip.entries.single()
            assertTrue("the flag said encrypted", entry.encrypted)
            assertEquals(ArchiveEntry.Unreadable.ENCRYPTED_METHOD, entry.unreadable)
            assertThrows(WrongPassword::class.java) { contents(zip, "secret.txt") }
        }
    }

    @Test
    fun `an ordinary entry is not mistaken for an encrypted one`() {
        ZipArchive.open(fixture("plain.zip")).use { zip ->
            assertTrue(zip.entries.none { it.encrypted })
            assertTrue(zip.entries.none { it.unreadable != null })
        }
    }

    @Test
    fun `something that is not a zip says so`() {
        assertThrows(NotAnArchive::class.java) { ZipArchive.open(fixture("plain.alz")) }
    }

    @Test
    fun `a zip and an alz are not confused for each other`() {
        assertFalse(ZipArchive.looksLikeZip(fixture("alzip_korean.alz")))
        assertFalse(AlzArchive.looksLikeAlz(fixture("plain.zip")))
    }
}
