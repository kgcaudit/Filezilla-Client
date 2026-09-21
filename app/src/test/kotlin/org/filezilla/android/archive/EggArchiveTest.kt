package org.filezilla.android.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

/**
 * Reading EGG, against the format rather than against one reader.
 *
 * Several of these are cases unegg -- ESTsoft's own extractor -- cannot
 * read: a name in a code page, a name relative to another entry, posix
 * file information, a chunk from a later version. The specification
 * describes all four and asks readers to cope with all four, so they are
 * here. A test written only from unegg would have agreed with unegg's
 * blind spots, which is how the alz reader was wrong for a fortnight.
 */
class EggArchiveTest {

    private fun fixture(name: String) = File("src/test/resources/archives/$name")

    private fun contents(archive: Archive, path: String, password: CharArray? = null): String =
        archive.open(archive.entries.first { it.path == path }, password)
            .use { String(it.readBytes()) }

    @Test
    fun `an egg is recognised by its first four bytes`() {
        assertTrue(EggArchive.looksLikeEgg(fixture("plain.egg")))
        assertFalse(EggArchive.looksLikeEgg(fixture("plain.alz")))
        assertFalse(EggArchive.looksLikeEgg(fixture("plain.zip")))
    }

    @Test
    fun `stored and deflated entries both come back whole`() {
        EggArchive.open(fixture("plain.egg")).use { egg ->
            assertEquals(listOf("hello.txt", "stored.txt"), egg.entries.map { it.path })
            assertEquals("hello from an egg\n".repeat(40), contents(egg, "hello.txt"))
            assertEquals("not compressed at all\n", contents(egg, "stored.txt"))
        }
    }

    @Test
    fun `the specification's own worked example reads as it says`() {
        // Section 3 of the published specification, byte for byte off the
        // page: "hello.txt", five bytes, "hello", stored. Every other
        // fixture here was written by make_egg.py from the same reading
        // of the format as the reader, so they would all agree with a
        // misreading. This one cannot.
        EggArchive.open(fixture("spec_example.egg")).use { egg ->
            val entry = egg.entries.single()
            assertEquals("hello.txt", entry.path)
            assertEquals(5L, entry.size)
            assertEquals("hello", contents(egg, "hello.txt"))
            // 0x01C7FB634FA3C923 ticks from 1601, which is this instant.
            assertEquals(1190278235981L, entry.modifiedMillis)
        }
    }

    @Test
    fun `a real ALZip egg reads, posix header and all`() {
        // Made by ALZip minutes before it was added here, and it turns out
        // to carry the *posix* file information header -- mode, uid, gid,
        // seconds since 1970 -- and no Windows one at all. unegg has no
        // code for that header: its file-header loop has cases for the
        // filename, the comment, the Windows information and the encrypt
        // header, and everything else is ERROR_BAD_FORMAT, with the posix
        // structure commented out in its own header file as "to be
        // introduced after analysis". So ESTsoft's own extractor cannot
        // read this file, and this app can only because the published
        // specification describes the header that unegg does not.
        EggArchive.open(fixture("alzip_plain.egg")).use { egg ->
            val entry = egg.entries.single()
            assertEquals("egg.txt", entry.path)
            assertEquals(3L, entry.size)
            assertFalse(entry.isDirectory)
            assertFalse(entry.encrypted)
            assertNull(entry.unreadable)
            assertEquals("egg", contents(egg, "egg.txt"))
            // 0x6AB125CC seconds, not FILETIME ticks: the posix header
            // counts from 1970 and the windows one from 1601, and reading
            // one as the other lands in the wrong millennium.
            assertEquals(1789994444_000L, entry.modifiedMillis)
        }
    }

    @Test
    fun `a real ALZip egg with a korean folder keeps the name and grows the folder`() {
        // The name is "느시/egg.txt" in UTF-8 with the code-page flag
        // clear, and there is no folder entry: the folder exists only
        // because a file's name mentions it.
        EggArchive.open(fixture("alzip_korean.egg")).use { egg ->
            assertEquals(listOf("느시/egg.txt"), egg.entries.map { it.path })
            assertEquals("egg", contents(egg, "느시/egg.txt"))
            val top = ArchiveBrowsing.rowsIn(egg.entries)
            assertEquals(listOf("느시/"), top.map { it.path })
            assertEquals(1, top.single().count)
        }
    }

    @Test
    fun `a real ALZip egg locks with the cipher this app has`() {
        // ALZip on a phone locks with the format's oldest cipher -- the
        // one the specification calls "key base XOR" and the alz reader
        // already has -- rather than with the AES or LEA it also allows.
        // Checked on three archives it made: this one, a 5 MB stored mp3
        // and a 22 MB deflated mp4. So a locked egg is listed as openable
        // rather than marked out of reach, which is the whole difference
        // between asking for a password and refusing the file.
        EggArchive.open(fixture("alzip_secret.egg")).use { egg ->
            val entry = egg.entries.single()
            assertEquals("꾀꼬리.egg", entry.path)
            assertEquals(117L, entry.size)
            assertTrue(entry.encrypted)
            assertNull(entry.unreadable)
        }
    }

    @Test
    fun `a real ALZip egg refuses a password that is not its own`() {
        // The check the format provides, on a file somebody really made:
        // a key that does not open it stops here rather than handing back
        // 117 bytes of noise for the screen to save as a file.
        EggArchive.open(fixture("alzip_secret.egg")).use { egg ->
            val entry = egg.entries.single()
            assertThrows(WrongPassword::class.java) { egg.open(entry, "nonsense".toCharArray()) }
            assertThrows(WrongPassword::class.java) { egg.open(entry) }
        }
    }

