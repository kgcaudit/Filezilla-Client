package org.filezilla.android.ui

import org.filezilla.ftp.listing.DirectoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bytes in the units people read them in.
 *
 * Decimal (MB = 1000 kB), which is what a phone's own downloader and file
 * manager show. The binary units were more precise and read as a typo.
 */
fun formatSize(bytes: Long): String {
    if (bytes < 0) return ""
    if (bytes < 1000) return "$bytes B"
    val units = listOf("kB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1000
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

/** Transfer speed, in the same units as [formatSize]. */
fun formatSpeed(bytesPerSecond: Long): String = "${formatSize(bytesPerSecond)}/s"

/**
 * A duration as a person would say it: `4:07`, or `1:25:04` past an hour.
 *
 * Seconds are kept even for long estimates, because the figure is a moving
 * one and a minutes-only readout looks frozen.
 */
fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, secs)
    }
}

private val listingFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
private val logFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

fun formatEntryTime(entry: DirectoryEntry): String =
    entry.time?.let { listingFormat.format(Date(it.epochMillis)) } ?: ""

fun formatLogTime(millis: Long): String = logFormat.format(Date(millis))

/** A date and time as the listing shows them, for any millisecond value. */
fun formatTimestamp(millis: Long): String = listingFormat.format(Date(millis))

/** Joins a directory and a name into a remote path, without a doubled slash. */
fun remotePathOf(directory: String, name: String): String =
    if (directory == "/" || directory.isEmpty()) "/$name" else "${directory.trimEnd('/')}/$name"
