package org.filezilla.android.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.filezilla.android.data.AppPreferences
import org.filezilla.android.data.TrashEntry
import org.filezilla.android.files.FilePath
import org.filezilla.android.files.LocalOperations
import org.filezilla.android.files.StorageVolumes

/**
 * The phone's trash: the files waiting there, picking them for a bulk action,
 * and moving one back or erasing it for good.
 *
 * Lifted out of [MainViewModel]. The work is self-contained -- a delete moves a
 * file into a hidden folder on its own volume and records where it came from,
 * and a restore moves it back -- but for two things only the view model can
 * answer, handed in so this side need not reach back into it: [recentSource]
 * names the volume a path sits on, and [relistLocalPanes] redraws the open
 * folders after a restore lands a file back into one.
 *
 * [scope] is the view model's own, so the writes live exactly as long as it
 * does.
 */
class TrashController(
    private val app: Application,
    private val volumes: StorageVolumes,
    private val preferences: AppPreferences,
    private val scope: CoroutineScope,
    private val recentSource: (String) -> String,
    private val relistLocalPanes: () -> Unit,
) {

    /**
     * The trashed files, most recently deleted first -- what the trash screen
     * shows. Held as state so the screen redraws as items are restored,
     * removed, or the trash is emptied; the stored list is the source of truth.
     */
    var entries by mutableStateOf<List<TrashEntry>>(emptyList())
        private set

    /** Whether the trash screen is picking rows for a bulk restore or erase. */
    var selecting by mutableStateOf(false)
        private set

    /** The trash paths currently ticked. */
    var selection by mutableStateOf<Set<String>>(emptySet())
        private set

    /** The old single trash folder, kept for reading entries stored before the move. */
    private val legacyTrashDir: java.io.File by lazy {
        java.io.File(app.getExternalFilesDir(null) ?: app.filesDir, "trash")
    }

    /**
     * The trash folder for the volume [path] is on -- a hidden folder at that
     * volume's own root.
     *
     * On its own volume on purpose. The app's external files dir looked like
     * the same volume but is a separate FUSE domain, so a rename into it
     * failed and the move fell back to copying the whole file byte for byte:
     * deleting a several-gigabyte video took as long as copying one. A trash
     * beside the file, on the same volume, makes a delete the instant rename
     * it should be, and a restore the same. A file on no known volume falls
     * back to the app's own folder.
     */
    private fun trashDirFor(path: String): java.io.File {
        val volume = volumes.volumePaths().firstOrNull { FilePath.isWithin(path, it) }
        return if (volume != null) java.io.File(volume, TRASH_DIR_NAME) else legacyTrashDir
    }

    /**
     * Moves a local file into the trash rather than erasing it, keeping a
     * note of where it came from so it can be put back. Runs on the write
     * thread the delete already switched to, and touches no UI state -- the
     * screen re-reads storage when it opens.
     */
    fun stashLocal(path: String) {
        val src = java.io.File(FilePath.normalize(path))
        if (!src.exists()) return
        val dir = trashDirFor(src.absolutePath).also { it.mkdirs() }
        val name = Trash.stash(dir, src)
        preferences.addTrash(
            java.io.File(dir, name).absolutePath,
            src.absolutePath,
            src.isDirectory,
            System.currentTimeMillis(),
        )
    }

    /** Re-reads the trash from storage, for when the screen opens; starts it unselected. */
    fun refresh() {
        entries = preferences.trash()
        exitSelection()
    }

    // ------------------------------------------------------- selection

    /** Turns a row's tick on or off, entering selection mode on the first. */
    fun toggleSelected(entry: TrashEntry) {
        selecting = true
        selection = if (entry.trashPath in selection) {
            selection - entry.trashPath
        } else {
            selection + entry.trashPath
        }
    }

    /** Ticks every row, or clears them all when they are already all ticked. */
    fun toggleSelectAll() {
        val all = entries.map { it.trashPath }.toSet()
        selecting = true
        selection = if (all.isNotEmpty() && selection.containsAll(all)) emptySet() else all
    }

    /** Leaves selection mode, forgetting the ticks. */
    fun exitSelection() {
        selecting = false
        selection = emptySet()
    }

    private fun selected(): List<TrashEntry> = entries.filter { it.trashPath in selection }

    /** Puts every ticked file back where it came from, then leaves selection mode. */
    fun restoreSelected() {
        val picked = selected()
        if (picked.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) { picked.forEach(::restoreEntryBlocking) }
            entries = preferences.trash()
            exitSelection()
            relistLocalPanes()
        }
    }

    /** Erases every ticked file for good, then leaves selection mode. */
    fun deleteSelectedForever() {
        val picked = selected()
        if (picked.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) { picked.forEach(::deleteEntryBlocking) }
            entries = preferences.trash()
            exitSelection()
        }
    }

    /**
     * The file on disk that backs a trash entry, for its thumbnail and its
     * restore. An absolute path is taken as is; a bare name is an entry from
     * before the per-volume move, read against the old app-private folder.
     */
    fun fileFor(entry: TrashEntry): java.io.File =
        if (entry.trashPath.startsWith("/")) {
            java.io.File(entry.trashPath)
        } else {
            java.io.File(legacyTrashDir, entry.trashPath)
        }

    /**
     * The volume a trashed file originally sat on, named the way the storage
     * list names it -- shown so a file can be told apart from its namesakes.
     */
    fun sourceOf(originalPath: String): String = recentSource(originalPath)

    /**
     * Puts a trashed file back where it came from. If its old folder is gone
     * it is recreated; if a file now sits at the old name the restored one is
     * given a free name beside it, so nothing is overwritten. Re-lists the
     * local panes when done, so a restore into the open folder shows at once.
     */
    fun restore(entry: TrashEntry) {
        scope.launch {
            withContext(Dispatchers.IO) { restoreEntryBlocking(entry) }
            entries = preferences.trash()
            relistLocalPanes()
        }
    }

    /** Erases one trashed file for good and drops it from the list. */
    fun deleteForever(entry: TrashEntry) {
        scope.launch {
            withContext(Dispatchers.IO) { deleteEntryBlocking(entry) }
            entries = preferences.trash()
        }
    }

    // One entry, shared by the single-row menu and the batch actions. Runs on
    // a caller's IO context.

    /**
     * Moves one entry's file back, dropping the record only when it is actually
     * back -- or was already gone. A failed move (a full card, an unwritable
     * old folder) keeps the entry, so a row with no file, and a file no row
     * names, never happen.
     */
    private fun restoreEntryBlocking(entry: TrashEntry) {
        val stored = fileFor(entry)
        val done = if (!stored.exists()) {
            true
        } else {
            runCatching {
                val original = java.io.File(entry.originalPath)
                val parent = original.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()
                Trash.restore(stored, parent?.absolutePath ?: FilePath.ROOT, original.name)
            }.isSuccess
        }
        if (done) preferences.removeTrash(entry.trashPath)
    }

    /** Erases one entry's file, keeping the record if the erase failed. */
    private fun deleteEntryBlocking(entry: TrashEntry) {
        val gone = runCatching { LocalOperations.delete(fileFor(entry).absolutePath) }.isSuccess
        if (gone) preferences.removeTrash(entry.trashPath)
    }

    /** Erases everything in the trash for good. */
    fun empty() {
        scope.launch {
            withContext(Dispatchers.IO) {
                // Each entry's own file, since the trash is now spread across a
                // hidden folder per volume rather than one folder to list.
                for (entry in preferences.trash()) {
                    runCatching { LocalOperations.delete(fileFor(entry).absolutePath) }
                }
                preferences.clearTrash()
            }
            entries = emptyList()
        }
    }

    companion object {
        const val TRASH_DIR_NAME = ".OloExplorerTrash"
    }
}
