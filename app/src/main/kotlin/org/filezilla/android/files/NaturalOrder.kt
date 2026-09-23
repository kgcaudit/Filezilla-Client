package org.filezilla.android.files

/**
 * Ordering names the way a person reads them, so a number counts by its value.
 *
 * Plain text order puts "10" before "2", because it compares character by
 * character and '1' comes before '2' -- which turns a folder of pages named
 * 1..12 into 1, 10, 11, 12, 2, 3, ... This compares each run of digits by the
 * number it spells instead, so 2 comes before 10 and a comic reads in order.
 * The rest of the name is compared letter by letter, without regard to case,
 * and Korean sorts by the Unicode order of its syllables, which is the 가나다
 * order. Leading zeros do not change a number's value ("01" is "1"), and a name
 * that differs only by them, or is otherwise identical, is given one fixed
 * order so a list never shuffles between two equal-looking rows.
 *
 * One place for this, used by the file list, the archive list and the image
 * viewer alike, so the same names line up the same way wherever they are shown.
 * Pure, because getting the digit arithmetic right is the whole point and a
 * screenshot never shows it is wrong until a page is out of place.
 */
object NaturalOrder {

    /** Compares two names by value-aware natural order; see the class note. */
    fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ni = i
                while (ni < a.length && a[ni].isDigit()) ni++
                var nj = j
                while (nj < b.length && b[nj].isDigit()) nj++
                // By value: with the leading zeros set aside, the longer run of
                // digits is the larger number, and equal-length runs compare as
                // text.
                val da = a.substring(i, ni).trimStart('0').ifEmpty { "0" }
                val db = b.substring(j, nj).trimStart('0').ifEmpty { "0" }
                val c = if (da.length != db.length) da.length - db.length else da.compareTo(db)
                if (c != 0) return c
                i = ni
                j = nj
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        // Whichever still has characters left is the longer, later name; and if
        // they run out together the names matched value for value, so the raw
        // text breaks the tie (a shorter "1" before a padded "01") rather than
        // leaving two rows free to swap places.
        val remaining = (a.length - i) - (b.length - j)
        return if (remaining != 0) remaining else a.compareTo(b)
    }

    /** The same as a comparator, for sorting names directly. */
    val byName: Comparator<String> = Comparator { a, b -> compare(a, b) }

    /** A comparator over any row, given how to read its name. */
    fun <T> by(name: (T) -> String): Comparator<T> = Comparator { a, b -> compare(name(a), name(b)) }
}