    @Test
    fun `a name with no code page is read as UTF-8`() {
        EggArchive.open(fixture("korean.egg")).use { egg ->
            assertEquals(listOf("한글이름.txt"), egg.entries.map { it.path })
            assertEquals("내용입니다\n".repeat(20), contents(egg, "한글이름.txt"))
        }
    }

    @Test
    fun `a name that asks for CP949 is read as CP949`() {
        // The flag bit and the locale number that go with it. Read as
        // UTF-8 -- which is what unegg does to every name it accepts --
        // this comes out as mojibake or throws.
        EggArchive.open(fixture("korean_cp949.egg")).use { egg ->
            assertEquals(listOf("한글이름.txt"), egg.entries.map { it.path })
            assertEquals("내용입니다\n".repeat(20), contents(egg, "한글이름.txt"))
        }
    }

    @Test
    fun `a name relative to another entry is joined to it`() {
        EggArchive.open(fixture("relative.egg")).use { egg ->
            assertEquals(listOf("papers/", "papers/inside.txt"), egg.entries.map { it.path })
            assertEquals("hello from an egg\n".repeat(40), contents(egg, "papers/inside.txt"))
        }
    }

    @Test
    fun `a folder is a folder and carries no size`() {
        EggArchive.open(fixture("folder.egg")).use { egg ->
            val folder = egg.entries.first { it.path == "papers/" }
            assertTrue(folder.isDirectory)
            assertEquals(-1, folder.size)
            assertFalse(egg.entries.first { it.path == "papers/inside.txt" }.isDirectory)
        }
    }

    @Test
    fun `posix file information says which entry is a folder`() {
        EggArchive.open(fixture("posix.egg")).use { egg ->
            assertTrue(egg.entries.first { it.path == "scripts/" }.isDirectory)
            val script = egg.entries.first { it.path == "scripts/run.sh" }
            assertFalse(script.isDirectory)
            assertEquals(1590997000_000L, script.modifiedMillis)
        }
    }

    @Test
    fun `a file split into several blocks comes back joined`() {
        // What an egg does and a zip cannot: one file compressed as more
        // than one run. Reading only the first block would give a file
        // that looks plausible and is short.
        EggArchive.open(fixture("multiblock.egg")).use { egg ->
            val expected = "hello from an egg\n".repeat(40) +
                "the quick brown fox jumps over the lazy dog\n".repeat(30)
            assertEquals(expected, contents(egg, "long.txt"))
            assertEquals(expected.length.toLong(), egg.entries.single().size)
        }
    }

    @Test
    fun `a solid archive gives each file its own slice`() {
        // One block for the lot. The second file's bytes can only be had
        // by decompressing past the first, and a reader that hands back
        // the whole block for each entry is wrong twice over.
        EggArchive.open(fixture("solid.egg")).use { egg ->
            assertEquals(listOf("first.txt", "second.txt"), egg.entries.map { it.path })
            assertEquals("first file\n", contents(egg, "first.txt"))
            assertEquals("second file\n", contents(egg, "second.txt"))
        }
    }

    @Test
    fun `a chunk from a later version is stepped over`() {
        // The specification's forward compatibility: handle what you know
        // and skip the rest. Refusing here would mean every archive a
        // newer ALZip writes stops working.
        EggArchive.open(fixture("unknown_chunk.egg")).use { egg ->
            assertEquals(listOf("hello.txt"), egg.entries.map { it.path })
            assertEquals("hello from an egg\n".repeat(40), contents(egg, "hello.txt"))
        }
    }

    @Test
    fun `an encrypted entry opens with its password`() {
        EggArchive.open(fixture("secret.egg")).use { egg ->
            val entry = egg.entries.single()
            assertTrue(entry.encrypted)
            assertNull(entry.unreadable)
            assertEquals(
                "the quick brown fox jumps over the lazy dog\n".repeat(30),
                contents(egg, "secret.txt", "alzip".toCharArray()),
            )
        }
    }

    @Test
    fun `the wrong password is refused rather than answered with rubbish`() {
        EggArchive.open(fixture("secret.egg")).use { egg ->
            val entry = egg.entries.single()
            assertThrows(WrongPassword::class.java) { egg.open(entry, "wrong".toCharArray()) }
            assertThrows(WrongPassword::class.java) { egg.open(entry) }
        }
    }

    @Test
    fun `an entry in a method this app does not read is listed and marked`() {
        // The point of marking rather than refusing: the readable entry
        // beside it is still readable.
        EggArchive.open(fixture("azo.egg")).use { egg ->
            val squeezed = egg.entries.first { it.path == "squeezed.txt" }
            assertEquals(ArchiveEntry.Unreadable.COMPRESSION_METHOD, squeezed.unreadable)
            assertThrows(NotAnArchive::class.java) { egg.open(squeezed) }
            assertNull(egg.entries.first { it.path == "readable.txt" }.unreadable)
            assertEquals(
                "the quick brown fox jumps over the lazy dog\n".repeat(30),
                contents(egg, "readable.txt"),
            )
        }
    }

    @Test
    fun `a time is the same instant wherever the phone is`() {
        // FILETIME is UTC, unlike the DOS time in alz and zip, so this is
        // the one archive format whose dates do not move with the phone.
        val original = TimeZone.getDefault()
        try {
            val seen = listOf("UTC", "Asia/Seoul", "America/Los_Angeles").map { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                EggArchive.open(fixture("plain.egg")).use { it.entries.first().modifiedMillis }
            }
            assertNotNull(seen.first())
            assertEquals(1, seen.toSet().size)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `something that is not an egg is refused`() {
        assertThrows(NotAnArchive::class.java) { EggArchive.open(fixture("plain.zip")) }
        assertThrows(NotAnArchive::class.java) { EggArchive.open(fixture("nothing.egg")) }
    }
}
