package org.filezilla.android.ui

/**
 * The letters down the side of a long list, and where each one starts.
 *
 * A folder with three thousand films in it is a wall of rows: the scroll bar
 * is a hairline that says nothing about how far there is to go, and reaching
 * the ㅅs means dragging and guessing. Korean file managers answer that with
 * an index rail -- ㄱ ㄴ ㄷ ㄹ down the right edge -- and so does this.
 *
 * Read from the rows rather than from a fixed alphabet. A fixed rail has to
 * be complete, so it offers letters no row starts with and the tap does
 * nothing; this offers what is there. It also means the rail matches whatever
 * the list is actually sorted by, because it is built from the sorted rows.
 */
object ScrollIndex {

    /** The nineteen leading consonants, in the order Unicode puts them. */
    private const val LEADS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

    /**
     * The doubles folded onto their singles.
     *
     * An index with ㄲ on it is an index nobody uses: no Korean dictionary,
     * phone book or file manager separates them, and a rail of nineteen
     * letters on a phone is a rail of letters too small to hit.
     */
    private val FOLDED = mapOf('ㄲ' to 'ㄱ', 'ㄸ' to 'ㄷ', 'ㅃ' to 'ㅂ', 'ㅆ' to 'ㅅ', 'ㅉ' to 'ㅈ')

    private const val HANGUL_FIRST = 0xAC00
    private const val HANGUL_LAST = 0xD7A3
    private const val PER_LEAD = 588

    /** Everything that is neither a letter nor a syllable shares one heading. */
    const val OTHER = "#"

    /**
     * What a name files under.
     *
     * A syllable gives its leading consonant, a Latin letter gives itself in
     * capitals, and everything else -- digits, punctuation, scripts this rail
     * has no letters for -- goes under [OTHER] rather than each getting a
     * heading of its own.
     */
    fun headingFor(name: String): String {
        val first = name.trimStart().firstOrNull() ?: return OTHER
        val code = first.code
        if (code in HANGUL_FIRST..HANGUL_LAST) {
            val lead = LEADS[(code - HANGUL_FIRST) / PER_LEAD]
            return (FOLDED[lead] ?: lead).toString()
        }
        if (first in 'ㄱ'..'ㅎ') {
            // A bare consonant, which a filename can start with.
            return (FOLDED[first] ?: first).toString()
        }
        if (first.isLetter()) return first.uppercaseChar().toString()
        return OTHER
    }

    /** One heading and the row it starts at. */
    data class Stop(val label: String, val row: Int)

    /**
     * The rail, in the order the rows are already in.
     *
     * Runs, not groups: a heading appears once per run of rows that share it,
     * so a list with folders hoisted to the top gets its letters twice, which
     * is what the list actually looks like. Collapsing them would send a tap
     * on ㄱ to the folders when the rows under the finger are files.
     */
    fun stopsFor(names: List<String>): List<Stop> {
        val out = mutableListOf<Stop>()
        var last: String? = null
        names.forEachIndexed { row, name ->
            val heading = headingFor(name)
            if (heading != last) {
                out += Stop(heading, row)
                last = heading
            }
        }
        return out
    }

    /**
     * The rail thinned to [limit] entries, keeping the ends.
     *
     * A phone is about forty letters tall at a legible size, and a list of
     * mixed scripts can want more stops than that. Dropping every nth keeps
     * the rail evenly spaced and keeps first and last, which are the two a
     * finger goes for.
     */
    fun thin(stops: List<Stop>, limit: Int): List<Stop> {
        if (limit <= 0 || stops.size <= limit) return stops
        if (limit == 1) return listOf(stops.first())
        return (0 until limit).map { i ->
            stops[(i * (stops.size - 1)) / (limit - 1)]
        }.distinct()
    }
}
