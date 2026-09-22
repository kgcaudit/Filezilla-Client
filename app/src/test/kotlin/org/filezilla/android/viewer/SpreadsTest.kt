package org.filezilla.android.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pairing pages into spreads: the cover alone, then twos, and the off-by-one
 * places -- which page starts which spread, which spread a page is in.
 */
class SpreadsTest {

    @Test
    fun `the cover stands alone and the rest pair up`() {
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), Spreads.of(5, twoPage = true))
    }

    @Test
    fun `a last page with no partner stands alone`() {
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), Spreads.of(4, twoPage = true))
    }

    @Test
    fun `two pages are the cover and one more`() {
        assertEquals(listOf(listOf(0), listOf(1)), Spreads.of(2, twoPage = true))
    }

    @Test
    fun `single-page mode gives one page per spread`() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), Spreads.of(3, twoPage = false))
    }

    @Test
    fun `which spread a page falls in`() {
        assertEquals(0, Spreads.spreadOf(0, twoPage = true))
        assertEquals(1, Spreads.spreadOf(1, twoPage = true))
        assertEquals(1, Spreads.spreadOf(2, twoPage = true))
        assertEquals(2, Spreads.spreadOf(3, twoPage = true))
        assertEquals(2, Spreads.spreadOf(4, twoPage = true))
    }

    @Test
    fun `the first page of a spread`() {
        assertEquals(0, Spreads.firstPage(0, twoPage = true))
        assertEquals(1, Spreads.firstPage(1, twoPage = true))
        assertEquals(3, Spreads.firstPage(2, twoPage = true))
    }

    @Test
    fun `page and spread agree with the spread list`() {
        val pages = 9
        val spreads = Spreads.of(pages, twoPage = true)
        assertEquals(spreads.size, Spreads.count(pages, twoPage = true))
        for (s in spreads.indices) {
            assertEquals("spread $s starts at its first page", spreads[s].first(), Spreads.firstPage(s, true))
        }
        for (p in 0 until pages) {
            assertEquals("page $p is in the spread that lists it", p in spreads[Spreads.spreadOf(p, true)], true)
        }
    }

    @Test
    fun `single mode leaves pages where they are`() {
        assertEquals(4, Spreads.spreadOf(4, twoPage = false))
        assertEquals(4, Spreads.firstPage(4, twoPage = false))
    }
}
