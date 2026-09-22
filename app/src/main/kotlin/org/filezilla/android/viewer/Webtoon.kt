package org.filezilla.android.viewer

/**
 * Reading a webtoon: one long strip, or a run of them, scrolled top to bottom.
 *
 * A webtoon page is a tall image -- often far taller than any phone screen or
 * even than a single bitmap a GPU can hold -- so it is cut into horizontal
 * bands, and only the bands on screen are ever decoded. This works out where
 * each band's slice of the source image is and how much to shrink it, so the
 * screen shows the strip at its own width with nothing decoded that is not
 * being looked at. Pure, because the tiling arithmetic is the part that goes
 * wrong out of sight.
 */
object Webtoon {

    /**
     * A tall image is one whose height is at least twice its width. A normal
     * comic or manga page is about half as tall again as it is wide (three to
     * two, so around 1.5); a webtoon page -- whether one long strip or a run of
     * screen-shaped panels stacked to be scrolled -- is twice as tall or more.
     * The line sits at two: high enough that an ordinary page is never mistaken
     * for a strip, low enough that a page shaped like the phone's own screen,
     * which reads as a slideshow rather than a scroll when paged, is caught and
     * opened to scroll instead.
     */
    private const val RATIO = 2.0

    /** The most a band decodes to on its long side, kept modest so a scroll is cheap. */
    const val MAX_BAND = 2048

    /** The longest side any decode may reach, under the smallest GPU texture worth supporting. */
    private const val MAX_DIMENSION = 4096

    fun isWebtoon(width: Int, height: Int): Boolean =
        width > 0 && height >= width * RATIO

    /** One horizontal slice of one page: its source rows, and the shrink to decode at. */
    data class Band(
        val page: Int,
        val imageWidth: Int,
        val srcTop: Int,
        val srcBottom: Int,
        val sample: Int,
    )

    /**
     * The shrink for a page: enough that its width covers [viewportWidth] for a
     * crisp fit, but never so little that a side runs past the texture limit.
     * A power of two, as the decoders want.
     */
    fun sampleForWidth(width: Int, viewportWidth: Int): Int {
        var sample = 1
        // Halve while the width still covers the screen when halved again.
        while (viewportWidth > 0 && width / (sample * 2) >= viewportWidth) sample *= 2
        // And keep halving if a side is still too big to decode at all.
        while (width / sample > MAX_DIMENSION) sample *= 2
        return sample
    }

    /**
     * Every band of every page in order, given each page's [sizes] (width to
     * height) and the [viewportWidth] to fit. A page taller than [MAX_BAND]
     * once shrunk is cut into several; a short one is a single band.
     */
    fun plan(sizes: List<Pair<Int, Int>>, viewportWidth: Int): List<Band> {
        val bands = ArrayList<Band>()
        for ((page, size) in sizes.withIndex()) {
            val (width, height) = size
            if (width <= 0 || height <= 0) continue
            val sample = sampleForWidth(width, viewportWidth)
            val bandSrc = (MAX_BAND * sample).coerceAtLeast(1)
            var top = 0
            while (top < height) {
                val bottom = minOf(top + bandSrc, height)
                bands.add(Band(page, width, top, bottom, sample))
                top = bottom
            }
        }
        return bands
    }
}
