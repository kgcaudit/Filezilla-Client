package org.filezilla.android.ui

import org.filezilla.ftp.listing.DirectoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bytes in the units people read them in. */
fun formatSize(bytes: Long): String {
    if (bytes < 0) return ""
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

private val listingFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
private val logFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

fun formatEntryTime(entry: DirectoryEntry): String =
    entry.time?.let { listingFormat.format(Date(it.epochMillis)) } ?: ""

fun formatLogTime(millis: Long): String = logFormat.format(Date(millis))

/** Joins a directory and a name into a remote path, without a doubled slash. */
fun remotePathOf(directory: String, name: String): String =
    if (directory == "/" || directory.isEmpty()) "/$name" else "${directory.trimEnd('/')}/$name"
