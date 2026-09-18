package org.filezilla.ftp.listing

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * Parser for `MLSD` lines (RFC 3659), a port of
 * `CDirectoryListingParser::ParseAsMlsd`.
 *
 * `MLSD` is the format worth having: it is machine-readable by design, its
 * timestamps are UTC to the second, and its sizes are exact. Where a server
 * offers it, none of the guesswork in the `LIST` parsers applies.
 *
 * Parsing is strict, as in the original: any malformed fact rejects the whole
 * line rather than producing a half-right entry.
 */
object MlsdParser {

    /**
     * Parses one `MLSD` line.
     *
     * @return the entry, or null when the line is malformed or describes the
     *   current/parent directory, which callers must not list.
     */
    fun parse(line: String): DirectoryEntry? {
        // "facts;facts;... filename" -- exactly one space separates them, and
        // the filename may itself contain spaces.
        val separator = line.indexOf(' ')
        if (separator < 0) return null
        val facts = line.substring(0, separator)
        val name = line.substring(separator + 1)
        if (facts.isEmpty() || name.isEmpty()) return null

        var size = -1L
        var isDir = false
        var isLink = false
        var linkTarget: String? = null
        var time: EntryTime? = null
        var permissions = ""

        // The facts may arrive in any order, so owner and group are collected
        // separately and assembled afterwards.
        var owner = ""
        var ownerName = ""
        var group = ""
        var groupName = ""
        var user = ""
        var uid = ""
        var gid = ""

        var start = 0
        while (start < facts.length) {
            var delim = facts.indexOf(';', start)
            if (delim < 0) {
                delim = facts.length
            } else if (delim < start + 3) {
                return null
            }
            val eq = facts.indexOf('=', start)
            if (eq < 0 || eq < start + 1 || eq > delim) return null

            val fact = facts.substring(start, eq).lowercase()
            val value = facts.substring(eq + 1, delim)

            when (fact) {
                "type" -> {
                    val colon = value.indexOf(':')
                    val prefix = (if (colon < 0) value else value.substring(0, colon)).lowercase()
                    when {
                        prefix == "dir" && colon < 0 -> isDir = true
                        prefix == "os.unix=slink" || prefix == "os.unix=symlink" -> {
                            // A symlink to a file and one to a directory look
                            // identical here, so both are treated as directories
                            // and the difference is discovered on CWD.
                            isDir = true
                            isLink = true
                            if (colon >= 0) linkTarget = value.substring(colon + 1)
                        }
                        (prefix == "cdir" || prefix == "pdir") && colon < 0 -> return null
                    }
                }

                "size" -> {
                    size = value.toLongOrNull() ?: return null
                    if (size < 0) return null
                }

                "modify" -> time = parseTimeval(value) ?: return null

                "create" -> if (time == null) {
                    time = parseTimeval(value) ?: return null
                }

                "perm" -> if (value.isNotEmpty()) {
                    permissions = if (permissions.isEmpty()) value else "$value ($permissions)"
                }

                "unix.mode" -> permissions =
                    if (permissions.isEmpty()) value else "$permissions ($value)"

                "unix.owner" -> owner = value
                "unix.ownername" -> ownerName = value
                "unix.group" -> group = value
                "unix.groupname" -> groupName = value
                "unix.user" -> user = value
                "unix.uid" -> uid = value
                "unix.gid" -> gid = value
            }
            start = delim + 1
        }

        val ownerGroup = buildString {
            append(
                ownerName.ifEmpty { owner.ifEmpty { user.ifEmpty { uid } } },
            )
            val g = groupName.ifEmpty { group.ifEmpty { gid } }
            if (g.isNotEmpty()) {
                append(' ')
                append(g)
            }
        }

        return DirectoryEntry(
            name = name,
            size = size,
            isDirectory = isDir,
            isLink = isLink,
            linkTarget = linkTarget,
            time = time,
            permissions = permissions.ifEmpty { null },
            ownerGroup = ownerGroup.ifEmpty { null },
        )
    }

    /**
     * Parses an RFC 3659 time-val: `YYYYMMDDHHMMSS` in UTC, optionally with
     * fractional seconds, which are accepted and discarded.
     */
    private fun parseTimeval(value: String): EntryTime? {
        val digits = value.substringBefore('.')
        if (digits.length != 14 || !digits.all { it.isDigit() }) return null
        return runCatching {
            val parsed = LocalDateTime.parse(digits, TIMEVAL)
            EntryTime(parsed.toInstant(ZoneOffset.UTC).toEpochMilli(), TimeAccuracy.SECONDS)
        }.getOrNull()
    }

    private val TIMEVAL: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendValue(ChronoField.YEAR, 4)
        .appendValue(ChronoField.MONTH_OF_YEAR, 2)
        .appendValue(ChronoField.DAY_OF_MONTH, 2)
        .appendValue(ChronoField.HOUR_OF_DAY, 2)
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
        .toFormatter()
}
