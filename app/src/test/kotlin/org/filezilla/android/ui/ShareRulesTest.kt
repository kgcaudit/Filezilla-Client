package org.filezilla.android.ui

import org.filezilla.ftp.listing.DirectoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of the picked rows can actually be handed to another app.
 *
 * The same reckoning the selection bar does, kept here where it can be read
 * without a screen: a folder cannot go through a share sheet, and a
 * selection of nothing but folders is a button that must not look ready.
 */
class ShareRulesTest {

    private fun entry(name: String, folder: Boolean = false) =
        DirectoryEntry(name = name, isDirectory = folder)

    private fun sharable(path: String, rows: List<DirectoryEntry>, picked: Set<String>) =
        rows.filter { it.name in picked && !it.isDirectory }
            .map { org.filezilla.android.files.FilePath.child(path, it.name) }

    @Test
    fun `picked files are offered with their full paths`() {
        val paths = sharable(
            "/storage/emulated/0/Download",
            listOf(entry("a.jpg"), entry("b.png")),
            setOf("a.jpg", "b.png"),
        )

        assertEquals(
            listOf("/storage/emulated/0/Download/a.jpg", "/storage/emulated/0/Download/b.png"),
            paths,
        )
    }

    /** Picking a folder along with some photos shares the photos. */
    @Test
    fun `a folder in the selection is dropped rather than refusing the lot`() {
        val paths = sharable(
            "/phone",
            listOf(entry("holiday", folder = true), entry("a.jpg")),
            setOf("holiday", "a.jpg"),
        )

        assertEquals(listOf("/phone/a.jpg"), paths)
        assertTrue("the button should be live", paths.isNotEmpty())
    }

    @Test
    fun `nothing but folders leaves the button dead`() {
        val paths = sharable(
            "/phone",
            listOf(entry("one", folder = true), entry("two", folder = true)),
            setOf("one", "two"),
        )

        assertFalse("nothing to share, so nothing to offer", paths.isNotEmpty())
    }

    /** A row that is listed but not picked is not shared. */
    @Test
    fun `only the picked rows go`() {
        val paths = sharable("/phone", listOf(entry("a.jpg"), entry("b.jpg")), setOf("a.jpg"))

        assertEquals(listOf("/phone/a.jpg"), paths)
    }
}
