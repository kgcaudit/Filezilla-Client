package org.filezilla.android.ui

import org.filezilla.android.files.FilePath
import org.filezilla.android.files.LocalOperations
import org.filezilla.android.storage.ConflictChoice
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

/**
 * Puts the held items down in [target], one at a time, settling each name
 * already there by [choice].
 *
 * Pulled out on its own for the overwrite that lost data: the thing being
 * replaced can contain the thing replacing it -- a folder moved up onto a
 * same-named parent, so the source sits inside the destination. Deleting the
 * destination first would take the source down with it and lose the lot, which
 * is exactly what a user saw -- a folder "11" holding another "11", the inner
 * one pasted up over the outer, overwrite chosen, and both gone. So when the
 * source is inside the destination it is [set aside][overwriteFromWithin]
 * under a free name first, the destination removed, and the set-aside item put
 * in its place. The plain case still removes the destination first, because
 * the operations refuse to write over anything and replacing is a decision
 * taken here rather than a rule bent down in them.
 */
fun pasteLocally(
    mode: ClipboardMode,
    paths: List<String>,
    target: String,
    choice: ConflictChoice,
) {
    for (path in paths) {
        val name = FilePath.name(path)
        val existing = File(FilePath.child(target, name))
        var asName: String? = null
        if (existing.exists()) {
            when (choice) {
                ConflictChoice.SKIP -> continue
                ConflictChoice.OVERWRITE -> {
                    if (FilePath.isWithin(FilePath.normalize(path), existing.absolutePath)) {
                        overwriteFromWithin(mode, path, target, name)
                        continue
                    }
                    LocalOperations.delete(existing.absolutePath)
                }
                ConflictChoice.KEEP_BOTH -> asName = freeNameIn(target, name)
            }
        }
        when (mode) {
            ClipboardMode.COPY -> LocalOperations.copy(path, target, asName)
            ClipboardMode.MOVE -> LocalOperations.move(path, target, asName)
        }
    }
}

/**
 * Overwrites [name] in [target] with [path], where [path] lives inside what is
 * being overwritten.
 *
 * The order is the whole point: the source is set beside the destination under
 * a free name first, so removing the destination cannot reach it, and only
 * then is the set-aside item renamed into place. Done the obvious way round --
 * delete then move -- the delete would swallow the source and leave nothing to
 * move.
 */
private fun overwriteFromWithin(mode: ClipboardMode, path: String, target: String, name: String) {
    val aside = freeNameIn(target, name)
    when (mode) {
        ClipboardMode.COPY -> LocalOperations.copy(path, target, aside)
        ClipboardMode.MOVE -> LocalOperations.move(path, target, aside)
    }
    LocalOperations.delete(FilePath.child(target, name))
    LocalOperations.rename(FilePath.child(target, aside), name)
}
