package org.filezilla.android.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Writing to the device, against real files.
 *
 * This is the half that can lose someone's data, so it is tested against a
 * real filesystem rather than a simulated one. The cases that matter are the
 * refusals: a folder copied into itself walks forever and starts writing on
 * the way, and a typed name with a slash in it creates the file somewhere the
 * user was not looking.
 */
class LocalOperationsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val root get() = temp.root.absolutePath

    private fun path(vararg parts: String) = File(temp.root, parts.joinToString("/")).absolutePath

    // --------------------------------------------------------------- making

    @Test
    fun `a folder is created`() {
        LocalOperations.createDirectory(root, "Movies")

        assertTrue(File(temp.root, "Movies").isDirectory)
    }

    @Test
    fun `a file is created empty`() {
        LocalOperations.createFile(root, "notes.txt")

        val made = File(temp.root, "notes.txt")
        assertTrue(made.isFile)
        assertEquals(0L, made.length())
    }

    @Test
    fun `creating over something that is there is refused`() {
        temp.newFolder("Movies")

        assertThrows(IOException::class.java) { LocalOperations.createDirectory(root, "Movies") }
    }

    /**
     * A typed name is not a path. Allowing a separator would put the file
     * somewhere other than the folder on screen, which nobody asked for and
     * nobody would see coming.
     */
    @Test
    fun `a name cannot contain a separator`() {
        // Inside a box of the test's own, so that "did anything escape" can be
        // asked of a directory this test controls. Asked of the system's
        // temporary directory, as it was first written, the answer depended on
        // what every other run had left lying there.
        val box = temp.newFolder("box").absolutePath

        val error = assertThrows(IOException::class.java) {
            LocalOperations.createDirectory(box, "../escaped")
        }

        assertTrue(error.message.orEmpty(), "slash" in error.message.orEmpty())
        assertFalse(File(temp.root, "escaped").exists())
        assertEquals(emptyList<String>(), temp.root.list()!!.toList() - "box")
    }

    @Test
    fun `dot and dot-dot are not names`() {
        assertThrows(IOException::class.java) { LocalOperations.createDirectory(root, ".") }
        assertThrows(IOException::class.java) { LocalOperations.createDirectory(root, "..") }
    }

    @Test
    fun `an empty name is refused`() {
        assertThrows(IOException::class.java) { LocalOperations.createDirectory(root, "   ") }
    }

    // ------------------------------------------------------------- removing

    @Test
    fun `a folder goes with everything in it`() {
        temp.newFolder("Movies", "2026")
        File(temp.root, "Movies/2026/a.mkv").writeText("x")

        LocalOperations.delete(path("Movies"))

        assertFalse(File(temp.root, "Movies").exists())
    }

    @Test
    fun `deleting what is already gone is not an error`() {
        LocalOperations.delete(path("missing"))
    }

    // ------------------------------------------------------------- renaming

    @Test
    fun `renaming keeps the contents`() {
        File(temp.root, "a.txt").writeText("hello")

        LocalOperations.rename(path("a.txt"), "b.txt")

        assertFalse(File(temp.root, "a.txt").exists())
        assertEquals("hello", File(temp.root, "b.txt").readText())
    }

    @Test
    fun `renaming onto an existing name is refused`() {
        File(temp.root, "a.txt").writeText("a")
        File(temp.root, "b.txt").writeText("b")

        assertThrows(IOException::class.java) { LocalOperations.rename(path("a.txt"), "b.txt") }

        // Neither was touched: a refusal must not be half done.
        assertEquals("a", File(temp.root, "a.txt").readText())
        assertEquals("b", File(temp.root, "b.txt").readText())
    }

    // -------------------------------------------------------------- copying

    @Test
    fun `a file is copied`() {
        File(temp.root, "a.txt").writeText("hello")
        temp.newFolder("target")

        LocalOperations.copy(path("a.txt"), path("target"))

        assertEquals("hello", File(temp.root, "target/a.txt").readText())
        // The original stays, which is what makes it a copy.
        assertTrue(File(temp.root, "a.txt").exists())
    }

    @Test
    fun `a folder is copied with everything under it`() {
        temp.newFolder("Movies", "2026")
        File(temp.root, "Movies/2026/a.mkv").writeText("x")
        temp.newFolder("target")

        LocalOperations.copy(path("Movies"), path("target"))

        assertEquals("x", File(temp.root, "target/Movies/2026/a.mkv").readText())
    }

    @Test
    fun `copying can rename on the way`() {
        File(temp.root, "a.txt").writeText("hello")
        temp.newFolder("target")

        LocalOperations.copy(path("a.txt"), path("target"), asName = "a (1).txt")

        assertEquals("hello", File(temp.root, "target/a (1).txt").readText())
    }

    /**
     * A copy keeps the original's modified time.
     *
     * The bug this pins: the copy streamed the bytes and let the new file
     * take the moment the write finished, so a photo taken last year, copied
     * to the phone, read as copied-just-now -- and a thousand files copied at
     * once all read as the same minute. A copy is the same file in another
     * place; its clock has to say the same thing.
     */
    @Test
    fun `a copy keeps the original's modified time`() {
        val source = File(temp.root, "a.txt").apply { writeText("hello") }
        // A year back, floored to the whole second: some filesystems keep
        // only seconds, and the copy has to land on the same tick on those
        // as on the ones that keep milliseconds.
        val stamp = (System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000) / 1000 * 1000
        assertTrue(source.setLastModified(stamp))
        temp.newFolder("target")

        LocalOperations.copy(path("a.txt"), path("target"))

        assertEquals(stamp, File(temp.root, "target/a.txt").lastModified())
    }

    /**
     * The bytes are reported as they are written, and add up to the whole.
     *
     * This is what feeds the badge in the corner: a copy that did not say how
     * many bytes it had moved could only show a bar sweeping back and forth. A
     * folder, so the count has to cross more than one file and still total the
     * sum of them.
     */
    @Test
    fun `a copy reports every byte it writes`() {
        temp.newFolder("tree", "sub")
        File(temp.root, "tree/a.bin").writeBytes(ByteArray(5000))
        File(temp.root, "tree/sub/b.bin").writeBytes(ByteArray(3000))
        temp.newFolder("target")

        var reported = 0L
        LocalOperations.copy(path("tree"), path("target"), onBytes = { reported += it })

        assertEquals(8000L, reported)
        assertEquals(8000L, LocalOperations.sizeOf(path("target", "tree")))
    }

    /** The size of a tree is the sum of its files, for measuring a copy first. */
    @Test
    fun `sizeOf sums a folder's files`() {
        temp.newFolder("tree", "sub")
        File(temp.root, "tree/a.bin").writeBytes(ByteArray(1200))
        File(temp.root, "tree/sub/b.bin").writeBytes(ByteArray(800))

        assertEquals(2000L, LocalOperations.sizeOf(path("tree")))
    }

    /**
     * A copy asked to stop stops, and does not leave a half-written file
     * pretending to be whole.
     *
     * The file manager offers to stop a copy from the detail sheet. Stopping
     * has to take the one file that was mid-stream back out -- a truncated file
     * that reads as complete is worse than no file -- while leaving whole the
     * ones that finished before the stop.
     */
    @Test
    fun `a cancelled copy stops and removes the partial file`() {
        File(temp.root, "big.bin").writeBytes(ByteArray(1_000_000))
        temp.newFolder("target")

        // Stop as soon as the first chunk lands, so the copy is partway in.
        var seen = false
        assertThrows(LocalOperations.Cancelled::class.java) {
            LocalOperations.copy(
                path("big.bin"),
                path("target"),
                onBytes = { seen = true },
                isCancelled = { seen },
            )
        }

        assertTrue("the copy should have started before stopping", seen)
        assertFalse(File(temp.root, "target/big.bin").exists())
    }

    @Test
    fun `a copy that fails partway removes the half-written file`() {
        File(temp.root, "big.bin").writeBytes(ByteArray(1_000_000))
        temp.newFolder("target")

        // A write failure partway -- a full disk is the usual one -- raised
        // here once the first chunk has landed, so the target exists and is
        // only half there when it strikes.
        val boom = IOException("no space left on device")
        var written = 0L
        assertThrows(IOException::class.java) {
            LocalOperations.copy(
                path("big.bin"),
                path("target"),
                onBytes = {
                    written += it
                    if (written > 0) throw boom
                },
            )
        }

        assertFalse(
            "a failed copy must not leave a file that looks whole",
            File(temp.root, "target/big.bin").exists(),
        )
    }

    /**
     * The two ways a copy eats itself. Both walk forever, and both start
     * writing before they do, so they are refused before anything is made
     * rather than discovered halfway through.
     */
    @Test
    fun `a folder cannot be copied into itself`() {
        temp.newFolder("Movies")

        assertThrows(IOException::class.java) { LocalOperations.copy(path("Movies"), root) }
    }

    @Test
    fun `a folder cannot be copied into its own descendant`() {
        temp.newFolder("Movies", "2026")

        val error = assertThrows(IOException::class.java) {
            LocalOperations.copy(path("Movies"), path("Movies", "2026"))
        }

        assertTrue(error.message.orEmpty(), "inside itself" in error.message.orEmpty())
        // Nothing started: no half-copied tree left behind.
        assertFalse(File(temp.root, "Movies/2026/Movies").exists())
    }

    /**
     * The name that merely starts the same is why this is a segment
     * comparison and not a prefix one. "Movies2" is not inside "Movies", and
     * refusing it would make a perfectly ordinary copy impossible.
     */
    @Test
    fun `a folder whose name merely starts the same can be copied into`() {
        temp.newFolder("Movies")
        temp.newFolder("Movies2")

        LocalOperations.copy(path("Movies"), path("Movies2"))

        assertTrue(File(temp.root, "Movies2/Movies").isDirectory)
    }

    @Test
    fun `copying onto an existing name is refused`() {
        File(temp.root, "a.txt").writeText("new")
        temp.newFolder("target")
        File(temp.root, "target/a.txt").writeText("old")

        assertThrows(IOException::class.java) { LocalOperations.copy(path("a.txt"), path("target")) }

        // The one already there is untouched, rather than half overwritten.
        assertEquals("old", File(temp.root, "target/a.txt").readText())
    }

    // --------------------------------------------------------------- moving

    @Test
    fun `a move within one volume is a rename, not a copy`() {
        File(temp.root, "a.txt").writeText("hello")
        temp.newFolder("target")

        val kind = LocalOperations.move(path("a.txt"), path("target"))

        // The distinction is the whole point: a rename costs nothing whatever
        // the file's size, and a 6 GB move that copied would take minutes.
        assertEquals(LocalOperations.MoveKind.RENAMED, kind)
        assertEquals("hello", File(temp.root, "target/a.txt").readText())
        assertFalse(File(temp.root, "a.txt").exists())
    }

    @Test
    fun `a folder moves with everything under it`() {
        temp.newFolder("Movies", "2026")
        File(temp.root, "Movies/2026/a.mkv").writeText("x")
        temp.newFolder("target")

        LocalOperations.move(path("Movies"), path("target"))

        assertEquals("x", File(temp.root, "target/Movies/2026/a.mkv").readText())
        assertFalse(File(temp.root, "Movies").exists())
    }

    @Test
    fun `a folder cannot be moved into itself`() {
        temp.newFolder("Movies")

        assertThrows(IOException::class.java) { LocalOperations.move(path("Movies"), root) }
        // Still there: a refused move must not remove the original.
        assertTrue(File(temp.root, "Movies").isDirectory)
    }
}
