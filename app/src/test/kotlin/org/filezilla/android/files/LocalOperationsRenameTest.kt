package org.filezilla.android.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Renaming on the phone, including the way it fails.
 *
 * The user's report: renaming a folder in My Files did nothing. `renameTo`
 * answers false rather than saying why, and on the emulated volume it answers
 * false for cases that are legal -- so the rename threw, and the error was
 * then wiped by the pane's own refresh.
 */
class LocalOperationsRenameTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a folder is renamed with what is in it`() {
        val here = folder.newFolder("here")
        File(here, "Before/inner").mkdirs()
        File(here, "Before/one.txt").writeText("hello")

        LocalOperations.rename(File(here, "Before").path, "After")

        assertFalse(File(here, "Before").exists())
        assertTrue(File(here, "After/inner").isDirectory)
        assertEquals("hello", File(here, "After/one.txt").readText())
    }

    @Test
    fun `a file is renamed`() {
        val here = folder.newFolder("here")
        File(here, "before.txt").writeText("hello")

        LocalOperations.rename(File(here, "before.txt").path, "after.txt")

        assertEquals("hello", File(here, "after.txt").readText())
        assertFalse(File(here, "before.txt").exists())
    }

    /** The copy fallback must not fire on a name that is taken. */
    @Test
    fun `a name already in use is refused`() {
        val here = folder.newFolder("here")
        File(here, "one").mkdirs()
        File(here, "two").mkdirs()
        File(here, "one/keep.txt").writeText("x")

        runCatching { LocalOperations.rename(File(here, "one").path, "two") }
            .onSuccess { throw AssertionError("the rename should have been refused") }

        assertTrue("the original was disturbed", File(here, "one/keep.txt").exists())
        assertTrue(File(here, "two").isDirectory)
    }

    @Test
    fun `a name that would escape the folder is refused`() {
        val here = folder.newFolder("here")
        File(here, "one").mkdirs()

        for (name in listOf("../escaped", "a/b", "..", " ")) {
            runCatching { LocalOperations.rename(File(here, "one").path, name) }
                .onSuccess { throw AssertionError("'$name' should have been refused") }
        }

        assertTrue(File(here, "one").isDirectory)
    }

    @Test
    fun `renaming something that is gone says so`() {
        val here = folder.newFolder("here")

        runCatching { LocalOperations.rename(File(here, "ghost").path, "other") }
            .onSuccess { throw AssertionError("the rename should have been refused") }
    }
}
