package org.filezilla.android.ui

import org.filezilla.android.files.FilePath
import org.filezilla.android.storage.DownloadConflict
import org.filezilla.android.storage.numberedName
import java.io.File

/**
 * What a paste inside the phone would land on top of.
 *
 * A paste used to ask nothing. The file operations refuse to write over
 * anything -- which is what makes them safe -- so pasting onto a name that
 * was already there failed outright, the pane re-listed unchanged, and the
 * screen looked exactly as though the paste had never been attempted.
 *
 * Reported in the same shape a download conflict is, so the same dialog
 * answers both: the question a user is being asked is the same question, and
 * two dialogs that ask it differently is how the two come to disagree.
 * "Remote" here is the incoming item, whichever side it came from.
 */
fun localPasteConflicts(target: String, source: String, names: List<String>): List<DownloadConflict> =
    names.mapNotNull { name ->
        val existing = File(FilePath.child(target, name))
        if (!existing.exists()) return@mapNotNull null
        val incoming = File(FilePath.child(source, name))
        DownloadConflict(
            displayName = name,
            // Only a file has a length worth comparing; a directory's is
            // whatever the filesystem uses for its own bookkeeping, and
            // showing that beside a name reads as a claim about its contents.
            remoteSize = incoming.length().takeIf { incoming.isFile },
            remoteModifiedMillis = incoming.lastModified().takeIf { it > 0 },
            localSize = if (existing.isFile) existing.length() else -1,
            localModifiedMillis = existing.lastModified(),
        )
    }

/**
 * A name like [name] that nothing in [target] is using.
 *
 * Counts upward rather than stopping at "(1)", because keeping both twice is
 * ordinary -- and a second paste that quietly landed back on "(1)" would be
 * the same silent overwrite this whole path exists to prevent.
 */
fun freeNameIn(target: String, name: String): String {
    if (!File(FilePath.child(target, name)).exists()) return name
    var n = 1
    while (File(FilePath.child(target, numberedName(name, n))).exists()) n++
    return numberedName(name, n)
}
