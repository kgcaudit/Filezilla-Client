package org.filezilla.android.ui

import org.filezilla.ftp.listing.DirectoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderDownloadTest {

    private fun file(name: String, size: Long = 10) = DirectoryEntry(name = name, size = size)
    private fun dir(name: String) = DirectoryEntry(name = name, size = 0, isDirectory = true)
    private fun link(name: String) =
        DirectoryEntry(name = name, size = 0, isDirectory = true, isLink = true)

    /** A fixed remote tree: path -> its entries. */
    private fun tree(vararg pairs: Pair<String, List<DirectoryEntry>>): RemoteLister {
        val map = pairs.toMap()
        return RemoteLister { path -> map[path] ?: emptyList() }
    }

    @Test
    fun `a selected folder queues the files inside it`() {
        val lister = tree("/HDD1/Vision" to listOf(file("a.mp4"), file("b.mp4")))

        val plan = FolderDownload.plan(lister, "/HDD1", listOf(dir("Vision")))

        assertEquals(
            listOf("/HDD1/Vision/a.mp4", "/HDD1/Vision/b.mp4"),
            plan.files.map { it.remotePath },
        )
        // Each lands in a "Vision" folder under the one the user chose.
        assertTrue(plan.files.all { it.subPath == listOf("Vision") })
    }

    /** The bug this fixes: a folder-only selection queued nothing, silently. */
    @Test
    fun `selecting only folders is not an empty plan`() {
        val lister = tree("/HDD1/Vision" to listOf(file("a.mp4")))

        val plan = FolderDownload.plan(lister, "/HDD1", listOf(dir("Vision")))

        assertEquals(1, plan.files.size)
    }

    @Test
    fun `nested folders are mirrored`() {
        val lister = tree(
            "/HDD1/Vision" to listOf(dir("clips"), file("top.txt")),
            "/HDD1/Vision/clips" to listOf(dir("2026"), file("one.mp4")),
            "/HDD1/Vision/clips/2026" to listOf(file("deep.mp4")),
        )

        val plan = FolderDownload.plan(lister, "/HDD1", listOf(dir("Vision")))

        val byPath = plan.files.associate { it.remotePath to it.subPath }
        assertEquals(listOf("Vision"), byPath["/HDD1/Vision/top.txt"])
        assertEquals(listOf("Vision", "clips"), byPath["/HDD1/Vision/clips/one.mp4"])
        assertEquals(listOf("Vision", "clips", "2026"), byPath["/HDD1/Vision/clips/2026/deep.mp4"])
    }

    @Test
    fun `a selected file still goes to the chosen folder itself`() {
        val plan = FolderDownload.plan(tree(), "/HDD1", listOf(file("loose.txt")))

        assertEquals("/HDD1/loose.txt", plan.files.single().remotePath)
        assertEquals(emptyList<String>(), plan.files.single().subPath)
    }

    @Test
    fun `files and folders can be selected together`() {
        val lister = tree("/HDD1/Vision" to listOf(file("a.mp4")))

        val plan = FolderDownload.plan(lister, "/HDD1", listOf(file("loose.txt"), dir("Vision")))

        assertEquals(
            listOf("/HDD1/loose.txt", "/HDD1/Vision/a.mp4"),
            plan.files.map { it.remotePath },
        )
    }

    /** A link pointing at its own parent would otherwise walk forever. */
    @Test
    fun `a link that loops back does not hang the walk`() {
        val lister = tree(
            "/HDD1/Vision" to listOf(file("a.mp4"), link("self")),
            "/HDD1/Vision/self" to listOf(file("a.mp4"), link("self")),
        )

        val plan = FolderDownload.plan(lister, "/HDD1", listOf(dir("Vision")))

        assertEquals(listOf("/HDD1/Vision/a.mp4"), plan.files.map { it.remotePath })
        assertEquals(1, plan.skippedLinks)
    }

    @Test
    fun `an unknown size is carried as null rather than minus one`() {
        val plan = FolderDownload.plan(tree(), "/HDD1", listOf(file("x.bin", size = -1)))

        assertEquals(null, plan.files.single().size)
        assertEquals(10L, FolderDownload.plan(tree(), "/HDD1", listOf(file("y.bin"))).files.single().size)
    }

    /** Depth has to have a floor under it, or a deep tree overflows the stack. */
    @Test
    fun `a tree deeper than the cap is truncated rather than followed`() {
        val deep = RemoteLister { path ->
            // Every folder contains one more folder, forever.
            listOf(dir("down"), file("at${path.count { it == '/' }}.txt"))
        }

        val plan = FolderDownload.plan(deep, "/", listOf(dir("down")))

        assertTrue(plan.truncated)
        assertTrue(plan.files.size <= FolderDownload.MAX_DEPTH)
    }

    @Test
    fun `an enormous folder stops at the file cap`() {
        val many = RemoteLister { path ->
            if (path == "/HDD1/Vision") (1..FolderDownload.MAX_FILES + 500).map { file("f$it.bin") }
            else emptyList()
        }

        val plan = FolderDownload.plan(many, "/HDD1", listOf(dir("Vision")))

        assertTrue(plan.truncated)
        assertEquals(FolderDownload.MAX_FILES, plan.files.size)
    }

    @Test
    fun `an empty folder plans nothing, so the caller can say so`() {
        val plan = FolderDownload.plan(tree("/HDD1/Empty" to emptyList()), "/HDD1", listOf(dir("Empty")))

        assertTrue(plan.files.isEmpty())
    }

    @Test
    fun `the root directory does not produce a doubled slash`() {
        val lister = tree("/Vision" to listOf(file("a.mp4")))

        val plan = FolderDownload.plan(lister, "/", listOf(dir("Vision")))

        assertEquals("/Vision/a.mp4", plan.files.single().remotePath)
    }

    // ------------------------------------------- whether to ask the server

    /**
     * A file names itself in the listing that showed it, so planning one
     * needs nothing from the server. Worth knowing, because a single file is
     * most of what gets downloaded, and connecting to plan it would put a
     * login in front of every one of them.
     */
    @Test
    fun `files alone need no walk`() {
        assertFalse(FolderDownload.needsRemoteWalk(listOf(file("a.mkv"), file("b.mkv"))))
    }

    @Test
    fun `a folder needs a walk`() {
        assertTrue(FolderDownload.needsRemoteWalk(listOf(file("a.mkv"), dir("Vision"))))
    }

    /**
     * A link is reported as a directory whether or not it is one, and
     * [FolderDownload.plan] passes over it rather than following it. Walking
     * for something that will be skipped anyway is a connection for nothing.
     */
    @Test
    fun `a link needs no walk, since it is not followed`() {
        assertFalse(FolderDownload.needsRemoteWalk(listOf(link("elsewhere"))))
    }

    @Test
    fun `nothing picked needs no walk`() {
        assertFalse(FolderDownload.needsRemoteWalk(emptyList()))
    }
}
