package org.filezilla.android.ui

import org.filezilla.ftp.listing.DirectoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deleting a folder on a server, which FTP has no command for.
 *
 * The bug: `RMD` refuses a directory that is not empty, so deleting a folder
 * with anything in it came back as "550 Directory not empty" and the folder
 * stayed -- while deleting a file worked, which made it look like the app was
 * broken rather than the protocol being narrow.
 */
class RemoteDeleteTest {

    private fun file(name: String) = DirectoryEntry(name = name, size = 1)
    private fun dir(name: String) = DirectoryEntry(name = name, isDirectory = true)
    private fun link(name: String) =
        DirectoryEntry(name = name, isDirectory = true, isLink = true)

    /** A server whose tree is written out here, so the walk can be checked exactly. */
    private fun server(tree: Map<String, List<DirectoryEntry>>) =
        RemoteLister { path -> tree[path].orEmpty() }

    @Test
    fun `a loose file is one removal`() {
        val plan = RemoteDelete.plan(server(emptyMap()), "/pub", listOf(file("a.txt")))

        assertEquals(listOf(PlannedRemoval("/pub/a.txt", false)), plan.steps)
    }

    /** The case from the screenshot: a folder with files in it. */
    @Test
    fun `a folder is emptied before it is removed`() {
        val plan = RemoteDelete.plan(
            server(mapOf("/pub/test" to listOf(file("one.mkv"), file("two.srt")))),
            "/pub",
            listOf(dir("test")),
        )

        assertEquals(
            listOf(
                PlannedRemoval("/pub/test/one.mkv", false),
                PlannedRemoval("/pub/test/two.srt", false),
                PlannedRemoval("/pub/test", true),
            ),
            plan.steps,
        )
    }

    /** Deepest first, all the way down: an RMD in the wrong order is a 550. */
    @Test
    fun `a nested tree comes out bottom up`() {
        val plan = RemoteDelete.plan(
            server(
                mapOf(
                    "/a" to listOf(dir("b"), file("a.txt")),
                    "/a/b" to listOf(dir("c")),
                    "/a/b/c" to listOf(file("deep.bin")),
                ),
            ),
            "/",
            listOf(dir("a")),
        )

        assertEquals(
            listOf(
                PlannedRemoval("/a/b/c/deep.bin", false),
                PlannedRemoval("/a/b/c", true),
                PlannedRemoval("/a/b", true),
                PlannedRemoval("/a/a.txt", false),
                PlannedRemoval("/a", true),
            ),
            plan.steps,
        )
    }

    @Test
    fun `the root of a server is written without a doubled slash`() {
        val plan = RemoteDelete.plan(server(emptyMap()), "/", listOf(file("a.txt")))

        assertEquals("/a.txt", plan.steps.single().path)
    }

    // ------------------------------------------------------------- links

    /**
     * A link is unlinked, never followed. Following one leaves the folder the
     * user is looking at, and a link to "/" would take the server with it.
     */
    @Test
    fun `a link is removed as a file and not walked into`() {
        var listed = 0
        val lister = RemoteLister { path ->
            listed++
            if (path == "/pub/everything") listOf(file("do-not-touch")) else emptyList()
        }

        val plan = RemoteDelete.plan(lister, "/pub", listOf(link("everything")))

        assertEquals(listOf(PlannedRemoval("/pub/everything", false)), plan.steps)
        assertEquals("the link was followed", 0, listed)
        assertEquals(1, plan.links)
    }

    /**
     * And it is still removed rather than skipped. Leaving it would keep the
     * folder above it non-empty, so that folder's RMD would fail -- and the
     * delete would stop for a reason nothing on screen could explain.
     */
    @Test
    fun `a folder containing a link can still be emptied`() {
        val plan = RemoteDelete.plan(
            server(mapOf("/pub/test" to listOf(link("shortcut")))),
            "/pub",
            listOf(dir("test")),
        )

        assertEquals(
            listOf(
                PlannedRemoval("/pub/test/shortcut", false),
                PlannedRemoval("/pub/test", true),
            ),
            plan.steps,
        )
    }

    // -------------------------------------------------------- the bounds

    @Test
    fun `a tree deeper than the walk allows is marked incomplete`() {
        // Every folder contains another of the same name, for ever.
        val plan = RemoteDelete.plan(
            RemoteLister { listOf(dir("down")) },
            "/",
            listOf(dir("down")),
        )

        assertTrue(plan.truncated)
    }

    @Test
    fun `a folder of more entries than the walk allows is marked incomplete`() {
        val many = (1..FolderDownload.MAX_FILES + 10).map { file("f$it") }
        val plan = RemoteDelete.plan(
            server(mapOf("/pub/big" to many)),
            "/pub",
            listOf(dir("big")),
        )

        assertTrue(plan.truncated)
    }

    @Test
    fun `an ordinary tree is not marked incomplete`() {
        val plan = RemoteDelete.plan(
            server(mapOf("/pub/test" to listOf(file("a"), file("b")))),
            "/pub",
            listOf(dir("test")),
        )

        assertFalse(plan.truncated)
    }

    // ------------------------------------------------- what needs a walk

    @Test
    fun `only a real folder needs the server asked`() {
        assertFalse(RemoteDelete.needsRemoteWalk(listOf(file("a"), file("b"))))
        assertFalse(RemoteDelete.needsRemoteWalk(listOf(link("l"))))
        assertTrue(RemoteDelete.needsRemoteWalk(listOf(file("a"), dir("d"))))
    }
}
