package org.filezilla.android.viewer

import org.filezilla.android.files.NaturalOrder

/**
 * Finding the next book in a series, and putting a folder of them in order.
 *
 * Comics come as a run of files whose names end in a number -- "Title 1",
 * "Title 2", "Title 10", or "ep01", "ep02" -- and a reader that finishes one
 * wants the next. That is not the plain alphabetical next: "Title 10" sorts
 * before "Title 2" letter by letter, and a folder may hold two series at once.
 * So the trailing number is compared by value, and only within the same name:
 * the part before the number and the part after it (including the extension)
 * must match, so "A 2" follows "A 1" and "B 1" is left alone.
 */
object ComicSeries {

    /** One name split at its trailing number, for comparing within a series. */
    private data class Volume(val prefix: String, val number: Long, val suffix: String)

    private val DIGITS = Regex("""\d+""")

    private fun parse(name: String): Volume? {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        // The last run of digits in the stem is the volume number; what sits
        // before it names the series, what sits after it (and the extension)
        // has to match too, so "Title 1 extra" and "Title 1" are not confused.
        val match = DIGITS.findAll(stem).lastOrNull() ?: return null
        val number = match.value.toLongOrNull() ?: return null
        val prefix = stem.substring(0, match.range.first)
        val after = stem.substring(match.range.last + 1)
        return Volume(prefix, number, after + extension)
    }

    /**
     * The name in [candidates] that continues [current], or null if none does.
     *
     * The next volume of the same series: same name and extension, the
     * smallest number greater than this one's. A gap is stepped over, so 3
     * follows 1 when there is no 2.
     */
    fun nextVolume(current: String, candidates: List<String>): String? {
        val here = parse(current) ?: return null
        return candidates.asSequence()
            .filter { it != current }
            .mapNotNull { name ->
                parse(name)
                    ?.takeIf { it.prefix == here.prefix && it.suffix == here.suffix && it.number > here.number }
                    ?.let { name to it.number }
            }
            .minByOrNull { it.second }
            ?.first
    }

    /** Orders names so a number reads by its value: 2 before 10, not after. */
    val NATURAL: Comparator<String> = NaturalOrder.byName

    /** The one natural order the whole app uses; kept here for its old callers. */
    fun naturalCompare(a: String, b: String): Int = NaturalOrder.compare(a, b)
}
