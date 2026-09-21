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

    /** Told the entry about to be read and how many are done. */
    fun interface Progress {
        fun at(done: Int, total: Int, path: String)
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
        for ((done, entry) in files.withIndex()) {
            if (cancelled()) return ExtractResult(written, skipped, cancelled = true)
            onProgress.at(done, files.size, entry.path)

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

            val outcome = runCatching {
                archive.open(entry, password).use { source ->
                    target.outputStream().use { sink -> source.copyTo(sink) }
                }
            }
            outcome.onSuccess {
                written += target
                entry.modifiedMillis?.let { runCatching { target.setLastModified(it) } }
            }.onFailure { failure ->
                // A file half written is worse than no file: it opens, and
                // it is wrong.
                runCatching { target.delete() }
                skipped += ExtractResult.Skipped(
                    entry.path,
                    if (failure is WrongPassword) ExtractResult.Reason.PASSWORD
                    else ExtractResult.Reason.FAILED,
                )
            }
        }
        onProgress.at(files.size, files.size, "")
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
