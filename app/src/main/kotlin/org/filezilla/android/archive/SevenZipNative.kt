package org.filezilla.android.archive

import java.io.File

/**
 * The LZMA SDK's reference 7z extractor, reached over JNI.
 *
 * 7z's methods -- LZMA, LZMA2, PPMd and the branch filters -- are the
 * reference decoder's alone, and a mistake in porting them to Kotlin is
 * silent corruption. So the reference decoder is compiled from Igor
 * Pavlov's public-domain LZMA SDK (see `app/src/main/cpp/sevenz/
 * lzma-sdk-license.txt`) and driven here. It reads only; nothing in this
 * app writes a 7z.
 *
 * The reference decoder has no AES, so an encrypted 7z lists -- its header
 * is in the clear -- but its entries come back marked unreadable rather
 * than as something a password could open, because no password would help.
 *
 * Native, and only for arm64, the same as [RarNative]. On a 32-bit-only
 * device [available] is false and 7z is simply one format it cannot open.
 */
object SevenZipNative {

    /** What listing and extraction report back to. */
    interface Sink {
        /**
         * One entry, during a list. [encrypted] means an AES folder this
         * reader has no decoder for; [unsupported] means a compression
         * method it does not do. Either way the entry cannot be unpacked.
         */
        fun entry(
            name: String,
            size: Long,
            isDirectory: Boolean,
            modifiedMillis: Long,
            encrypted: Boolean,
            unsupported: Boolean,
        )

        /** Bytes unpacked so far and the file just written, during an extract. */
        fun progress(doneBytes: Long, totalBytes: Long, name: String)

        /** Asked before each file during an extract; true stops it. */
        fun cancelled(): Boolean
    }

    val available: Boolean = runCatching { System.loadLibrary("olo7z"); nativeVersion() > 0 }
        .getOrDefault(false)

    private external fun nativeVersion(): Int

    private external fun nativeList(path: String, sink: Sink): Int

    private external fun nativeExtract(
        path: String,
        destDir: String,
        picks: Array<String>?,
        totalBytes: Long,
        skipExisting: Boolean,
        sink: Sink,
    ): Int

    /** The entries in [file], or throws [NotAnArchive] with the reader's own code. */
    fun list(file: File): List<ArchiveEntry> {
        val entries = mutableListOf<ArchiveEntry>()
        val code = nativeList(file.path, object : Sink {
            override fun entry(
                name: String,
                size: Long,
                isDirectory: Boolean,
                modifiedMillis: Long,
                encrypted: Boolean,
                unsupported: Boolean,
            ) {
                val path = if (isDirectory) name.trimEnd('/') + "/" else name
                entries += ArchiveEntry(
                    path = path,
                    size = if (isDirectory) -1 else size,
                    isDirectory = isDirectory,
                    modifiedMillis = modifiedMillis.takeIf { it > 0 },
                    encrypted = encrypted,
                    unreadable = when {
                        encrypted -> ArchiveEntry.Unreadable.ENCRYPTED_METHOD
                        unsupported -> ArchiveEntry.Unreadable.COMPRESSION_METHOD
                        else -> null
                    },
                )
            }

            override fun progress(doneBytes: Long, totalBytes: Long, name: String) = Unit
            override fun cancelled() = false
        })
        if (code != 0) throw NotAnArchive("${file.name} is not a 7z this reader opens (code ${-code})")
        return entries
    }

    /** How an extract ended. */
    enum class Result { OK, CANCELLED, FAILED }

    /**
     * Unpacks [file] into [into]; [picks] null means everything.
     *
     * The native side reports bytes and asks [sink] whether to stop before
     * every file, so a large archive is watchable and stoppable between
     * files -- a single solid folder still decodes as a unit.
     */
    fun extract(
        file: File,
        into: File,
        picks: Set<String>?,
        totalBytes: Long,
        skipExisting: Boolean,
        sink: Sink,
    ): Result {
        val code = nativeExtract(
            file.path,
            into.path,
            picks?.toTypedArray(),
            totalBytes,
            skipExisting,
            sink,
        )
        return when (code) {
            0 -> Result.OK
            1 -> Result.CANCELLED
            else -> Result.FAILED
        }
    }
}
