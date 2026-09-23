package org.filezilla.android.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding the next book, the way a reader means it.
 *
 * The trap is that the next book is not the alphabetical next: "Title 10"
 * comes before "Title 2" letter by letter, and a folder can hold two series.
 */
class ComicSeriesTest {

    @Test
    fun `the next number continues the series, not the next letters`() {
        val folder = listOf("Title 1.cbz", "Title 2.cbz", "Title 10.cbz")
        assertEquals("Title 2.cbz", ComicSeries.nextVolume("Title 1.cbz", folder))
        assertEquals("Title 10.cbz", ComicSeries.nextVolume("Title 2.cbz", folder))
    }

    @Test
    fun `the last book has no next`() {
        val folder = listOf("Title 1.cbz", "Title 2.cbz")
        assertNull(ComicSeries.nextVolume("Title 2.cbz", folder))
    }

    @Test
    fun `zero-padded numbers work too`() {
        val folder = listOf("ep01.cbz", "ep02.cbz", "ep03.cbz")
        assertEquals("ep02.cbz", ComicSeries.nextVolume("ep01.cbz", folder))
    }

    @Test
    fun `a gap is stepped over`() {
        assertEquals("Title 3.cbz", ComicSeries.nextVolume("Title 1.cbz", listOf("Title 1.cbz", "Title 3.cbz")))
    }

    @Test
    fun `another series in the same folder is left alone`() {
        val folder = listOf("A 1.cbz", "A 2.cbz", "B 1.cbz", "B 2.cbz")
        assertEquals("A 2.cbz", ComicSeries.nextVolume("A 1.cbz", folder))
    }

    @Test
    fun `a different extension does not count as the same series`() {
        assertNull(ComicSeries.nextVolume("Title 1.cbz", listOf("Title 2.cbr")))
    }

    @Test
    fun `a name with no number has no next`() {
        assertNull(ComicSeries.nextVolume("oneshot.cbz", listOf("oneshot.cbz", "Title 1.cbz")))
    }

    @Test
    fun `natural order puts two before ten`() {
        val sorted = listOf("p10.jpg", "p2.jpg", "p1.jpg").sortedWith(ComicSeries.NATURAL)
        assertEquals(listOf("p1.jpg", "p2.jpg", "p10.jpg"), sorted)
        assertTrue(ComicSeries.naturalCompare("2", "10") < 0)
    }

    // The bundle cases: a collection is one archive holding a volume per
    // sub-folder, or a volume per sub-archive. The next volume is found by the
    // same rule either way -- these lock the shapes the viewer relies on.

    @Test
    fun `volumes bundled as sub-folders continue by number`() {
        val folders = listOf("1권", "2권", "10권")
        assertEquals("2권", ComicSeries.nextVolume("1권", folders))
        assertEquals("10권", ComicSeries.nextVolume("2권", folders))
        assertNull(ComicSeries.nextVolume("10권", folders))
    }

    @Test
    fun `volumes bundled as sub-archives continue by number`() {
        val archives = listOf("만화 1.cbz", "만화 2.cbz", "만화 3.cbz")
        assertEquals("만화 2.cbz", ComicSeries.nextVolume("만화 1.cbz", archives))
        assertEquals("만화 3.cbz", ComicSeries.nextVolume("만화 2.cbz", archives))
    }
}
