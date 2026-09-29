package org.filezilla.android.files

import java.io.File
import java.security.MessageDigest

/**
 * The fingerprint of a file: a hash read straight off its bytes.
 *
 * What it is for is telling whether two files are the very same bytes -- a
 * copy that arrived whole, a download that was not cut short, a part-file run
 * that joined back correctly -- by comparing a short string rather than the
 * whole file. Three algorithms, because which one a checksum was published in
 * is not the reader's to choose: SHA-256 for anything that matters, and MD5
 * and SHA-1 for matching the sums older sites still hand out.
 */
object Checksums {

    /** The algorithms offered, newest and safest first. */
    enum class Algorithm(val label: String, val digest: String) {
        SHA256("SHA-256", "SHA-256"),
        SHA1("SHA-1", "SHA-1"),
        MD5("MD5", "MD5"),
    }

    /** How far the hashing has got, in bytes. */
    fun interface Progress {
        fun at(doneBytes: Long, totalBytes: Long)
    }

    /**
     * The hash of [file] as lowercase hex, or null if it was stopped partway or
     * could not be read. Read in one pass with a modest buffer, so a large file
     * does not have to fit in memory to be summed.
     */
    fun of(
        file: File,
        algorithm: Algorithm,
        cancelled: () -> Boolean = { false },
        onProgress: Progress = Progress { _, _ -> },
    ): String? = runCatching {
        val digest = MessageDigest.getInstance(algorithm.digest)
        val total = file.length().coerceAtLeast(0)
        var done = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (cancelled()) return null
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                done += read
                onProgress.at(done, total)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
