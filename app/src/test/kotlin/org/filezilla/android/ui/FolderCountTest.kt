package org.filezilla.android.ui

import org.filezilla.ftp.listing.DirectoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What a folder row says it holds.
 *
 * The distinction worth testing is zero against unknown. A folder the app
 * cannot read must not be called empty: that is a claim about its contents
 * made on the strength of not having seen them, and it is the answer a user
 * would act on by deleting it.
 */
class FolderCountTest {

    @get:Rule
    val phone = TemporaryFolder()

    @Test
    fun `a folder reports what is in it`() {
        val folder = phone.newFolder("holiday")
        File(folder, "a.jpg").writeText("x")
        File(folder, "b.jpg").writeText("x")
        File(folder, "raw").mkdirs()

        assertEquals(3, FolderCount.of(folder.absolutePath))
    }

    /** Zero, and meant: there is nothing in it. */
    @Test
    fun `an empty folder reports nothing in it`() {
        assertEquals(0, FolderCount.of(phone.newFolder("empty").absolutePath))
    }

    /** Unknown, and not to be confused with empty. */
    @Test
    fun `a folder that is not there is not empty`() {
        assertNull(FolderCount.of(File(phone.root, "never-existed").absolutePath))
    }

    @Test
    fun `a file is not a folder`() {
        val one = File(phone.root, "notes.txt").apply { writeText("x") }

        assertNull(FolderCount.of(one.absolutePath))
    }

    /** Hidden entries are in the folder whether or not the list shows them. */
    @Test
    fun `the count is of what is there, not of what is shown`() {
        val folder = phone.newFolder("dotted")
        File(folder, ".hidden").writeText("x")
        File(folder, "shown.txt").writeText("x")

        assertEquals(2, FolderCount.of(folder.absolutePath))
    }

    // -------------------------------------------- the summary under the list

    private fun file(name: String) = DirectoryEntry(name = name)
    private fun dir(name: String) = DirectoryEntry(name = name, isDirectory = true)

    @Test
    fun `the summary counts folders and files apart`() {
        val summary = summarise(listOf(dir("one"), dir("two"), file("a"), file("b"), file("c")))

        assertEquals(FolderSummary(folders = 2, files = 3), summary)
    }

    /**
     * Over the rows on screen, not over the listing. With a filter on, a
     * count of everything would be a number nobody could arrive at by
     * counting what is in front of them.
     */
    @Test
    fun `an empty listing sums to nothing`() {
        assertEquals(FolderSummary(0, 0), summarise(emptyList()))
    }
}
