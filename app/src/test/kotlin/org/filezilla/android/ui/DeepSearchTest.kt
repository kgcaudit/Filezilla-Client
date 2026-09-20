package org.filezilla.android.ui

import org.filezilla.ftp.listing.DirectoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walk behind "하위 폴더까지 찾기".
 *
 * Tested against a tree made of maps rather than a filesystem or a server,
 * because what can go wrong here is the shape of the walk -- a link followed
 * into a loop, a ".." climbed back up, a limit that stops the search without
 * saying so -- and none of that is about where the folders came from. The
 * same walk runs on the phone and on a server, so getting it right once is
 * the point of it being one function.
 */
class DeepSearchTest {

    /** A tree as folder-path to rows. */
    private fun tree(vararg folders: Pair<String, List<DirectoryEntry>>): RemoteLister {
        val map = folders.toMap()
        return RemoteLister { path -> map[path] ?: error("no such folder: $path") }
    }

    private fun file(name: String) = DirectoryEntry(name = name)
    private fun dir(name: String) = DirectoryEntry(name = name, isDirectory = true)
    private fun link(name: String) =
        DirectoryEntry(name = name, isDirectory = true, isLink = true)

    @Test
    fun `it finds what the folder on screen does not show`() {
        val lister = tree(
            "/music" to listOf(dir("2025"), file("readme.txt")),
            "/music/2025" to listOf(file("natori - iris.mp3")),
        )

        val found = DeepSearch.walk("/music", lister, "natori")

        assertEquals(listOf("/music/2025/natori - iris.mp3"), found.hits.map { it.path })
    }

    /** Where it was found, which is the whole point of showing the path. */
    @Test
    fun `a hit says which folder it is in`() {
        val lister = tree(
            "/music" to listOf(dir("2025")),
            "/music/2025" to listOf(file("natori.mp3")),
        )

        val hit = DeepSearch.walk("/music", lister, "natori").hits.single()

        assertEquals("/music/2025", hit.folder)
        assertEquals("natori.mp3", hit.entry.name)
    }

    @Test
    fun `folders match by name as well as files`() {
        val lister = tree(
            "/music" to listOf(dir("natori")),
            "/music/natori" to listOf(file("one.mp3")),
        )

        val found = DeepSearch.walk("/music", lister, "natori")

        assertEquals(listOf("/music/natori"), found.hits.map { it.path })
    }

    @Test
    fun `case does not matter`() {
        val lister = tree("/x" to listOf(file("NATORI.mp3")))

        assertEquals(1, DeepSearch.walk("/x", lister, "natori").hits.size)
    }

    /**
     * Breadth first: the thing being looked for is far more often one level
     * down than twenty, so a search that is cut short has given back the
     * part most likely to be wanted.
     */
    @Test
    fun `the shallow answers come first`() {
        val lister = tree(
            "/x" to listOf(dir("deep"), file("natori-here.mp3")),
            "/x/deep" to listOf(dir("deeper")),
            "/x/deep/deeper" to listOf(file("natori-buried.mp3")),
        )

        val found = DeepSearch.walk("/x", lister, "natori")

        assertEquals(
            listOf("/x/natori-here.mp3", "/x/deep/deeper/natori-buried.mp3"),
            found.hits.map { it.path },
        )
    }

    /** A link can point at its own parent; following one never ends. */
    @Test
    fun `a link is not followed`() {
        val lister = tree(
            "/x" to listOf(link("back"), file("natori.mp3")),
            // Listing this at all would mean the link was followed.
            "/x/back" to listOf(file("natori-never.mp3")),
        )

        val found = DeepSearch.walk("/x", lister, "natori")

        assertEquals(listOf("/x/natori.mp3"), found.hits.map { it.path })
    }

    /** `..` is navigation; walking into it climbs back out and loops. */
    @Test
    fun `dot entries are not walked into`() {
        val lister = tree("/x/y" to listOf(dir("."), dir(".."), file("natori.mp3")))

        val found = DeepSearch.walk("/x/y", lister, "natori")

        assertEquals(listOf("/x/y/natori.mp3"), found.hits.map { it.path })
    }

    /** A folder this user cannot open is not a reason to abandon the rest. */
    @Test
    fun `a folder that will not open is passed over`() {
        val lister = RemoteLister { path ->
            when (path) {
                "/x" -> listOf(dir("locked"), dir("open"))
                "/x/open" -> listOf(file("natori.mp3"))
                else -> throw IllegalStateException("550 permission denied")
            }
        }

        val found = DeepSearch.walk("/x", lister, "natori")

        assertEquals(listOf("/x/open/natori.mp3"), found.hits.map { it.path })
    }

    @Test
    fun `hidden files stay hidden unless they are being shown`() {
        val lister = tree("/x" to listOf(file(".natori-cache")))

        assertTrue(DeepSearch.walk("/x", lister, "natori").hits.isEmpty())
        assertEquals(1, DeepSearch.walk("/x", lister, "natori", showHidden = true).hits.size)
    }

    /**
     * A search that stopped early has to say so, or the user reads "3개
     * 찾음" as "there are three".
     */
    @Test
    fun `running into a limit is reported rather than hidden`() {
        val many = (1..DeepSearch.MAX_HITS + 10).map { file("natori-$it.mp3") }
        val lister = tree("/x" to many)

        val found = DeepSearch.walk("/x", lister, "natori")

        assertEquals(DeepSearch.MAX_HITS, found.hits.size)
        assertTrue("stopped early and did not say so", found.truncated)
    }

    @Test
    fun `a search that finds everything is not marked truncated`() {
        val lister = tree("/x" to listOf(file("natori.mp3")))

        assertFalse(DeepSearch.walk("/x", lister, "natori").truncated)
    }

    /** Called off between folders, and it gives back what it had. */
    @Test
    fun `cancelling keeps what was found so far`() {
        var read = 0
        val lister = RemoteLister { path ->
            read++
            when (path) {
                "/x" -> listOf(dir("a"), dir("b"))
                "/x/a" -> listOf(file("natori-one.mp3"))
                else -> listOf(file("natori-two.mp3"))
            }
        }

        val found = DeepSearch.walk("/x", lister, "natori", cancelled = { read >= 2 })

        assertTrue("did not report being called off", found.cancelled)
        assertEquals(listOf("/x/a/natori-one.mp3"), found.hits.map { it.path })
    }

    /** Nothing to look for is not a reason to read the whole tree. */
    @Test
    fun `an empty needle reads nothing`() {
        var read = 0
        val lister = RemoteLister { read++; emptyList() }

        val found = DeepSearch.walk("/x", lister, "   ")

        assertEquals(0, read)
        assertEquals(emptyList<SearchHit>(), found.hits)
    }

    /** What the walk cost, which is what the screen tells the user about. */
    @Test
    fun `it reports how many folders it opened`() {
        val lister = tree(
            "/x" to listOf(dir("a"), dir("b")),
            "/x/a" to emptyList(),
            "/x/b" to emptyList(),
        )

        assertEquals(3, DeepSearch.walk("/x", lister, "natori").foldersRead)
    }
}
