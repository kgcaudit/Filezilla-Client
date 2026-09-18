package org.filezilla.ftp.listing

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Parsers for `LIST` output, used when a server does not offer `MLSD`.
 *
 * `LIST` output was meant for people to read, not programs, so it is
 * ambiguous and varies by server. FileZilla carries fourteen parsers for it,
 * covering VMS, IBM MVS, HP NonStop and other systems
 * (`directorylistingparser.cpp`). This port targets the servers in scope --
 * vsftpd, ProFTPD, Pure-FTPd, FileZilla Server, IIS -- which between them emit
 * only the Unix `ls -l` and DOS forms below. A line no parser recognises is
 * skipped rather than guessed at.
 */
object ListParser {

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    /** Tries each parser in turn. Returns null for a line none can read. */
    fun parseLine(line: String, clock: Clock = Clock.systemUTC()): DirectoryEntry? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        // "total 12" and similar preambles are not entries.
        if (trimmed.startsWith("total ", ignoreCase = true)) return null
        return parseUnix(trimmed, clock) ?: parseDos(trimmed, clock)
    }

    /**
     * Unix `ls -l`, the form every Unix FTP server emits:
     *
     * ```
     * drwxr-xr-x  2 root  other   512 Apr  8  1994 some dir
     * -rw-r--r--  1 root  other   531 Jan 25 00:17 some file
     * lrwxrwxrwx  1 root  other     7 Jan 25 00:17 link -> usr/bin
     * -rw-r--r--  1 root  other   531 2024-01-25 00:17 iso style
     * ```
     *
     * The owner and group fields are optional -- some servers omit the group,
     * some omit both -- so, as in the original, the parse is retried with
     * fewer of them until one fits.
     */
    fun parseUnix(line: String, clock: Clock = Clock.systemUTC()): DirectoryEntry? {
        val permissions = line.takeWhile { !it.isWhitespace() }
        if (permissions.length < 10) return null
        if (permissions[0] !in "bcdlps-") return null

        val isDir = permissions[0] == 'd' || permissions[0] == 'l'
        val isLink = permissions[0] == 'l'

        var rest = line.drop(permissions.length).trimStart()

        // Drop the link count when present.
        val linkCount = rest.takeWhile { !it.isWhitespace() }
        if (linkCount.isNotEmpty() && linkCount.all { it.isDigit() }) {
            rest = rest.drop(linkCount.length).trimStart()
        }

        // Owner and group are both optional and some servers add a third
        // field, so try three, then two, then one, then none, and keep the
        // first split that yields a valid size and date.
        outer@ for (ownerFields in 3 downTo 0) {
            val tokens = ArrayList<String>(ownerFields)
            var cursor = rest
            for (i in 0 until ownerFields) {
                val token = cursor.takeWhile { !it.isWhitespace() }
                if (token.isEmpty()) continue@outer
                tokens += token
                cursor = cursor.drop(token.length).trimStart()
            }

            val sizeToken = cursor.takeWhile { !it.isWhitespace() }
            if (sizeToken.isEmpty() || !sizeToken.all { it.isDigit() }) continue
            val size = sizeToken.toLongOrNull() ?: continue
            cursor = cursor.drop(sizeToken.length).trimStart()

            val dated = parseUnixDate(cursor, clock) ?: continue
            var name = dated.second.trim()
            if (name.isEmpty()) continue

            // ls -F appends a type marker that is not part of the name.
            if (name.last() in "/|*") name = name.dropLast(1)

            var target: String? = null
            if (isLink) {
                val arrow = name.indexOf(" -> ")
                if (arrow >= 0) {
                    target = name.substring(arrow + 4)
                    name = name.substring(0, arrow)
                }
            }
            if (name.isEmpty() || name == "." || name == "..") return null

            return DirectoryEntry(
                name = name,
                size = size,
                isDirectory = isDir,
                isLink = isLink,
                linkTarget = target,
                time = dated.first,
                permissions = permissions,
                ownerGroup = tokens.joinToString(" ").ifEmpty { null },
            )
        }
        return null
    }

    /**
     * Reads the date from a Unix listing and returns it with the remainder of
     * the line, which is the filename. Handles the three forms in scope:
     *
     * - `Apr  8  1994` -- an old file, date only
     * - `Jan 25 00:17` -- within the last months, no year given
     * - `2024-01-25 00:17` -- `ls --time-style=long-iso`
     */
    private fun parseUnixDate(input: String, clock: Clock): Pair<EntryTime, String>? {
        val first = input.takeWhile { !it.isWhitespace() }
        if (first.isEmpty()) return null
        val afterFirst = input.drop(first.length).trimStart()
        val second = afterFirst.takeWhile { !it.isWhitespace() }
        if (second.isEmpty()) return null
        val afterSecond = afterFirst.drop(second.length).trimStart()

        // ISO form: 2024-01-25 00:17
        if (first.length == 10 && first[4] == '-' && first[7] == '-') {
            val year = first.substring(0, 4).toIntOrNull() ?: return null
            val month = first.substring(5, 7).toIntOrNull() ?: return null
            val day = first.substring(8, 10).toIntOrNull() ?: return null
            val hm = parseHourMinute(second) ?: return null
            val time = utc(year, month, day, hm.first, hm.second, TimeAccuracy.MINUTES)
                ?: return null
            return time to afterSecond
        }

        val month = MONTHS[first.lowercase()] ?: return null
        val day = second.toIntOrNull()?.takeIf { it in 1..31 } ?: return null

        val third = afterSecond.takeWhile { !it.isWhitespace() }
        if (third.isEmpty()) return null
        val afterThird = afterSecond.drop(third.length).trimStart()

        parseHourMinute(third)?.let { (hour, minute) ->
            // No year given: the listing covers roughly the last six months,
            // so a date ahead of today belongs to last year. Port of the rule
            // in FileZilla's own parser tests.
            val today = LocalDate.now(clock)
            val year = if (month * 31 + day > today.monthValue * 31 + today.dayOfMonth + 1) {
                today.year - 1
            } else {
                today.year
            }
            val time = utc(year, month, day, hour, minute, TimeAccuracy.MINUTES) ?: return null
            return time to afterThird
        }

        val year = third.toIntOrNull()?.takeIf { it in 1000..9999 } ?: return null
        val time = utc(year, month, day, 0, 0, TimeAccuracy.DAYS) ?: return null
        return time to afterThird
    }

    /**
     * DOS and IIS listings:
     *
     * ```
     * 04-27-00  12:09PM       <DIR>          directory name
     * 04-27-00  12:09PM              123456 file name
     * ```
     */
    fun parseDos(line: String, clock: Clock = Clock.systemUTC()): DirectoryEntry? {
        val dateToken = line.takeWhile { !it.isWhitespace() }
        val date = parseDosDate(dateToken, clock) ?: return null
        var rest = line.drop(dateToken.length).trimStart()

        val timeToken = rest.takeWhile { !it.isWhitespace() }
        val timeOfDay = parseDosTime(timeToken) ?: return null
        rest = rest.drop(timeToken.length).trimStart()

        val sizeToken = rest.takeWhile { !it.isWhitespace() }
        if (sizeToken.isEmpty()) return null
        rest = rest.drop(sizeToken.length).trimStart()

        val isDir = sizeToken.equals("<DIR>", ignoreCase = true)
        val size = if (isDir) -1L else sizeToken.toLongOrNull() ?: return null

        val name = rest.trim()
        if (name.isEmpty() || name == "." || name == "..") return null

        val time = utc(
            date.first, date.second, date.third,
            timeOfDay.first, timeOfDay.second,
            TimeAccuracy.MINUTES,
        )
        return DirectoryEntry(name = name, size = size, isDirectory = isDir, time = time)
    }

    /** `MM-DD-YY` or `MM-DD-YYYY`, also accepting `/` as the separator. */
    private fun parseDosDate(token: String, clock: Clock): Triple<Int, Int, Int>? {
        val parts = token.split('-', '/')
        if (parts.size != 3) return null
        val month = parts[0].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        val day = parts[1].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
        val rawYear = parts[2].toIntOrNull() ?: return null
        val year = when {
            parts[2].length == 4 -> rawYear
            // Two-digit years: pivot on the current century, as IIS intends.
            rawYear < 70 -> 2000 + rawYear
            else -> 1900 + rawYear
        }
        if (year !in 1000..9999) return null
        // Guard against a Unix line whose name happens to start with digits.
        if (year > LocalDate.now(clock).year + 1) return null
        return Triple(year, month, day)
    }

    /** `HH:MM`, `HH:MMAM` or `HH:MMPM`. */
    private fun parseDosTime(token: String): Pair<Int, Int>? {
        val upper = token.uppercase()
        val meridiem = when {
            upper.endsWith("AM") -> "AM"
            upper.endsWith("PM") -> "PM"
            else -> null
        }
        val digits = if (meridiem != null) upper.dropLast(2) else upper
        val hm = parseHourMinute(digits) ?: return null
        var hour = hm.first
        if (meridiem != null) {
            if (hour !in 1..12) return null
            if (meridiem == "PM" && hour != 12) hour += 12
            if (meridiem == "AM" && hour == 12) hour = 0
        }
        if (hour !in 0..23) return null
        return hour to hm.second
    }

    /** `HH:MM`, rejecting anything else. */
    private fun parseHourMinute(token: String): Pair<Int, Int>? {
        val colon = token.indexOf(':')
        if (colon < 1) return null
        val hour = token.substring(0, colon).toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = token.substring(colon + 1).toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        return hour to minute
    }

    private fun utc(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        accuracy: TimeAccuracy,
    ): EntryTime? = runCatching {
        EntryTime(
            LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli(),
            accuracy,
        )
    }.getOrNull()
}
