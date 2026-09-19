package org.filezilla.android.storage

import android.net.Uri

/**
 * Where a finished download is to be written: a folder the user granted, plus
 * the folders to make inside it.
 *
 * Downloading a remote folder means mirroring its shape, so a record has to
 * carry more than the granted tree -- `/HDD1/Vision/clips/a.mp4` has to land in
 * `Vision/clips/`, not loose among everything else.
 *
 * The journal stores this as one opaque string, because the destination is an
 * Android concept that `:core-ftp` neither reads nor should have to grow a
 * column for. It is kept in the URI's fragment, which a tree URI does not
 * otherwise use, so a record written before folder downloads existed -- a bare
 * tree URI -- decodes as [subPath] empty and keeps working untouched.
 */
data class DownloadDestination(
    val tree: Uri,
    val subPath: List<String> = emptyList(),
    /** What to do if a file of the same name is already there. */
    val onConflict: ConflictChoice = ConflictChoice.DEFAULT,
) {

    /** True when this is an ordinary folder rather than a granted tree. */
    val isLocalPath: Boolean get() = tree.scheme == "file"

    /** The folder's path, for a local destination. */
    val localPath: String get() = requireNotNull(tree.path) { "a file URI with no path" }

    fun encode(): String {
        val path = subPath.joinToString("/") { Uri.encode(it) }
        // The choice rides in the fragment beside the path, separated by a
        // character no encoded segment can contain. Both are the app's own
        // business, which is why they go here rather than into a column the
        // engine would have to know about.
        val fragment = when {
            subPath.isEmpty() && onConflict == ConflictChoice.DEFAULT -> return tree.toString()
            else -> "$path|${onConflict.name}"
        }
        return tree.buildUpon().encodedFragment(fragment).build().toString()
    }

    companion object {
        /** Reads back what [encode] wrote, and a plain tree URI as well. */
        fun decode(stored: String): DownloadDestination {
            val uri = Uri.parse(stored)
            val fragment = uri.encodedFragment
            val tree = uri.buildUpon().fragment(null).build()
            if (fragment.isNullOrEmpty()) return DownloadDestination(tree)

            // A fragment written before conflicts existed carries only the
            // path, so those records keep working and get the default.
            val path = fragment.substringBefore('|')
            val choice = fragment.substringAfter('|', "")
                .let { name -> ConflictChoice.entries.firstOrNull { it.name == name } }
                ?: ConflictChoice.DEFAULT

            val segments = path.split('/')
                .filter { it.isNotEmpty() }
                .map { Uri.decode(it) }
            return DownloadDestination(tree, segments, choice)
        }
    }
}
