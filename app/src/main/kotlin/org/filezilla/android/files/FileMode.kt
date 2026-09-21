package org.filezilla.android.files

/**
 * Unix permission bits, read out of whatever the server said and written
 * back as the three digits `SITE CHMOD` wants.
 *
 * Reading them is the awkward half, because a listing can describe
 * permissions three different ways and two of them are not a mode at all:
 *
 *  - `LIST` gives the `ls -l` column, `drwxr-xr-x`, type character first.
 *  - `MLSD` gives `unix.mode`, already octal: `0755`.
 *  - `MLSD` also gives RFC 3659's `perm` fact, `fdelcmp` -- which is not
 *    the file's permissions at all but a list of what *this login* may do
 *    with it. Parsed as a mode it is nonsense, and a dialog that opened on
 *    nonsense would write it back.
 *
 * A server that sends both gets them joined, as `fdelcmp (0755)`, so the
 * octal has to be found rather than assumed to be the whole string.
 */
@JvmInline
value class FileMode(val bits: Int) {

    /** The three digits `SITE CHMOD` takes. */
    override fun toString(): String = bits.toString(8).padStart(3, '0')

    fun allows(who: Who, what: What): Boolean = bits and mask(who, what) != 0

    fun with(who: Who, what: What, allowed: Boolean): FileMode {
        val mask = mask(who, what)
        return FileMode(if (allowed) bits or mask else bits and mask.inv())
    }

    /** `rwxr-xr-x`, for showing what the digits mean. */
    fun asLetters(): String = buildString {
        for (who in Who.entries) {
            for (what in What.entries) {
                append(if (allows(who, what)) what.letter else '-')
            }
        }
    }

    enum class Who(val shift: Int) { OWNER(6), GROUP(3), EVERYONE(0) }

    enum class What(val bit: Int, val letter: Char) { READ(4, 'r'), WRITE(2, 'w'), EXECUTE(1, 'x') }

    companion object {

        /** What a file gets when the server would not say. */
        val FILE = FileMode(0b110_100_100)

        /** And a folder, which is useless without execute. */
        val FOLDER = FileMode(0b111_101_101)

        private fun mask(who: Who, what: What): Int = what.bit shl who.shift

        /**
         * The mode in [permissions], or null when it does not contain one.
         *
         * Null is a real answer and not a failure: a server that sends only
         * `perm=fdelcmp` has said what this login may do and nothing about
         * the file's bits. Guessing a mode from that and offering it for
         * editing would write a guess to the server.
         */
        fun of(permissions: String?): FileMode? {
            val text = permissions?.trim().orEmpty()
            if (text.isEmpty()) return null
            return octalIn(text) ?: lettersIn(text)
        }

        /**
         * An octal run, which may be three digits or four.
         *
         * Four means a leading setuid/setgid/sticky digit. It is kept out of
         * the mode rather than carried: nothing here can edit it, and
         * sending back three digits where the server had four is how a
         * sticky bit gets silently cleared -- see [extraDigitIn].
         */
        private fun octalIn(text: String): FileMode? {
            val run = Regex("""(?<![0-9])([0-7]{3,4})(?![0-9])""").find(text) ?: return null
            val digits = run.groupValues[1]
            return FileMode(digits.takeLast(3).toInt(8))
        }

        /**
         * The leading setuid/setgid/sticky digit, if the server named one.
         *
         * Carried back into what is sent so that editing who may read a
         * file does not also clear its sticky bit. Read from either shape,
         * because the `ls` column carries those bits too -- as the letter
         * that replaces execute.
         */
        fun extraDigitIn(permissions: String?): String? {
            val text = permissions.orEmpty()
            Regex("""(?<![0-9])([0-7]{4})(?![0-9])""").find(text)?.let {
                return it.groupValues[1].take(1).takeIf { digit -> digit != "0" }
            }
            val field = letterFieldIn(text) ?: return null
            var extra = 0
            if (field[2] in "sS") extra = extra or 4
            if (field[5] in "sS") extra = extra or 2
            if (field[8] in "tT") extra = extra or 1
            return extra.toString(8).takeIf { extra != 0 }
        }

        private fun letterFieldIn(text: String): String? =
            Regex("""[bcdlps-]?([-rwxsStT]{9})(?![-rwxsStT])""").find(text)?.groupValues?.get(1)

        /**
         * The `ls -l` column, with or without its leading type character.
         *
         * The execute positions can hold four other letters. Lower case `s`
         * and `t` mean the special bit is set *and* so is execute; upper
         * case `S` and `T` mean the special bit is set and execute is not.
         * Reading every one of them as "not execute" is what the first
         * version did, and on a sticky shared folder -- `drwxrwxrwt`, which
         * is what /tmp looks like -- it would have shown 776 and then
         * written 776, taking away the execute bit that let anyone enter it.
         */
        private fun lettersIn(text: String): FileMode? {
            val field = letterFieldIn(text) ?: return null
            var bits = 0
            for (who in Who.entries) {
                for (what in What.entries) {
                    val at = 8 - (who.shift + what.bit.countTrailingZeroBits())
                    val letter = field[at]
                    val set = letter == what.letter ||
                        (what == What.EXECUTE && letter in "st")
                    if (set) bits = bits or (what.bit shl who.shift)
                }
            }
            return FileMode(bits)
        }
    }
}
