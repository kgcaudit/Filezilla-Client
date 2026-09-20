package org.filezilla.android.transfer

import org.filezilla.android.data.AppPreferences
import org.filezilla.android.data.MovedFolder
import org.filezilla.android.data.SiteDao
import org.filezilla.android.files.EmptyFolders
import org.filezilla.ftp.protocol.LogLevel
import java.io.File

/**
 * The second half of a move across devices.
 *
 * The first half is per file: each transfer removes what it carried, once it
 * has arrived. That leaves the folders, which are not transfers and so have
 * nothing to remove them -- and a folder that was empty when it was cut never
 * had a file to be carried by in the first place. Cutting a folder and
 * pasting it on a server used to leave the entire shape of it behind, which
 * is exactly what the user found.
 *
 * Run when the queue drains, from the service, because that is the moment the
 * move is actually over. The list of folders to look at is written down when
 * the paste is made, so it survives the app being killed halfway through.
 *
 * Only empty folders go -- see [EmptyFolders]. A folder still holding a file
 * is a folder whose file did not get there, and it is left alone. So the
 * worst this can do is remove a folder that had nothing in it.
 */
class MoveCleanup(
    private val preferences: AppPreferences,
    private val sites: SiteDao,
    private val transfers: TransferManager,
    private val log: (LogLevel, String) -> Unit,
) {

    /**
     * Clears away what the finished move emptied, and forgets the list.
     *
     * Forgotten whether or not anything was removed: the queue has drained,
     * so this move is over. A folder still standing is one whose files did
     * not arrive, and re-queueing those is the user's decision -- which
     * writes a fresh list of its own.
     */
    suspend fun sweep() {
        val pending = preferences.foldersToClearAfterMove
        if (pending.isEmpty()) return
        preferences.foldersToClearAfterMove = emptySet()

        for (folder in pending.filter { it.siteId == null }) {
            val removed = EmptyFolders.prune(File(folder.path))
            if (removed.isNotEmpty()) {
                log(LogLevel.STATUS, "Moved ${File(folder.path).name}; cleared ${removed.size} emptied folders")
            }
        }

        for ((siteId, folders) in pending.filter { it.siteId != null }.groupBy { it.siteId }) {
            sweepServer(siteId!!, folders)
        }
    }

    /**
     * The same rule on the far side, in one session for the whole site.
     *
     * FTP has no recursive remove: `RMD` refuses a directory with anything in
     * it, which happens to be precisely the rule this wants -- so a folder
     * whose files did not arrive is protected by the protocol itself.
     */
    private suspend fun sweepServer(siteId: String, folders: List<MovedFolder>) {
        val site = sites.byId(siteId) ?: return
        runCatching {
            transfers.browse(site) { session ->
                // Deepest first, so clearing a child lets its parent go in
                // the same pass.
                for (path in folders.map { it.path }.sortedByDescending { it.count { c -> c == '/' } }) {
                    runCatching { session.removeDirectory(path) }
                }
            }
        }.onFailure {
            log(LogLevel.ERROR, "Could not reach the server to clear the folders a move emptied")
        }
    }
}
