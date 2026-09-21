package org.filezilla.android.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reading ALZ, against archives the reference implementation agrees with.
 *
 * The archives in `src/test/resources/archives` are written by
 * `make_alz.py` from the format as unalz describes it, and the real unalz
 * -- built from the source this was ported from -- lists and extracts
 * every one of them correctly, passwords included. So they are not this
 * code marking its own homework: two independent readers agree on what
 * they contain.
 *
 * What they are not is an archive made by ALZip itself. That gap closes
 * when a real one turns up, and until then it is stated rather than
 * papered over.
 */
class AlzArchiveTest {

    private fun fixture(name: String) = File("src/test/resources/archives/$name")

    private fun contents(archive: Archive, path: String, password: CharArray? = null): String =
        archive.open(archive.entries.first { it.path == path }, password)
            .use { String(it.readBytes()) }

    @Test
    fun `an alz is recognised by its first four bytes`() {
        assertTrue(AlzArchive.looksLikeAlz(fixture("plain.alz")))
        assertTrue(!AlzArchive.looksLikeAlz(fixture("make_alz.py")))
    }

    @Test
    fun `stored and deflated entries both come back whole`() {
        AlzArchive.open(fixture("plain.alz")).use { alz ->
            assertEquals(listOf("hello.txt", "deflated.txt"), alz.entries.map { it.path })
            assertEquals("hello from alz\n", contents(alz, "hello.txt"))
            assertEquals("repeat ".repeat(500), contents(alz, "deflated.txt"))
        }
    }

    @Test
    fun `sizes are what the archive says, not what came out`() {
        AlzArchive.open(fixture("plain.alz")).use { alz ->
            val deflated = alz.entries.first { it.path == "deflated.txt" }
            assertEquals(3500, deflated.size)
            // The point of showing both: a list that only had the
            // uncompressed size would not say what the download costs.
            assertTrue("compressed size was not recorded", deflated.compressedSize in 1..100)
        }
    }

    @Test
    fun `korean names are read as CP949, which is all the format has`() {
        AlzArchive.open(fixture("korean.alz")).use { alz ->
            assertEquals(
                listOf("한글파일.txt", "사진/", "사진/여름 휴가.jpg", "보고서(최종).hwp"),
                alz.entries.map { it.path },
            )
            assertEquals("한글 내용입니다\n", contents(alz, "한글파일.txt"))
            assertEquals("hwp body", contents(alz, "보고서(최종).hwp"))
        }
    }

    @Test
    fun `a folder is a folder, not an empty file`() {
        AlzArchive.open(fixture("korean.alz")).use { alz ->
            val folder = alz.entries.first { it.path == "사진/" }
            assertTrue(folder.isDirectory)
            assertEquals("사진", folder.name)
            // No sizes are stored for one, and inventing a zero would be
            // claiming the archive said something it did not.
            assertEquals(-1, folder.size)
        }
    }

    @Test
    fun `a name knows the folder it is in`() {
        AlzArchive.open(fixture("korean.alz")).use { alz ->
            val photo = alz.entries.first { it.name == "여름 휴가.jpg" }
            assertEquals("사진", photo.parent)
            assertEquals("", alz.entries.first { it.path == "한글파일.txt" }.parent)
        }
    }

    @Test
    fun `the right password opens an entry`() {
        AlzArchive.open(fixture("secret.alz")).use { alz ->
            assertTrue(alz.entries.single().encrypted)
            assertEquals(
                "password protected content\n",
                contents(alz, "secret.txt", "bimil".toCharArray()),
            )
        }
    }

    @Test
    fun `a wrong password is refused rather than answered with rubbish`() {
        AlzArchive.open(fixture("secret.alz")).use { alz ->
            // These formats encrypt without authenticating, so a wrong key
            // decrypts to something -- and handing that back as the file
            // would be worse than saying no.
            assertThrows(WrongPassword::class.java) {
                contents(alz, "secret.txt", "wrong".toCharArray())
            }
        }
    }

    @Test
    fun `no password at all is refused the same way`() {
        AlzArchive.open(fixture("secret.alz")).use { alz ->
            assertThrows(WrongPassword::class.java) { contents(alz, "secret.txt") }
        }
    }

    @Test
    fun `an entry in a method we cannot read is listed, not hidden`() {
        AlzArchive.open(fixture("hasbzip2.alz")).use { alz ->
            assertEquals(2, alz.entries.size)

            val readable = alz.entries.first { it.path == "readable.txt" }
            assertNull(readable.unreadable)
            assertEquals("this one is deflate\n", contents(alz, "readable.txt"))

            // Refusing the whole archive over one entry would hide the
            // others, which are what somebody opened it for.
            val other = alz.entries.first { it.path == "maxpack.bin" }
            assertEquals(ArchiveEntry.Unreadable.COMPRESSION_METHOD, other.unreadable)
            assertThrows(NotAnArchive::class.java) { contents(alz, "maxpack.bin") }
        }
    }

    @Test
    fun `an archive split across volumes reads as one`() {
        // ALZip splits into .alz, .a00, .a01 … and an entry is free to
        // begin in one and end in the next, so the parts are joined before
        // anything above tries to read a run of bytes.
        AlzArchive.open(fixture("split.alz")).use { alz ->
            assertEquals(listOf("hello.txt", "deflated.txt"), alz.entries.map { it.path })
            assertEquals("hello from alz\n", contents(alz, "hello.txt"))
            assertEquals("repeat ".repeat(500), contents(alz, "deflated.txt"))
        }
    }

    @Test
    fun `the volumes are found in order`() {
        assertEquals(
            listOf("split.alz", "split.a00"),
            AlzArchive.partsOf(fixture("split.alz")).map { it.name },
        )
        assertEquals(listOf("plain.alz"), AlzArchive.partsOf(fixture("plain.alz")).map { it.name })
    }

    @Test
    fun `something that is not an alz says so`() {
        assertThrows(NotAnArchive::class.java) { AlzArchive.open(fixture("make_alz.py")) }
    }

    @Test
    fun `dos time is read as a real date`() {
        AlzArchive.open(fixture("plain.alz")).use { alz ->
            val at = alz.entries.first().modifiedMillis!!
            val calendar = java.util.GregorianCalendar().apply { timeInMillis = at }
            assertEquals(2024, calendar.get(java.util.Calendar.YEAR))
            assertEquals(3, calendar.get(java.util.Calendar.MONTH) + 1)
            assertEquals(15, calendar.get(java.util.Calendar.DAY_OF_MONTH))
            assertEquals(13, calendar.get(java.util.Calendar.HOUR_OF_DAY))
            assertEquals(45, calendar.get(java.util.Calendar.MINUTE))
            // Seconds are stored in units of two, so twenty survives exactly.
            assertEquals(20, calendar.get(java.util.Calendar.SECOND))
        }
    }
}
