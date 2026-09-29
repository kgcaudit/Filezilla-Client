package org.filezilla.android.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure heart of the folder mirror.
 *
 * Everything that decides *what* the mirror does lives in [SyncDiff], and none
 * of it needs a device or a server, so all of it is tested here against maps.
 * The cases are the ones a mirror actually has to get right: something new on
 * one side, something changed, something the source no longer has, something
 * already the same, a whole tree of it, and the awkward case where one side has
 * a folder where the other has a file of the same name.
 */
class SyncDiffTest {

    private fun file(size: Long? = null, mtime: Long? = null) = Meta(false, size, mtime)
    private fun dir() = Meta(isDir = true)

    // ------------------------------------------------------------------- add

    @Test
    fun `a file the target lacks is copied`() {
        val plan = SyncDiff.diff(
            source = mapOf("a.txt" to file(size = 10)),
            target = emptyMap(),
        )

        assertEquals(listOf(SyncAction.Copy("a.txt", 10)), plan.actions)
        assertEquals(1, plan.copyCount)
        assertEquals(0, plan.deleteCount)
    }

    @Test
    fun `a folder the target lacks is made`() {
        val plan = SyncDiff.diff(
            source = mapOf("photos" to dir()),
            target = emptyMap(),
        )

        assertEquals(listOf(SyncAction.MakeDir("photos")), plan.actions)
        assertEquals(1, plan.makeDirCount)
    }

    // ---------------------------------------------------------------- change

    @Test
    fun `a file of a different size is copied over`() {
        val plan = SyncDiff.diff(
            source = mapOf("a.txt" to file(size = 20)),
            target = mapOf("a.txt" to file(size = 10)),
        )

        assertEquals(listOf(SyncAction.Copy("a.txt", 20)), plan.actions)
    }

    @Test
    fun `a newer source file is copied over when both times are known`() {
        val plan = SyncDiff.diff(
            source = mapOf("a.txt" to file(size = 10, mtime = 2_000)),
            target = mapOf("a.txt" to file(size = 10, mtime = 1_000)),
        )

        assertEquals(listOf(SyncAction.Copy("a.txt", 10)), plan.actions)
    }

    @Test
    fun `an older source file of the same size is left alone`() {
        val plan = SyncDiff.diff(
            source = mapOf("a.txt" to file(size = 10, mtime = 1_000)),
            target = mapOf("a.txt" to file(size = 10, mtime = 2_000)),
        )

        assertTrue(plan.actions.isEmpty())
        assertEquals(1, plan.skipCount)
    }

    @Test
    fun `same size but time known on only one side is left alone`() {
        val plan = SyncDiff.diff(
            source = mapOf("a.txt" to file(size = 10, mtime = 5_000)),
            target = mapOf("a.txt" to file(size = 10, mtime = null)),
        )

        assertTrue(plan.actions.isEmpty())
        assertEquals(1, plan.skipCount)
    }

    @Test
    fun `size known on only one side does not force a copy`() {
        val plan = SyncDiff.diff(
            source = mapOf("a.txt" to file(size = null, mtime = null)),
            target = mapOf("a.txt" to file(size = 10, mtime = null)),
        )

        assertTrue(plan.actions.isEmpty())
        assertEquals(1, plan.skipCount)
    }

    // ------------------------------------------------------------------ same

    @Test
    fun `identical files and existing folders produce nothing`() {
        val plan = SyncDiff.diff(
            source = mapOf("dir" to dir(), "dir/a.txt" to file(size = 3, mtime = 100)),
            target = mapOf("dir" to dir(), "dir/a.txt" to file(size = 3, mtime = 100)),
        )

        assertTrue(plan.actions.isEmpty())
        assertTrue(plan.isNoop)
        assertEquals(1, plan.skipCount)
    }

    // ---------------------------------------------------------------- delete

    @Test
    fun `an extra in the target is left be when deletion is off`() {
        val plan = SyncDiff.diff(
            source = emptyMap(),
            target = mapOf("old.txt" to file(size = 1)),
            deleteExtras = false,
        )

        assertTrue(plan.actions.isEmpty())
        assertEquals(0, plan.deleteCount)
    }

    @Test
    fun `an extra in the target is deleted when deletion is on`() {
        val plan = SyncDiff.diff(
            source = emptyMap(),
            target = mapOf("old.txt" to file(size = 1)),
            deleteExtras = true,
        )

        assertEquals(listOf(SyncAction.Delete("old.txt", isDir = false)), plan.actions)
        assertEquals(1, plan.deleteCount)
    }

