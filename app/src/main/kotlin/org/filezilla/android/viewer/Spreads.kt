package org.filezilla.android.viewer

/**
 * Pairing a comic's pages into spreads for a wide screen.
 *
 * On a phone held sideways, or a tablet, two pages sit side by side the way
 * an open book does. The cover is the exception: page one stands alone, the
 * way the first page of a printed book faces nothing, and the pairs start
 * after it -- (2,3), (4,5), and so on. A last page with no partner stands
 * alone too. With pairing off, every page is its own spread, which is the
 * plain single-page reader.
 *
 * Kept apart from the screen and pure, because the arithmetic -- which page
 * begins which spread, which spread a page falls in -- is the kind that is
 * off by one in a way no screenshot shows, and is tested instead.
 */
object Spreads {

    /** The page indices in each spread, in reading order. */
    fun of(pageCount: Int, twoPage: Boolean): List<List<Int>> {
        if (pageCount <= 0) return emptyList()
        if (!twoPage) return (0 until pageCount).map { listOf(it) }
        val out = ArrayList<List<Int>>()
        out.add(listOf(0)) // the cover, alone
        var i = 1
        while (i < pageCount) {
            if (i + 1 < pageCount) {
                out.add(listOf(i, i + 1))
                i += 2
            } else {
                out.add(listOf(i))
                i += 1
            }
        }
        return out
    }

    /** How many spreads [pageCount] pages make. */
    fun count(pageCount: Int, twoPage: Boolean): Int {
        if (pageCount <= 0) return 0
        if (!twoPage) return pageCount
        return 1 + (pageCount - 1 + 1) / 2 // cover, then ceil((n-1)/2) pairs
    }

    /** The spread a page falls in. */
    fun spreadOf(page: Int, twoPage: Boolean): Int {
        if (!twoPage) return page
        if (page <= 0) return 0
        return 1 + (page - 1) / 2
    }

    /** The first page of a spread, for keeping the reader's place by page. */
    fun firstPage(spread: Int, twoPage: Boolean): Int {
        if (!twoPage) return spread
        if (spread <= 0) return 0
        return 1 + (spread - 1) * 2
    }
}
