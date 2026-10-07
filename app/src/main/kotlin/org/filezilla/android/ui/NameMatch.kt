package org.filezilla.android.ui

/**
 * How the filter box decides which rows a query keeps.
 *
 * Plain text is matched the forgiving way it always was -- found anywhere in
 * the name, case ignored -- so typing "report" still turns up "Q3-REPORT.pdf".
 * The moment a query carries a wildcard it means a shape instead: "*" stands
 * for any run of characters and "?" for a single one, matched against the whole
 * name, so "*.jpg" keeps the pictures and "IMG_????.png" the four-digit ones.
 * One box, two intents, told apart by whether a wildcard is present -- no mode
 * to switch, nothing to learn before the plain case works.
 *
 * A pure function on the name, so the rule can be read and tested on its own
 * rather than inferred from a filtered screen.
 */
object NameMatch {

    /**
     * A test for one row name, built once from [query] and then applied to
     * every row -- so a wildcard query compiles its pattern a single time, not
     * once per file in the folder.
     */
    fun predicate(query: String): (String) -> Boolean {
        val q = query.trim()
        if (q.isEmpty()) return { true }
        if (q.none { it == '*' || it == '?' }) {
            return { it.contains(q, ignoreCase = true) }
        }
        val regex = globRegex(q)
        return { regex.matches(it) }
    }

    /** Whether [name] passes [query]. The one-shot form of [predicate]. */
    fun matches(name: String, query: String): Boolean = predicate(query)(name)

    /**
     * A glob turned into a whole-name, case-insensitive regex: "*" to any run,
     * "?" to one character, and every other character taken literally -- the
     * dot in "*.jpg" included, so it matches a real dot and not any character.
     */
    private fun globRegex(glob: String): Regex {
        val pattern = buildString {
            for (ch in glob) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(ch.toString()))
                }
            }
        }
        return Regex(pattern, RegexOption.IGNORE_CASE)
    }
}
