package org.filezilla.android.ui

import org.filezilla.android.files.SyncAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Carrying a mirror out on the phone, against real folders in a temp dir.
 *
 * The pure diff decides what to do; this checks that doing it lands where it
 * should -- folders made, files copied over, extras removed -- and that the
 * server-to-phone case, where the files arrive through the queue instead, still
 * makes the empty folders and clears the extras without touching the files.
 */
class SyncLocallyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun source(): File = temp.newFolder("source")
    private fun target(): File = temp.newFolder("target")

    private fun write(root: File, rel: String, text: String) {
        val file = File(root, rel)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    @Test
    fun `a copy lands the file under the target`() {
        val src = source()
        val dst = target()
        write(src, "a.txt", "hello")

        val result = syncLocally(
            actions = listOf(SyncAction.Copy("a.txt", 5)),
            sourceRoot = src.absolutePath,
            targetRoot = dst.absolutePath,
            copyFiles = true,
        )

        assertEquals("hello", File(dst, "a.txt").readText())
        assertEquals(1, result.copied)
    }

    @Test
    fun `a copy replaces a file already there`() {
        val src = source()
        val dst = target()
        write(src, "a.txt", "new")
        write(dst, "a.txt", "old")

        syncLocally(
            actions = listOf(SyncAction.Copy("a.txt", 3)),
            sourceRoot = src.absolutePath,
            targetRoot = dst.absolutePath,
            copyFiles = true,
        )

        assertEquals("new", File(dst, "a.txt").readText())
    }

    @Test
    fun `a nested copy makes the folders it needs`() {
        val src = source()
        val dst = target()
        write(src, "deep/inner/a.txt", "x")

        syncLocally(
            actions = listOf(
                SyncAction.MakeDir("deep"),
                SyncAction.MakeDir("deep/inner"),
                SyncAction.Copy("deep/inner/a.txt", 1),
            ),
            sourceRoot = src.absolutePath,
            targetRoot = dst.absolutePath,
            copyFiles = true,
        )

        assertEquals("x", File(dst, "deep/inner/a.txt").readText())
    }

    @Test
    fun `an empty folder is made`() {
        val dst = target()

        val result = syncLocally(
            actions = listOf(SyncAction.MakeDir("empty")),
            sourceRoot = source().absolutePath,
            targetRoot = dst.absolutePath,
            copyFiles = true,
        )

        assertTrue(File(dst, "empty").isDirectory)
        assertEquals(1, result.made)
    }

    @Test
    fun `a delete removes an extra, folders and all`() {
        val dst = target()
        write(dst, "gone/inside.txt", "bye")

        val result = syncLocally(
            actions = listOf(
                SyncAction.Delete("gone/inside.txt", isDir = false),
                SyncAction.Delete("gone", isDir = true),
            ),
            sourceRoot = source().absolutePath,
            targetRoot = dst.absolutePath,
            copyFiles = true,
        )

        assertFalse(File(dst, "gone").exists())
        assertEquals(2, result.deleted)
    }

    @Test
    fun `with copyFiles off the files are left to the queue but folders and deletes still run`() {
        val src = source()
        val dst = target()
        write(src, "d/a.txt", "fetched by the queue")
        write(dst, "old.txt", "extra")

        val result = syncLocally(
            actions = listOf(
                SyncAction.MakeDir("d"),
                SyncAction.Copy("d/a.txt", 1),
                SyncAction.Delete("old.txt", isDir = false),
            ),
            sourceRoot = src.absolutePath,
            targetRoot = dst.absolutePath,
            copyFiles = false,
        )

        // The folder was made and the extra removed...
        assertTrue(File(dst, "d").isDirectory)
        assertFalse(File(dst, "old.txt").exists())
        // ...but the file was not copied -- that is the download queue's job.
        assertFalse(File(dst, "d/a.txt").exists())
        assertEquals(0, result.copied)
        assertEquals(1, result.made)
        assertEquals(1, result.deleted)
    }

    @Test
    fun `a stop between actions leaves the rest undone`() {
        val src = source()
        val dst = target()
        write(src, "a.txt", "1")
        write(src, "b.txt", "2")

        var seen = 0
        val result = syncLocally(
            actions = listOf(SyncAction.Copy("a.txt", 1), SyncAction.Copy("b.txt", 1)),
            sourceRoot = src.absolutePath,
            targetRoot = dst.absolutePath,
            copyFiles = true,
            cancelled = { seen++ >= 1 },
        )

        assertEquals(1, result.copied)
        assertTrue(File(dst, "a.txt").exists())
        assertFalse(File(dst, "b.txt").exists())
    }
}
