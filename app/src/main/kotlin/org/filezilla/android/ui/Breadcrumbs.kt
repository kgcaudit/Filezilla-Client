package org.filezilla.android.ui

import org.filezilla.android.files.FilePath

/** One step of a path, and where tapping it goes. */
data class Crumb(val label: String, val path: String)

/**
 * A path as a row of places rather than a line of text.
 *
 * The header used to print the path whole, ellipsised from the right, so six
 * folders down it read "/storage/emulated/0/Download/Invoi..." -- which names
 * the one folder the user is definitely looking at and hides every folder
 * they might want to go back to. Walking back up meant pressing up once per
 * level and watching a listing load each time.
 *
 * [rootPath] is where the trail stops: a volume on the phone, or the server's
 * own root. It is not always a prefix of [path] -- a pane can be pointed at
 * a folder outside every volume it knows about -- so a path that falls
 * outside it is still given a complete trail, from "/" down, rather than a
 * half one or none.
 */
fun breadcrumbs(path: String, rootPath: String, rootLabel: String): List<Crumb> {
    val here = FilePath.normalize(path)
    val root = FilePath.normalize(rootPath)
    val inside = FilePath.isWithin(here, root)

    val start = if (inside) root else FilePath.ROOT
    val label = if (inside) rootLabel else FilePath.ROOT
    val crumbs = mutableListOf(Crumb(label, start))

    val rest = when {
        !inside -> here
        start == FilePath.ROOT -> here
        else -> here.removePrefix(start)
    }
    var walked = start
    for (segment in FilePath.segments(rest)) {
        walked = FilePath.child(walked, segment)
        crumbs += Crumb(segment, walked)
    }
    return crumbs
}
