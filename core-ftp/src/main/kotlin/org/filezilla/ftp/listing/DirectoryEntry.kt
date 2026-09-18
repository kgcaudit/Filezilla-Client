package org.filezilla.ftp.listing

/**
 * How precise a listed timestamp is.
 *
 * This is not bookkeeping for its own sake. A Unix `LIST` line carries only
 * minutes, and a line for an old file carries only a date, so the engine has
 * to know whether it still needs `MDTM` to get a usable timestamp -- which is
 * the decision `CDirentry::has_time()` drives in `filetransfer.cpp:346-352`.
 */
enum class TimeAccuracy { NONE, DAYS, MINUTES, SECONDS }

/** A timestamp from a listing, in UTC milliseconds, with its precision. */
data class EntryTime(val epochMillis: Long, val accuracy: TimeAccuracy)

/**
 * One entry in a remote directory listing. Port of `CDirentry`.
 */
data class DirectoryEntry(
    val name: String,
    val size: Long = -1,
    val isDirectory: Boolean = false,
    val isLink: Boolean = false,
    /** Link target, when the listing gave one. */
    val linkTarget: String? = null,
    val time: EntryTime? = null,
    val permissions: String? = null,
    val ownerGroup: String? = null,
) {
    /** True when a date is known, whatever its precision. */
    val hasDate: Boolean get() = time != null && time.accuracy != TimeAccuracy.NONE

    /**
     * True when a time of day is known, not just a date. When this is false and
     * timestamps are being preserved, the engine falls back to `MDTM`.
     */
    val hasTime: Boolean get() = time != null && time.accuracy >= TimeAccuracy.MINUTES
}
