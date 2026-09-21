package org.filezilla.android.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unpacking and packing: what the screen does once somebody taps.
 *
 * All of it away from Android, because the parts worth getting right --
 * where a file lands, what happens to a name that climbs out of the
 * chosen folder, whether a zip written into the folder it is zipping eats
 * itself -- are decided here and not in the Compose.
 */
class ArchiveWorkTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun fixture(name: String) = File("src/test/resources/archives/$name")

    @Test
    fun `a file is opened as what it is, not as what it is called`() {
        assertEquals(Archives.Kind.ZIP, Archives.kindOf(fixture("plain.zip")))
        assertEquals(Archives.Kind.ALZ, Archives.kindOf(fixture("plain.alz")))
        assertEquals(Archives.Kind.EGG, Archives.kindOf(fixture("plain.egg")))

        // The same bytes under the wrong name still open as themselves.
        val misnamed = temporary.newFile("photos.zip")
        fixture("plain.egg").copyTo(misnamed, overwrite = true)
        assertEquals(Archives.Kind.EGG, Archives.kindOf(misnamed))
        Archives.open(misnamed).use { assertEquals("hello.txt", it.entries.first().path) }
    }

    @Test
    fun `a name is only ever a reason to look`() {
        assertTrue(Archives.looksLikeArchive("holiday.ALZ"))
        assertTrue(Archives.looksLikeArchive("한글.egg"))
        assertFalse(Archives.looksLikeArchive("notes.txt"))
        assertFalse(Archives.looksLikeArchive("zip"))
    }

    @Test
    fun `unpacking makes the folders the names ask for`() {
        // The real ALZip lesson: a folder archive contains no directory
        // records at all, only files with folders in their names.
        val into = temporary.newFolder("out")
        Archives.open(fixture("alzip_folder.alz")).use { archive ->
            val result = ArchiveExtract.run(archive, into)
            assertTrue(result.skipped.isEmpty())
            assertTrue(result.written.isNotEmpty())
            for (file in result.written) {
                assertTrue("$file is not under $into", file.canonicalPath.startsWith(into.canonicalPath))
                assertTrue("$file was not written", file.isFile)
            }
        }
    }

    @Test
    fun `a name that climbs out of the folder is skipped and reported`() {
        val into = temporary.newFolder("out")
        val outside = File(into.parentFile, "stolen.txt")
        val archive = FakeArchive(
            ArchiveEntry("../stolen.txt"),
            ArchiveEntry("kept.txt"),
        )
        val result = ArchiveExtract.run(archive, into)

        assertFalse("a file was written outside the chosen folder", outside.exists())
        assertEquals(listOf("kept.txt"), result.written.map { it.name })
        assertEquals(
            listOf(ExtractResult.Skipped("../stolen.txt", ExtractResult.Reason.ESCAPES)),
            result.skipped,
        )
    }

    @Test
    fun `one bad entry does not take the readable ones with it`() {
        val into = temporary.newFolder("out")
        Archives.open(fixture("azo.egg")).use { archive ->
            val result = ArchiveExtract.run(archive, into)
            assertEquals(listOf("readable.txt"), result.written.map { it.name })
            assertEquals(
                listOf(ExtractResult.Skipped("squeezed.txt", ExtractResult.Reason.UNREADABLE)),
                result.skipped,
            )
        }
    }

    @Test
    fun `a wrong password skips the entry rather than writing rubbish`() {
        val into = temporary.newFolder("out")
        Archives.open(fixture("secret.egg")).use { archive ->
            assertTrue(ArchiveExtract.needsPassword(archive))
            val result = ArchiveExtract.run(archive, into, password = "wrong".toCharArray())
            assertTrue(result.written.isEmpty())
            assertEquals(ExtractResult.Reason.PASSWORD, result.skipped.single().reason)
            assertFalse(File(into, "secret.txt").exists())

            val opened = ArchiveExtract.run(archive, into, password = "alzip".toCharArray())
            assertTrue(opened.skipped.isEmpty())
            assertEquals(
                "the quick brown fox jumps over the lazy dog\n".repeat(30),
                File(into, "secret.txt").readText(),
            )
        }
    }

    @Test
    fun `picking a folder brings what is under it`() {
        val into = temporary.newFolder("out")
        Archives.open(fixture("folder.egg")).use { archive ->
            val result = ArchiveExtract.run(archive, into, picks = listOf("papers/"))
            assertEquals(listOf("inside.txt"), result.written.map { it.name })
        }
    }

    @Test
    fun `a stopped extract keeps the files already finished`() {
        val into = temporary.newFolder("out")
        Archives.open(fixture("plain.egg")).use { archive ->
            // Stop once the second file has been opened -- so the first is
            // finished and kept, and the one caught mid-write is deleted
            // rather than left half-formed.
            val second = File(into, "stored.txt")
            val result = ArchiveExtract.run(archive, into, cancelled = { second.exists() })
            assertTrue(result.cancelled)
            assertEquals(listOf("hello.txt"), result.written.map { it.name })
            assertFalse("a half-written file was left behind", second.exists())
        }
    }

    @Test
    fun `progress is bytes, so one big file still moves the bar`() {
        // The freeze this fixes: progress used to be a file count, so an
        // archive of one large file sat at "0 of 1" for as long as it took
        // to write, looking dead. Bytes climb as it is written.
        val folder = temporary.newFolder("src")
        val big = File(folder, "big.bin")
        big.writeBytes(ByteArray(3_000_000) { (it % 251).toByte() })
        val zip = File(temporary.root, "big.zip")
        ArchiveWriter.zip(listOf(folder), zip)

        val into = temporary.newFolder("out")
        val seen = mutableListOf<Long>()
        Archives.open(zip).use { archive ->
            val result = ArchiveExtract.run(
                archive, into,
                onProgress = { done, total, _ ->
                    seen += done
                    assertTrue("done past total", done <= total)
                },
            )
            assertTrue(result.skipped.isEmpty())
            // More than the two end points: the bar moved while the single
            // file was being written, not only before and after.
            assertTrue("only $seen reported for a 3 MB file", seen.filter { it in 1..2_999_999 }.isNotEmpty())
            assertEquals(3_000_000L, seen.last())
        }
    }

    @Test
    fun `a stop lands mid-file on a big one, and deletes what was written`() {
        val folder = temporary.newFolder("src")
        File(folder, "big.bin").writeBytes(ByteArray(4_000_000) { it.toByte() })
        val zip = File(temporary.root, "big.zip")
        ArchiveWriter.zip(listOf(folder), zip)

        val into = temporary.newFolder("out")
        Archives.open(zip).use { archive ->
            // Stop after the first megabyte -- part way through the only
            // file, which the old between-files check could never do.
            val result = ArchiveExtract.run(
                archive, into,
                cancelled = { File(into, "src").resolve("big.bin").length() > 1_000_000 },
            )
            assertTrue(result.cancelled)
            assertTrue(result.written.isEmpty())
            assertFalse(File(into, "src/big.bin").exists())
        }
    }

    @Test
    fun `a zip made here is read back by the reader here`() {
        val folder = temporary.newFolder("papers")
        File(folder, "한글 이름.txt").writeText("한글 내용입니다\n")
        File(folder, "plain.txt").writeText("ordinary\n")
        File(folder, "sub").mkdirs()
        File(folder, "sub/deep.txt").writeText("deeper\n")

        val zip = File(temporary.root, "made.zip")
        val result = ArchiveWriter.zip(listOf(folder), zip)
        assertEquals(3, result.entries)

        ZipArchive.open(zip).use { archive ->
            assertEquals(
                listOf("papers/plain.txt", "papers/sub/deep.txt", "papers/한글 이름.txt"),
                archive.entries.map { it.path }.sorted(),
            )
            val korean = archive.entries.first { it.path.endsWith("한글 이름.txt") }
            assertEquals(
                "한글 내용입니다\n",
                archive.open(korean).use { String(it.readBytes()) },
            )
        }
    }

    @Test
    fun `a zip written into the folder it is zipping does not eat itself`() {
        // Without this the growing archive is a file in the tree being
        // walked, so it is read while it is written and stops only when
        // the disk does.
        val folder = temporary.newFolder("papers")
        File(folder, "one.txt").writeText("x".repeat(1000))
        val zip = File(folder, "papers.zip")
        zip.writeBytes(ByteArray(0))

        val result = ArchiveWriter.zip(listOf(folder), zip)
        assertEquals(1, result.entries)
        ZipArchive.open(zip).use { archive ->
            assertEquals(listOf("papers/one.txt"), archive.entries.map { it.path })
        }
    }

    @Test
    fun `an empty folder is kept`() {
        val folder = temporary.newFolder("papers")
        File(folder, "empty").mkdirs()
        File(folder, "one.txt").writeText("x\n")
        val zip = File(temporary.root, "made.zip")
        ArchiveWriter.zip(listOf(folder), zip)

        ZipArchive.open(zip).use { archive ->
            assertTrue(archive.entries.any { it.path == "papers/empty/" && it.isDirectory })
        }
    }

    /** An archive of exactly the entries given, all empty. */
    private class FakeArchive(vararg given: ArchiveEntry) : Archive {
        override val entries = given.toList()
        override fun open(entry: ArchiveEntry, password: CharArray?) =
            "content of ${entry.path}".byteInputStream()
        override fun close() = Unit
    }
}
