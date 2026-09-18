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
data class DownloadDestination(val tree: Uri, val subPath: List<String> = emptyList()) {

    fun encode(): String =
        if (subPath.isEmpty()) {
            tree.toString()
        } else {
            // Each segment is encoded on its own, so that a name containing a
            // slash or a percent cannot forge a folder boundary.
            tree.buildUpon()
                .encodedFragment(subPath.joinToString("/") { Uri.encode(it) })
                .build()
                .toString()
        }

    companion object {
        /** Reads back what [encode] wrote, and a plain tree URI as well. */
        fun decode(stored: String): DownloadDestination {
            val uri = Uri.parse(stored)
            val fragment = uri.encodedFragment
            val tree = uri.buildUpon().fragment(null).build()
            if (fragment.isNullOrEmpty()) return DownloadDestination(tree)
            val segments = fragment.split('/')
                .filter { it.isNotEmpty() }
                .map { Uri.decode(it) }
            return DownloadDestination(tree, segments)
        }
    }
}
