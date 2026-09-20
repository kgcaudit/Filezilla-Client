package org.filezilla.android.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What is left of a cut folder once the move has carried its files away.
 *
 * The bug: cutting folders on the phone and pasting them on a server queued
 * the uploads and left every folder where it was, empty ones included, so a
 * move was a copy with extra steps. These are the cases that decide whether
 * clearing them up is safe -- above all the one where a file is still there,
 * because a folder that still holds something is a folder whose file did not
 * arrive.
 */
class EmptyFoldersTest {

    @get:Rule
    val phone = TemporaryFolder()

    private fun folder(path: String): File =
        File(phone.root, path).apply { mkdirs() }

    private fun file(path: String): File =
        File(phone.root, path).apply {
            parentFile?.mkdirs()
            writeText("x")
        }

    @Test
    fun `an empty folder goes`() {
        val root = folder("holiday")

        val removed = EmptyFolders.prune(root)

        assertFalse("the folder is still there", root.exists())
        assertEquals(listOf(root.absolutePath), removed)
    }

    /** The case the whole thing is for: deepest first, so parents follow. */
    @Test
    fun `a tree of empty folders goes from the bottom up`() {
        val root = folder("trip")
        folder("trip/2025/summer")
        folder("trip/2025/winter")

        EmptyFolders.prune(root)

        assertFalse("the tree survived", root.exists())
    }

    /** A file still there means its transfer did not arrive. Leave everything. */
    @Test
    fun `a folder still holding a file is left alone`() {
        val root = folder("trip")
        file("trip/2025/notes.txt")

        val removed = EmptyFolders.prune(root)

        assertTrue("the folder was removed with a file in it", root.isDirectory)
        assertTrue("the file was removed", File(phone.root, "trip/2025/notes.txt").isFile)
        assertEquals(emptyList<String>(), removed)
    }

    /** And only the branches that emptied: the rest of the shape stays. */
    @Test
    fun `the empty branches go and the rest stays`() {
        val root = folder("trip")
        folder("trip/empty")
        file("trip/kept/notes.txt")

        EmptyFolders.prune(root)

        assertFalse("the empty branch stayed", File(phone.root, "trip/empty").exists())
        assertTrue("the branch with a file in it went", File(phone.root, "trip/kept").isDirectory)
        assertTrue("the root went with a file still under it", root.isDirectory)
    }

    /** Never a file, whatever it is asked. */
    @Test
    fun `a file is not a folder and is not touched`() {
        val one = file("notes.txt")

        val removed = EmptyFolders.prune(one)

        assertTrue("deleted a file", one.isFile)
        assertEquals(emptyList<String>(), removed)
    }

    @Test
    fun `a folder that is not there is not an error`() {
        assertEquals(emptyList<String>(), EmptyFolders.prune(File(phone.root, "never-existed")))
    }
}
