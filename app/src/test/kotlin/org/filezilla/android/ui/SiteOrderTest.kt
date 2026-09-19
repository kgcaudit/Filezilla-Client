package org.filezilla.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteOrderTest {

    private val list = listOf("a", "b", "c")

    @Test
    fun `a row swaps with the one above it`() {
        assertEquals(listOf("a", "c", "b"), SiteOrder.moved(list, "c", Move.UP))
    }

    @Test
    fun `a row swaps with the one below it`() {
        assertEquals(listOf("b", "a", "c"), SiteOrder.moved(list, "a", Move.DOWN))
    }

    /**
     * The ends are where an off-by-one shows up, and where it would either
     * throw or quietly drop a row. Null says "nothing to do" instead.
     */
    @Test
    fun `the top row cannot go up`() {
        assertNull(SiteOrder.moved(list, "a", Move.UP))
    }

    @Test
    fun `the bottom row cannot go down`() {
        assertNull(SiteOrder.moved(list, "c", Move.DOWN))
    }

    @Test
    fun `the only row cannot go anywhere`() {
        assertNull(SiteOrder.moved(listOf("a"), "a", Move.UP))
        assertNull(SiteOrder.moved(listOf("a"), "a", Move.DOWN))
    }

    /** A row deleted on another screen while this one was open. */
    @Test
    fun `a row that is not there moves nothing`() {
        assertNull(SiteOrder.moved(list, "gone", Move.UP))
    }

    @Test
    fun `nothing is lost or duplicated by a move`() {
        val moved = SiteOrder.moved(list, "b", Move.DOWN)

        assertEquals(list.toSet(), moved?.toSet())
        assertEquals(list.size, moved?.size)
    }

    /** What the screen asks to decide whether to dim a button. */
    @Test
    fun `the ends report that they cannot move`() {
        assertFalse(SiteOrder.canMove(list, "a", Move.UP))
        assertTrue(SiteOrder.canMove(list, "a", Move.DOWN))
        assertTrue(SiteOrder.canMove(list, "c", Move.UP))
        assertFalse(SiteOrder.canMove(list, "c", Move.DOWN))
    }
}
