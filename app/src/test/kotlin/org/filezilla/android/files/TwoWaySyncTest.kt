package org.filezilla.android.files

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * That a two-way sync merges both sides without copying a file twice or letting
 * the older overwrite the newer.
 *
 * The trap this guards is the one the implementation started in: running the
 * one-way diff both ways copies a file that differs by size in *both*
 * directions, so the older can land on top of the newer. [TwoWayDiff] picks a
 * winner by modification time instead, and reports a real tie as a conflict
 * rather than guessing.
 */
class TwoWaySyncTest {

    private fun file(size: Long, mtime: Long?) = Meta(isDir = false, size = size, mtimeMillis = mtime)

    private fun copies(plan: SyncPlan) = plan.actions.filterIsInstance<SyncAction.Copy>().map { it.rel }.toSet()

    @Test
    fun `each side gains what only the other has, and nothing is deleted`() {
        val r = TwoWayDiff.diff(
            source = mapOf("onlyLocal.txt" to file(1, 10)),
            target = mapOf("onlyRemote.txt" to file(1, 10)),
        )
        assertEquals(setOf("onlyLocal.txt"), copies(r.forward))
        assertEquals(setOf("onlyRemote.txt"), copies(r.backward))
        assertEquals(0, r.forward.deleteCount)
        assertEquals(0, r.backward.deleteCount)
    }

    @Test
    fun `a file changed on both sides is copied once, from the newer side`() {
        val r = TwoWayDiff.diff(
            source = mapOf("doc.txt" to file(20, mtime = 2000)), // newer on the phone
            target = mapOf("doc.txt" to file(10, mtime = 1000)), // older on the server
        )
        // The newer copies forward; the older does not come back.
        assertEquals(setOf("doc.txt"), copies(r.forward))
        assertEquals(emptySet<String>(), copies(r.backward))
    }

    @Test
    fun `the newer side wins whichever side it is`() {
        val r = TwoWayDiff.diff(
            source = mapOf("doc.txt" to file(20, mtime = 1000)), // older on the phone
            target = mapOf("doc.txt" to file(10, mtime = 2000)), // newer on the server
        )
        assertEquals(emptySet<String>(), copies(r.forward))
        assertEquals(setOf("doc.txt"), copies(r.backward))
    }

    @Test
    fun `a file identical on both sides is left alone`() {
        val both = mapOf("same.txt" to file(10, mtime = 1000))
        val r = TwoWayDiff.diff(both, both)
        assertEquals(emptySet<String>(), copies(r.forward))
        assertEquals(emptySet<String>(), copies(r.backward))
        assertEquals(1, r.forward.skipCount)
    }

    @Test
    fun `same age but different contents is a conflict, copied neither way`() {
        val r = TwoWayDiff.diff(
            source = mapOf("doc.txt" to file(20, mtime = 1000)),
            target = mapOf("doc.txt" to file(10, mtime = 1000)),
        )
        assertEquals(emptySet<String>(), copies(r.forward))
        assertEquals(emptySet<String>(), copies(r.backward))
        assertEquals(1, r.forward.conflicts.size)
    }

    @Test
    fun `a folder on one side and a file on the other is a conflict`() {
        val r = TwoWayDiff.diff(
            source = mapOf("x" to Meta(isDir = true)),
            target = mapOf("x" to file(1, 1)),
        )
        assertEquals(1, r.forward.conflicts.size)
        assertEquals(emptySet<String>(), copies(r.forward))
        assertEquals(emptySet<String>(), copies(r.backward))
    }
}