    @Test
    fun `deletions run deepest first so a folder is emptied before it goes`() {
        val plan = SyncDiff.diff(
            source = emptyMap(),
            target = mapOf(
                "old" to dir(),
                "old/inner" to dir(),
                "old/inner/a.txt" to file(size = 1),
                "old/b.txt" to file(size = 1),
            ),
            deleteExtras = true,
        )

        val deletes = plan.actions.filterIsInstance<SyncAction.Delete>().map { it.rel }
        assertEquals(
            listOf("old/inner/a.txt", "old/inner", "old/b.txt", "old"),
            deletes,
        )
        // The folder itself comes after everything nested under it.
        assertTrue(deletes.indexOf("old") == deletes.lastIndex)
    }

    // ---------------------------------------------------------------- nested

    @Test
    fun `folders are made shallowest first then files copied`() {
        val plan = SyncDiff.diff(
            source = mapOf(
                "a" to dir(),
                "a/b" to dir(),
                "a/b/deep.txt" to file(size = 5),
                "a/top.txt" to file(size = 5),
            ),
            target = emptyMap(),
        )

        val makeDirs = plan.actions.filterIsInstance<SyncAction.MakeDir>().map { it.rel }
        assertEquals(listOf("a", "a/b"), makeDirs)
        // Every MakeDir precedes every Copy in the ordered action list.
        val firstCopy = plan.actions.indexOfFirst { it is SyncAction.Copy }
        val lastMakeDir = plan.actions.indexOfLast { it is SyncAction.MakeDir }
        assertTrue(lastMakeDir < firstCopy)
    }

    @Test
    fun `a mixed tree makes copies and deletes at once`() {
        val plan = SyncDiff.diff(
            source = mapOf(
                "keep.txt" to file(size = 1, mtime = 1),
                "new.txt" to file(size = 2),
                "sub" to dir(),
                "sub/changed.txt" to file(size = 9),
            ),
            target = mapOf(
                "keep.txt" to file(size = 1, mtime = 1),
                "sub" to dir(),
                "sub/changed.txt" to file(size = 4),
                "gone.txt" to file(size = 3),
            ),
            deleteExtras = true,
        )

        assertEquals(0, plan.makeDirCount)
        assertEquals(2, plan.copyCount) // new.txt + sub/changed.txt
        assertEquals(1, plan.deleteCount) // gone.txt
        assertEquals(1, plan.skipCount) // keep.txt
    }

    // -------------------------------------------------------------- conflict

    @Test
    fun `a folder over a file is reported and its subtree left out`() {
        val plan = SyncDiff.diff(
            source = mapOf(
                "item" to dir(),
                "item/inside.txt" to file(size = 5),
            ),
            target = mapOf("item" to file(size = 2)),
        )

        assertEquals(listOf(SyncConflict("item", sourceIsDir = true)), plan.conflicts)
        // Nothing under the conflicting folder is copied or made, since the
        // file in the way would have to be deleted first.
        assertTrue(plan.actions.isEmpty())
        assertEquals(0, plan.copyCount)
        assertEquals(0, plan.makeDirCount)
    }

    @Test
    fun `a file over a folder is reported and not copied`() {
        val plan = SyncDiff.diff(
            source = mapOf("item" to file(size = 5)),
            target = mapOf("item" to dir()),
        )

        assertEquals(listOf(SyncConflict("item", sourceIsDir = false)), plan.conflicts)
        assertTrue(plan.actions.isEmpty())
    }

    @Test
    fun `a conflicting folder is not deleted even with deletion on`() {
        // The target's file at "item" is the wrong kind, not an extra, so it is
        // the user's to resolve -- deletion on must not sweep it away.
        val plan = SyncDiff.diff(
            source = mapOf("item" to dir()),
            target = mapOf("item" to file(size = 2)),
            deleteExtras = true,
        )

        assertEquals(0, plan.deleteCount)
        assertEquals(1, plan.conflicts.size)
    }

    @Test
    fun `a sibling of a conflict still syncs`() {
        val plan = SyncDiff.diff(
            source = mapOf(
                "item" to dir(),
                "item/inside.txt" to file(size = 5),
                "ok.txt" to file(size = 1),
            ),
            target = mapOf("item" to file(size = 2)),
        )

        assertEquals(listOf(SyncAction.Copy("ok.txt", 1)), plan.actions)
        assertEquals(1, plan.conflicts.size)
    }

    // ------------------------------------------------------------------ noop

    @Test
    fun `two empty sides are a no-op`() {
        val plan = SyncDiff.diff(emptyMap(), emptyMap())

        assertTrue(plan.isNoop)
        assertTrue(plan.actions.isEmpty())
        assertFalse(plan.conflicts.any())
    }
}
