package org.filezilla.android.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cutting a tall strip into bands: what counts as a webtoon, how much each
 * band shrinks, and that the pieces cover the whole page and none is too big
 * to decode.
 */
class WebtoonTest {

    @Test
    fun `a tall strip is a webtoon, a normal page is not`() {
        assertTrue(Webtoon.isWebtoon(1080, 8000))
        assertTrue(Webtoon.isWebtoon(2070, 19337))
        // A normal comic page, about 3:2 tall, is not a strip.
        assertFalse(Webtoon.isWebtoon(1400, 2000))
        // Exactly at the line counts; just under does not.
        assertTrue(Webtoon.isWebtoon(1000, 2500))
        assertFalse(Webtoon.isWebtoon(1000, 2499))
        assertFalse(Webtoon.isWebtoon(0, 5000))
    }

    @Test
    fun `a page wider than the screen is shrunk to fit it`() {
        // 2160 into a 1080 screen: /2 gives 1080, which still covers exactly.
        assertEquals(2, Webtoon.sampleForWidth(2160, 1080))
        // Already at screen width: no shrink.
        assertEquals(1, Webtoon.sampleForWidth(1080, 1080))
        // Narrower than the screen: never enlarged, so no shrink.
        assertEquals(1, Webtoon.sampleForWidth(720, 1080))
    }

    @Test
    fun `an enormous width is shrunk under the texture limit even with no viewport`() {
        // 10000 wide, unmeasured: must come down under 4096 a side.
        val sample = Webtoon.sampleForWidth(10000, 0)
        assertTrue(10000 / sample <= 4096)
    }

    @Test
    fun `a short page is a single band`() {
        val bands = Webtoon.plan(listOf(1080 to 1500), 1080)
        assertEquals(1, bands.size)
        assertEquals(0, bands[0].srcTop)
        assertEquals(1500, bands[0].srcBottom)
        assertEquals(1, bands[0].sample)
    }

    @Test
    fun `the crashing strip tiles into safe bands that cover it whole`() {
        val bands = Webtoon.plan(listOf(2070 to 19337), 1080)
        // Every band belongs to page 0 and stays within the decode limits.
        for (band in bands) {
            assertEquals(0, band.page)
            val decodedTall = (band.srcBottom - band.srcTop) / band.sample
            val decodedWide = band.imageWidth / band.sample
            assertTrue("band not too tall to draw", decodedTall <= Webtoon.MAX_BAND)
            assertTrue("band not too wide to draw", decodedWide <= 4096)
        }
        // The bands are contiguous and cover the page from top to bottom.
        assertEquals(0, bands.first().srcTop)
        assertEquals(19337, bands.last().srcBottom)
        for (i in 1 until bands.size) {
            assertEquals(bands[i - 1].srcBottom, bands[i].srcTop)
        }
    }

    @Test
    fun `several pages keep their order and their own page numbers`() {
        val bands = Webtoon.plan(listOf(1080 to 1000, 1080 to 6000), 1080)
        assertEquals(0, bands.first().page)
        assertEquals(1, bands.last().page)
        // Page 0 is short (one band); page 1 is tall (several).
        assertEquals(1, bands.count { it.page == 0 })
        assertTrue(bands.count { it.page == 1 } > 1)
    }

    @Test
    fun `a page with no size is skipped, not crashed on`() {
        val bands = Webtoon.plan(listOf(0 to 0, 1080 to 1500), 1080)
        assertEquals(1, bands.size)
        assertEquals(1, bands[0].page)
    }
}
