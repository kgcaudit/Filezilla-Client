package org.filezilla.android.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering names the way a reader expects: a number by its value, letters
 * without regard to case, and one fixed order for names that otherwise tie.
 */
class NaturalOrderTest {

    private fun sorted(vararg names: String): List<String> =
        names.toList().sortedWith(NaturalOrder.byName)

    @Test
    fun `a plain run of numbers reads by value, not by text`() {
        // The bug this exists to kill: 10 must not sort before 2.
        assertEquals(
            listOf("1.jpg", "2.jpg", "9.jpg", "10.jpg", "11.jpg", "12.jpg"),
            sorted("11.jpg", "2.jpg", "10.jpg", "1.jpg", "12.jpg", "9.jpg"),
        )
    }

    @Test
    fun `a number in the middle of a name still reads by value`() {
        assertEquals(
            listOf("Title 1.cbz", "Title 2.cbz", "Title 10.cbz"),
            sorted("Title 10.cbz", "Title 1.cbz", "Title 2.cbz"),
        )
    }

    @Test
    fun `zero padding does not change the value`() {
        // 01, 02, 010 read as 1, 2, 10 -- padding is only how it is written.
        assertEquals(
            listOf("01.jpg", "02.jpg", "010.jpg"),
            sorted("010.jpg", "02.jpg", "01.jpg"),
        )
        // Padded or not, all the ones sort before the two.
        val ordered = sorted("2.jpg", "1.jpg", "01.jpg", "001.jpg")
        assertEquals("2.jpg", ordered.last())
    }

    @Test
    fun `names that differ only by padding get one fixed order`() {
        // Equal in value, so the raw text breaks the tie the same way every
        // time rather than letting the two rows swap places.
        val once = sorted("1.jpg", "01.jpg")
        val again = sorted("01.jpg", "1.jpg")
        assertEquals(once, again)
    }

    @Test
    fun `several number groups each read by value`() {
        assertEquals(
            listOf("ch1_p2", "ch1_p10", "ch2_p1"),
            sorted("ch2_p1", "ch1_p10", "ch1_p2"),
        )
    }

    @Test
    fun `letters sort without regard to case`() {
        assertEquals(listOf("album", "Photos"), sorted("Photos", "album"))
        // The number still wins over the letters around it.
        assertEquals(listOf("A2", "a10"), sorted("a10", "A2"))
    }

    @Test
    fun `korean names read in 가나다 and by value`() {
        assertEquals(listOf("가.jpg", "나.jpg", "다.jpg"), sorted("다.jpg", "가.jpg", "나.jpg"))
        assertEquals(listOf("1화", "2화", "10화"), sorted("10화", "1화", "2화"))
    }

    @Test
    fun `leading symbols sort by their own place, before or after digits`() {
        // Pure natural order: a symbol below '0' in code leads, one above it
        // trails -- so a cover is put first by a name that sorts first, such as
        // "000", rather than by any special case.
        assertTrue("hash before a digit", NaturalOrder.compare("#01.jpg", "1.jpg") < 0)
        assertTrue("underscore after a digit", NaturalOrder.compare("_extra.jpg", "1.jpg") > 0)
        assertEquals("000.jpg", sorted("1.jpg", "000.jpg", "2.jpg").first())
    }
}
