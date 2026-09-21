package org.filezilla.android.archive

import java.io.Closeable
import java.io.InputStream

/**
 * One thing inside an archive, as a list can show it.
 *
 * Deliberately not the format's own record. A zip entry and an alz entry
 * describe the same facts in different bytes, and everything above this --
 * the list, the extract, the screen -- has no business knowing which it is
 * looking at.
 */
data class ArchiveEntry(
    /** Path inside the archive, separated by `/` whatever the format used. */
    val path: String,
    /** Uncompressed size, or -1 when the archive does not say. */
    val size: Long = -1,
    val compressedSize: Long = -1,
    val isDirectory: Boolean = false,
    val modifiedMillis: Long? = null,
    val encrypted: Boolean = false,
    /**
     * Why this entry cannot be unpacked, or null when it can.
     *
     * Carried per entry rather than refused for the whole archive: an
     * archive with one entry in a method this app does not do is still an
     * archive whose other entries are wanted. The list shows all of them
     * and says which are out of reach, which is a great deal better than
     * "cannot open" over the lot.
     */
    val unreadable: Unreadable? = null,
) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')

    /** The folder this sits in, as a path inside the archive. */
    val parent: String get() = path.trimEnd('/').substringBeforeLast('/', "")

    enum class Unreadable { COMPRESSION_METHOD, ENCRYPTED_METHOD }
}

/**
 * An archive that can be listed and read from, one entry at a time.
 *
 * Reading only. Nothing here writes an archive of its own format: see
 * [Archives] for why creating is always a zip.
 */
interface Archive : Closeable {

    val entries: List<ArchiveEntry>

    /**
     * The bytes of one entry, decompressed and decrypted.
     *
     * [password] is asked for only when [ArchiveEntry.encrypted]; a wrong
     * one raises [WrongPassword] rather than handing back rubbish, because
     * these formats encrypt without authenticating and rubbish is exactly
     * what a wrong key produces.
     */
    fun open(entry: ArchiveEntry, password: CharArray? = null): InputStream
}

/** The password was wrong, or none was given for an entry that needs one. */
class WrongPassword(message: String) : java.io.IOException(message)

/** The file is not the format it was opened as. */
class NotAnArchive(message: String) : java.io.IOException(message)
