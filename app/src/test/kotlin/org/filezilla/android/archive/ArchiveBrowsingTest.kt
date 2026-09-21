package org.filezilla.android.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tree inside an archive, which is mostly not in the archive.
 *
 * The fixture is a real ALZip file rather than a made-up list, because
 * the thing that makes this hard is exactly what ALZip does: it records
 * no folders. Every folder here is worked out from a name.
 */
class ArchiveBrowsingTest {

    private fun fixture(name: String) = File("src/test/resources/archives/$name")

    @Test
    fun `a real ALZip folder archive has a tree even with no folder records`() {
        Archives.open(fixture("alzip_folder.alz")).use { archive ->
            assertTrue(
                "the fixture is supposed to have no directory records",
                archive.entries.none { it.isDirectory },
            )
            val top = ArchiveBrowsing.rowsIn(archive.entries)
            assertTrue("nothing at the top of the archive", top.isNotEmpty())
            assertTrue("no folder was derived", top.any { it.isDirectory })
            for (folder in top.filter { it.isDirectory }) {
                assertTrue("${folder.path} counts nothing", folder.count > 0)
                assertTrue(
                    "${folder.path} opens onto nothing",
                    ArchiveBrowsing.rowsIn(archive.entries, folder.path.trimEnd('/')).isNotEmpty(),
                )
            }
        }
    }

    private val tree = listOf(
        ArchiveEntry("readme.txt", size = 10),
        ArchiveEntry("papers/one.txt", size = 100),
        ArchiveEntry("papers/two.txt", size = 200, encrypted = true),
        ArchiveEntry("papers/old/three.txt", size = 300),
        ArchiveEntry("Photos/a.jpg", size = 1000),
    )

    @Test
    fun `folders come before files and both are sorted by name`() {
        assertEquals(
            listOf("papers/", "Photos/", "readme.txt"),
            ArchiveBrowsing.rowsIn(tree).map { it.path },
        )
        assertEquals(
            listOf("papers/old/", "papers/one.txt", "papers/two.txt"),
            ArchiveBrowsing.rowsIn(tree, "papers").map { it.path },
        )
    }

    @Test
    fun `a folder counts everything under it, not just its own row`() {
        val papers = ArchiveBrowsing.rowsIn(tree).first { it.path == "papers/" }
        assertEquals(3, papers.count)
    }

    @Test
    fun `picking a folder picks what is inside it`() {
        val covered = ArchiveBrowsing.expand(tree, setOf("papers/"))
        assertEquals(
            setOf("papers/one.txt", "papers/two.txt", "papers/old/three.txt"),
            covered,
        )
        val picked = ArchiveBrowsing.picked(tree, setOf("papers/"))
        assertEquals(3, picked.files)
        assertEquals(600, picked.bytes)
        assertEquals(1, picked.locked)
    }

    @Test
    fun `picking a folder and a file inside it counts the file once`() {
        val picked = ArchiveBrowsing.picked(tree, setOf("papers/", "papers/one.txt"))
        assertEquals(3, picked.files)
        assertEquals(600, picked.bytes)
    }

    @Test
    fun `up from the top is nowhere`() {
        assertNull(ArchiveBrowsing.upFrom(""))
        assertEquals("", ArchiveBrowsing.upFrom("papers"))
        assertEquals("papers", ArchiveBrowsing.upFrom("papers/old"))
    }

    @Test
    fun `select all takes the rows shown and nothing else`() {
        assertEquals(
            setOf("papers/old/", "papers/one.txt", "papers/two.txt"),
            ArchiveBrowsing.pathsIn(tree, "papers"),
        )
    }
}
