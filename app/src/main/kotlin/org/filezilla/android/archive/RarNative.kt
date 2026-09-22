package org.filezilla.android.archive

import java.io.File

/**
 * The reference UnRAR decoder, reached over JNI.
 *
 * RAR 5.0 is not a format that can be read in Kotlin at any sane cost:
 * its LZ, its PPMd and its filter VM are the reference decoder's alone,
 * and a mistake in porting them is silent corruption. So the reference
 * decoder is compiled from Alexander Roshal's free UnRAR source (see
 * `app/src/main/cpp/unrar/license.txt`) and driven here. Its licence
 * allows reading RAR in any software and forbids building a RAR writer;
 * this reads only, and nothing in this app writes a RAR.
 *
 * Native, and only for arm64, which is every phone this app is put on. A
 * 32-bit-only device has no library to load, so [available] is false and
 * RAR is simply one format it cannot open -- everything else still works.
 */
object RarNative {

    /** What listing and extraction report back to. */
    interface Sink {
        /** One entry, during a list. */
        fun entry(name: String, size: Long, isDirectory: Boolean, modifiedMillis: Long, encrypted: Boolean)

        /** Bytes unpacked so far and the file being written, during an extract. */
        fun progress(doneBytes: Long, totalBytes: Long, name: String)

        /** Asked on every progress step during an extract; true stops it. */
        fun cancelled(): Boolean
    }

    val available: Boolean = runCatching { System.loadLibrary("olorar"); nativeVersion() > 0 }
        .getOrDefault(false)

    private external fun nativeVersion(): Int

    private external fun nativeList(path: String, sink: Sink): Int

    private external fun nativeExtract(
        path: String,
        destDir: String,
        picks: Array<String>?,
        password: String?,
        totalBytes: Long,
        skipExisting: Boolean,
        sink: Sink,
    ): Int

    /**
     * The first part of a multi-volume rar, which is where reading starts.
     *
     * `movie.part2.rar` opens the whole set from `movie.part1.rar`, and an
     * old-style `movie.r00` from `movie.rar`. A single `.rar` is its own
     * first volume. The native reader follows the chain from there.
     */
    fun firstVolume(file: File): File {
        val parent = file.parentFile ?: return file
        Regex("""(?i)^(.*\.part)(\d+)(\.rar)$""").matchEntire(file.name)?.let { m ->
            val one = "1".padStart(m.groupValues[2].length, '0')
            val first = File(parent, m.groupValues[1] + one + m.groupValues[3])
            return if (first.isFile) first else file
        }
        Regex("""(?i)^(.*)\.r\d\d$""").matchEntire(file.name)?.let { m ->
            val first = File(parent, m.groupValues[1] + ".rar")
            return if (first.isFile) first else file
        }
        return file
    }

    /** The entries in [file], or throws [NotAnArchive] with the reader's own code. */
    fun list(file: File): List<ArchiveEntry> {
        val entries = mutableListOf<ArchiveEntry>()
        val code = nativeList(firstVolume(file).path, object : Sink {
            override fun entry(name: String, size: Long, isDirectory: Boolean, modifiedMillis: Long, encrypted: Boolean) {
                val path = if (isDirectory) name.trimEnd('/') + "/" else name
                entries += ArchiveEntry(
                    path = path,
                    size = if (isDirectory) -1 else size,
                    isDirectory = isDirectory,
                    modifiedMillis = modifiedMillis.takeIf { it > 0 },
                    encrypted = encrypted,
                )
            }
            override fun progress(doneBytes: Long, totalBytes: Long, name: String) = Unit
            override fun cancelled() = false
        })
        if (code != 0) throw NotAnArchive("${file.name} is not a rar this reader opens (code ${-code})")
        return entries
    }

    /** How an extract ended. */
    enum class Result { OK, CANCELLED, WRONG_PASSWORD, FAILED }

    /**
     * Unpacks [file] into [into]; [picks] null means everything.
     *
     * The native side reports bytes and asks [sink] whether to stop on
     * every step, so a huge entry is both watchable and stoppable -- the
     * same as the Kotlin readers.
     */
    fun extract(
        file: File,
        into: File,
        picks: Set<String>?,
        password: CharArray?,
        totalBytes: Long,
        skipExisting: Boolean,
        sink: Sink,
    ): Result {
        val code = nativeExtract(
            firstVolume(file).path,
            into.path,
            picks?.toTypedArray(),
            password?.let { String(it) },
            totalBytes,
            skipExisting,
            sink,
        )
        return when {
            code == 0 -> Result.OK
            code == 1 -> Result.CANCELLED
            // ERAR_BAD_PASSWORD (24), ERAR_MISSING_PASSWORD (22).
            code == -24 || code == -22 -> Result.WRONG_PASSWORD
            else -> Result.FAILED
        }
    }
}
