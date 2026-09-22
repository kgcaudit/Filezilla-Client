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
 * AES-256 is built into the native decoder, so a password-protected 7z
 * whose header is readable -- the common case -- lists, and extracts once
 * the password is given. An archive whose header itself is encrypted does
 * not list and is one this reader cannot open.
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
        password: String?,
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
                    // Encrypted is readable now -- with the password -- so it
                    // asks for one rather than being marked out of reach; only
                    // a method the decoder does not do is unreadable.
                    encrypted = encrypted,
                    unreadable = if (unsupported) ArchiveEntry.Unreadable.COMPRESSION_METHOD else null,
                )
            }

            override fun progress(doneBytes: Long, totalBytes: Long, name: String) = Unit
            override fun cancelled() = false
        })
        if (code != 0) throw NotAnArchive("${file.name} is not a 7z this reader opens (code ${-code})")
        return entries
    }

    /** How an extract ended. */
    enum class Result { OK, CANCELLED, WRONG_PASSWORD, FAILED }

    /**
     * Unpacks [file] into [into]; [picks] null means everything.
     *
     * [password] is used for the encrypted folders; a wrong or missing one
     * on an encrypted archive comes back [Result.WRONG_PASSWORD] so the
     * caller can ask again. The native side reports bytes and asks [sink]
     * whether to stop before every file, so a large archive is watchable and
     * stoppable between files -- a single solid folder still decodes as a unit.
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
            file.path,
            into.path,
            picks?.toTypedArray(),
            password?.let { String(it) },
            totalBytes,
            skipExisting,
            sink,
        )
        return when (code) {
            0 -> Result.OK
            1 -> Result.CANCELLED
            2 -> Result.WRONG_PASSWORD
            else -> Result.FAILED
        }
    }
}
