package org.filezilla.android.archive

import java.io.File
import java.io.IOException

/** What came of unpacking, whether or not all of it worked. */
data class ExtractResult(
    val written: List<File> = emptyList(),
    /** Entries that could not be written, and why, in the order tried. */
    val skipped: List<Skipped> = emptyList(),
    /** True when somebody called it off, so [written] is not the whole archive. */
    val cancelled: Boolean = false,
) {
    data class Skipped(val path: String, val reason: Reason)

    enum class Reason {
        /** The name would have put the file outside the chosen folder. */
        ESCAPES,
        /** Compressed or encrypted in a way this app does not read. */
        UNREADABLE,
        /** A password was needed and none opened it. */
        PASSWORD,
        /** The read or the write failed part way. */
        FAILED,
    }
}

/**
 * Unpacking an archive into a folder.
 *
 * Every destination goes through [ArchivePaths.resolve], so a name that
 * would climb out of the chosen folder is skipped and reported rather
 * than followed -- see that file for why an archive's names are not ours
 * to trust.
 *
 * Folders are made from the paths rather than from directory records,
 * because ALZip writes none: a real ALZip archive of a folder full of
 * files contains only the files, each with the folder in its name. A
 * version of this that waited for a directory entry unpacked that
 * archive into nothing at all.
 *
 * Nothing here is Android, so all of it is tested directly.
 */
object ArchiveExtract {

    /**
     * How far the unpack has got, in bytes, and the file being written now.
     *
     * Bytes rather than a file count: an archive can be one enormous file,
     * and a count that reads "0 of 1" for the ten minutes it takes to write
     * a four-gigabyte video is a progress bar that looks frozen. Total is 0
     * when the archive does not say its sizes, and the bar is left
     * indeterminate rather than made up.
     */
    fun interface Progress {
        fun at(doneBytes: Long, totalBytes: Long, path: String)
    }

    /**
     * Writes [picks] -- or everything, when empty -- from [archive] into [into].
     *
     * One entry's failure does not stop the rest: a wrong password on one
     * file in an archive of thirty is a reason to skip that file, not to
     * hand back nothing. The caller is told what was skipped so it can say
     * so plainly.
     */
    fun run(
        archive: Archive,
        into: File,
        picks: Collection<String> = emptyList(),
        password: CharArray? = null,
        cancelled: () -> Boolean = { false },
        onProgress: Progress = Progress { _, _, _ -> },
    ): ExtractResult {
        val wanted = if (picks.isEmpty()) archive.entries else {
            val chosen = picks.toSet()
            // A chosen folder brings what is under it, which is what
            // picking a folder means everywhere else in this app.
            val prefixes = archive.entries.filter { it.path in chosen && it.isDirectory }
                .map { it.path }
            archive.entries.filter { entry ->
                entry.path in chosen || prefixes.any { entry.path.startsWith(it) }
            }
        }

        val written = mutableListOf<File>()
        val skipped = mutableListOf<ExtractResult.Skipped>()

        for (folder in ArchivePaths.foldersIn(wanted)) {
            ArchivePaths.resolve(into, folder)?.mkdirs()
        }

        val files = wanted.filterNot { it.isDirectory }
        val totalBytes = files.sumOf { it.size.coerceAtLeast(0) }
        var doneBytes = 0L

        for (entry in files) {
            // Between files, and again inside the copy below: a four-gigabyte
            // file must be stoppable before it finishes, or "stop" is a
            // button that does nothing for ten minutes.
            if (cancelled()) return ExtractResult(written, skipped, cancelled = true)
            onProgress.at(doneBytes, totalBytes, entry.path)

            if (entry.unreadable != null) {
                skipped += ExtractResult.Skipped(entry.path, ExtractResult.Reason.UNREADABLE)
                continue
            }
            val target = ArchivePaths.resolve(into, entry.path)
            if (target == null) {
                skipped += ExtractResult.Skipped(entry.path, ExtractResult.Reason.ESCAPES)
                continue
            }
            target.parentFile?.mkdirs()

            var stoppedHere = false
            val startedAt = doneBytes
            val outcome = runCatching {
                archive.open(entry, password).use { source ->
                    target.outputStream().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        // Report at most every megabyte, not every buffer: a
                        // four-gigabyte file is sixty thousand buffers, and
                        // pushing state that often would drop frames doing it.
                        var sinceReport = 0L
                        while (true) {
                            if (cancelled()) { stoppedHere = true; break }
                            val n = source.read(buffer)
                            if (n < 0) break
                            sink.write(buffer, 0, n)
                            doneBytes += n
                            sinceReport += n
                            if (sinceReport >= 1_000_000L) {
                                onProgress.at(doneBytes, totalBytes, entry.path)
                                sinceReport = 0L
                            }
                        }
                    }
                }
            }
            if (stoppedHere) {
                // A file the stop caught mid-write is not a file: delete it,
                // and do not count the bytes that did land.
                runCatching { target.delete() }
                return ExtractResult(written, skipped, cancelled = true)
            }
            outcome.onSuccess {
                written += target
                entry.modifiedMillis?.let { runCatching { target.setLastModified(it) } }
            }.onFailure { failure ->
                // A file half written is worse than no file: it opens, and
                // it is wrong.
                runCatching { target.delete() }
                doneBytes = startedAt
                skipped += ExtractResult.Skipped(
                    entry.path,
                    if (failure is WrongPassword) ExtractResult.Reason.PASSWORD
                    else ExtractResult.Reason.FAILED,
                )
            }
        }
        onProgress.at(totalBytes, totalBytes, "")
        return ExtractResult(written, skipped)
    }

    /**
     * Whether [archive] has anything that will need a password.
     *
     * Asked before starting, so the question comes once at the front
     * rather than at the seventh file of thirty.
     */
    fun needsPassword(archive: Archive, picks: Collection<String> = emptyList()): Boolean =
        archive.entries.any { entry ->
            !entry.isDirectory && entry.encrypted &&
                entry.unreadable != ArchiveEntry.Unreadable.ENCRYPTED_METHOD &&
                (picks.isEmpty() || entry.path in picks)
        }

    /** Reads one entry whole, which is how a password is checked before the rest. */
    @Throws(IOException::class)
    fun opens(archive: Archive, entry: ArchiveEntry, password: CharArray?): Boolean =
        runCatching { archive.open(entry, password).use { it.read() } }
            .fold(onSuccess = { true }, onFailure = { if (it is WrongPassword) false else throw it })
}
