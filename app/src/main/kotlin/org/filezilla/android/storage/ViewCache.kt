package org.filezilla.android.storage

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Copies of server files kept only long enough to look at them.
 *
 * Separate from [PartialFiles] because the two answer different questions.
 * A partial file is half of something the user asked to keep, and losing it
 * costs them the bytes again. A file here is a copy of something that is
 * still on the server, so losing it costs nothing but a re-fetch -- which
 * is why it lives in the cache directory, where Android may delete it when
 * the phone runs short, and why it is capped rather than kept.
 *
 * The cap matters more than it looks. A viewing cache with no limit is a
 * feature that quietly eats the phone's storage, and it is the kind of
 * cause nobody finds: the app's own listing says a few hundred megabytes
 * and nothing in it explains why.
 *
 * What identifies a copy is the server, the path, the size and the
 * modification time. Not the path alone: a file edited on the server keeps
 * its path, and opening yesterday's copy of it would be the app quietly
 * lying about what is there now.
 */
class ViewCache(
    context: Context,
    private val limitBytes: Long = DEFAULT_LIMIT_BYTES,
) {

    private val root = File(context.cacheDir, "viewing")

    /**
     * Where this exact version of this file would be kept.
     *
     * The name is a digest, with the real extension put back on the end.
     * The digest keeps the name safe whatever the server called the file --
     * a remote path can hold anything, including separators -- and the
     * extension has to survive, because it is what tells the app opening
     * it what kind of file this is.
     */
    fun fileFor(key: Key): File {
        root.mkdirs()
        // Letters and digits only, and short. Taken from a remote name, an
        // "extension" can be anything the server allows: "../../x" has a
        // last dot, and what follows it is "/x", which would have put a
        // path separator into a file name built to be safe.
        val extension = key.name.substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() && it.length <= 16 && it.all { c -> c.isLetterOrDigit() } }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(listOf(key.serverKey, key.path, key.size.toString(), key.modifiedMillis.toString())
                .joinToString("\u0000").toByteArray())
            .take(16)
            .joinToString("") { "%02x".format(it) }
        return File(root, if (extension == null) digest else "$digest.$extension")
    }

    /** The copy, if this exact version is already here and whole. */
    fun readyFile(key: Key): File? =
        fileFor(key).takeIf { it.isFile && (key.size < 0 || it.length() == key.size) }

    /**
     * Marks a file as just used, so eviction takes the others first.
     *
     * The modification time is the record, because it is the one thing a
     * file carries that survives the app being killed. Nothing else here
     * would know the order.
     */
    fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    /**
     * Brings the cache back under its limit, oldest use first.
     *
     * [keep] is spared whatever its age: it is the file that has just been
     * fetched, or is being looked at right now, and evicting it would mean
     * fetching it again immediately.
     */
    fun evictDownTo(keep: File? = null) {
        val files = root.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= limitBytes) return
            if (keep != null && file == keep) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    fun totalBytes(): Long = root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0

    /** Throws the lot away, for a person who wants their storage back. */
    fun clear() {
        root.listFiles()?.forEach { it.delete() }
    }

    /**
     * Which version of which file on which server.
     *
     * [modifiedMillis] is in here on purpose: a file edited on the server
     * keeps its path and its name, and a cache keyed on those alone would
     * go on showing the copy from before the edit.
     */
    data class Key(
        val serverKey: String,
        val path: String,
        val name: String,
        val size: Long,
        val modifiedMillis: Long,
    )

    companion object {
        /**
         * How much may be held.
         *
         * Enough for a film and the documents around it, and small enough
         * that somebody who never thinks about it never notices. The
         * transfer settings sheet says how much is in use and offers to
         * empty it, because a number nobody can see is a number nobody can
         * act on.
         */
        const val DEFAULT_LIMIT_BYTES = 512L * 1024 * 1024
    }
}
