package org.filezilla.android.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Walking about inside an archive as though it were a folder.
 *
 * The rows come back as the same type a folder's rows are, so the browser
 * lists an archive with the machinery it already has; these tests are
 * about the navigation the browser cannot see -- descending, backing out,
 * and the one decision that keeps a tapped .apk or .docx out of here.
 */
class ArchiveNavTest {

    private val tree = listOf(
        ArchiveEntry("readme.txt", size = 10, modifiedMillis = 1_600_000_000_000),
        ArchiveEntry("papers/one.txt", size = 100),
        ArchiveEntry("papers/old/three.txt", size = 300),
    )
    private val session = ArchiveSession(File("x.zip"), "x.zip", "/sdcard/Download", tree)

    @Test
    fun `only the app's own archive extensions are browsed, never a package or a document`() {
        assertTrue(ArchiveNav.browsable("holiday.zip"))
        assertTrue(ArchiveNav.browsable("사진.alz"))
        assertTrue(ArchiveNav.browsable("music.egg"))
        // All of these are zip underneath, and none should open as an
        // archive: the tap on an apk installs, and a docx opens in a reader.
        assertFalse(ArchiveNav.browsable("app.apk"))
        assertFalse(ArchiveNav.browsable("report.docx"))
        assertFalse(ArchiveNav.browsable("sheet.xlsx"))
        assertFalse(ArchiveNav.browsable("lib.jar"))
        assertFalse(ArchiveNav.browsable("book.epub"))
    }

    @Test
    fun `the root shows the top of the archive as folder rows`() {
        val rows = ArchiveNav.rows(session)
        assertEquals(listOf("papers", "readme.txt"), rows.map { it.name })
        assertTrue(rows.first { it.name == "papers" }.isDirectory)
        assertFalse(rows.first { it.name == "readme.txt" }.isDirectory)
        assertEquals(1_600_000_000_000, rows.first { it.name == "readme.txt" }.time?.epochMillis)
    }

    @Test
    fun `descending and backing out walk the tree and then leave`() {
        val papers = ArchiveNav.into(session, "papers")
        assertEquals("papers", papers.at)
        assertEquals(listOf("old", "one.txt"), ArchiveNav.rows(papers).map { it.name })

        val old = ArchiveNav.into(papers, "old")
        assertEquals("papers/old", old.at)

        assertEquals("papers", ArchiveNav.up(old)?.at)
        assertEquals("", ArchiveNav.up(papers)?.at)
        // Backing out of the root is the signal to leave the archive.
        assertNull(ArchiveNav.up(session))
    }

    @Test
    fun `a row maps back to the archive entry it stands for`() {
        assertEquals("readme.txt", ArchiveNav.entryFor(session, "readme.txt")?.path)
        val papers = ArchiveNav.into(session, "papers")
        assertEquals("papers/one.txt", ArchiveNav.entryFor(papers, "one.txt")?.path)
        // A folder row is not a file, so it has no entry to open.
        assertNull(ArchiveNav.entryFor(papers, "old"))
    }

    @Test
    fun `a breadcrumb path carries the archive depth and folder, or leaves`() {
        // Depth 0 is the outermost archive; a nested one is deeper.
        assertEquals(0 to "papers", ArchiveNav.leg(ArchiveNav.crumb(0, "papers")))
        assertEquals(0 to "", ArchiveNav.leg(ArchiveNav.crumb(0, "")))
        assertEquals(2 to "a/b", ArchiveNav.leg(ArchiveNav.crumb(2, "a/b")))
        // A real folder path is not one of the archive's, so it leaves.
        assertNull(ArchiveNav.leg("/sdcard/Download"))
    }
}
