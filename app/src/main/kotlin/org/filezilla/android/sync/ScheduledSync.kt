package org.filezilla.android.sync

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.filezilla.android.AppGraph
import org.filezilla.android.data.SiteEntity
import org.filezilla.android.data.SyncDirection
import org.filezilla.android.data.SyncJobEntity
import org.filezilla.android.data.SyncRunStatus
import org.filezilla.android.files.FilePath
import org.filezilla.android.files.LocalFileSource
import org.filezilla.android.files.SyncAction
import org.filezilla.android.files.SyncDiff
import org.filezilla.android.ui.syncLocally
import org.filezilla.android.storage.ConflictChoice
import org.filezilla.android.ui.RemoteLister
import org.filezilla.android.ui.SyncScan
import org.filezilla.ftp.listing.DirectoryEntry
import java.io.File

/**
 * How one scheduled run turned out, in counts the notice and the row read from.
 *
 * [queued] is files handed to the transfer queue, not files that have landed --
 * the queue carries them after this returns, so the notice says "N개 올리기
 * 시작" rather than claiming they are done. [made] and [deleted] happen here and
 * now, over the browse connection, so those are final.
 */
data class ScheduledSyncResult(
    val made: Int = 0,
    val queued: Int = 0,
    val deleted: Int = 0,
    val failed: Int = 0,
    /** True when the scan and diff found the target already mirrors the source. */
    val noop: Boolean = false,
    /** True when a scan hit a cap, so the mirror is of part of the tree only. */
    val truncated: Boolean = false,
) {
    /** Whether anything was handed off to run; false means nothing to drain. */
    val hasQueuedWork: Boolean get() = queued > 0

    val status: SyncRunStatus
        get() = when {
            failed > 0 && made == 0 && queued == 0 && deleted == 0 -> SyncRunStatus.FAILED
            failed > 0 -> SyncRunStatus.PARTIAL
            else -> SyncRunStatus.OK
        }
}

/**
 * Carries out a scheduled folder mirror with no UI, for the background worker.
 *
 * This is the one-shot mirror's engine run headless: it scans both ends with
 * [SyncScan], asks [SyncDiff] what differs, and carries the plan out through the
 * very same pieces the on-screen sync uses -- the transfer queue for the file
 * copies, a browse connection for the folders and the deletions, [syncLocally]
 * for the phone's own side. Nothing here decides *which* files differ; that is
 * all [SyncDiff], tested on its own.
 *
 * Only local-to-server and server-to-local, as [SyncJobEntity] says: a
 * scheduled run has one browse connection to give, and server-to-server would
 * need two.
 */
object ScheduledSync {

    /** Runs [job] against [site] once, returning what it did. */
    suspend fun run(graph: AppGraph, job: SyncJobEntity, site: SiteEntity): ScheduledSyncResult =
        when (job.directionEnum) {
            SyncDirection.UPLOAD -> upload(graph, job, site)
            SyncDirection.DOWNLOAD -> download(graph, job, site)
        }

    // ------------------------------------------------------------- scanning

    private fun localLister() = RemoteLister { LocalFileSource("").list(it) }

    private suspend fun scanLocal(root: String) = withContext(Dispatchers.IO) {
        SyncScan.scan(root = root, lister = localLister())
    }

    private suspend fun scanRemote(graph: AppGraph, site: SiteEntity, root: String) =
        graph.transfers.browse(site) { session ->
            SyncScan.scan(
                root = root,
                lister = RemoteLister { path ->
                    session.changeDirectory(path)
                    session.list()
                },
            )
        }

    // --------------------------------------------------------- phone -> server

    private suspend fun upload(graph: AppGraph, job: SyncJobEntity, site: SiteEntity): ScheduledSyncResult {
        val source = scanLocal(job.localRoot)
        val target = scanRemote(graph, site, job.remoteRoot)
        val plan = SyncDiff.diff(source.entries, target.entries, deleteExtras = job.deleteExtras)
        val truncated = source.truncated || target.truncated
        if (plan.isNoop) return ScheduledSyncResult(noop = true, truncated = truncated)

        var made = 0
        var failed = 0
        // Folders first, shallowest-first (the plan is already in that order),
        // so an empty folder is mirrored and a file never waits on a parent the
        // transfer would have to make anyway.
        val dirs = plan.actions.filterIsInstance<SyncAction.MakeDir>()
        if (dirs.isNotEmpty()) {
            graph.transfers.browse(site) { session ->
                for (dir in dirs) {
                    runCatching { session.createDirectory(FilePath.child(job.remoteRoot, dir.rel)) }
                        .onSuccess { made++ }
                        .onFailure { failed++ }
                }
            }
        }

        var queued = 0
        for (copy in plan.actions.filterIsInstance<SyncAction.Copy>()) {
            runCatching {
                graph.transfers.enqueueUpload(
                    site = site,
                    remotePath = FilePath.child(job.remoteRoot, copy.rel),
                    source = Uri.fromFile(File(FilePath.child(job.localRoot, copy.rel))),
                    totalBytes = copy.size,
                    // The source is the answer, so a file already on the server
                    // is replaced rather than resumed onto.
                    overwrite = true,
                )
            }.onSuccess { queued++ }.onFailure { failed++ }
        }

        val deleted = if (job.deleteExtras) {
            deleteRemoteExtras(graph, site, job.remoteRoot, topLevelDeletes(plan.actions))
        } else {
            0
        }

        return ScheduledSyncResult(made = made, queued = queued, deleted = deleted, failed = failed, truncated = truncated)
    }

    // --------------------------------------------------------- server -> phone

    private suspend fun download(graph: AppGraph, job: SyncJobEntity, site: SiteEntity): ScheduledSyncResult {
        val source = scanRemote(graph, site, job.remoteRoot)
        val target = scanLocal(job.localRoot)
        val plan = SyncDiff.diff(source.entries, target.entries, deleteExtras = job.deleteExtras)
        val truncated = source.truncated || target.truncated
        if (plan.isNoop) return ScheduledSyncResult(noop = true, truncated = truncated)

        var queued = 0
        for (copy in plan.actions.filterIsInstance<SyncAction.Copy>()) {
            val parentRel = copy.rel.substringBeforeLast('/', "")
            runCatching {
                graph.transfers.enqueueDownload(
                    site = site,
                    remotePath = FilePath.child(job.remoteRoot, copy.rel),
                    totalBytes = copy.size,
                    destinationTree = Uri.fromFile(File(job.localRoot)),
                    subPath = if (parentRel.isEmpty()) emptyList() else parentRel.split('/'),
                    onConflict = ConflictChoice.OVERWRITE,
                )
            }.onSuccess { queued++ }.onFailure { /* counted below via local result */ }
        }

        // The queue makes the folders a file needs but not the empty ones, and
        // it never removes anything -- so the phone's own side makes the empty
        // folders and removes the extras, with the copies left to the queue.
        val local = withContext(Dispatchers.IO) {
            syncLocally(
                actions = effectiveActions(plan.actions, job.deleteExtras),
                sourceRoot = job.remoteRoot,
                targetRoot = job.localRoot,
                copyFiles = false,
            )
        }

        return ScheduledSyncResult(
            made = local.made,
            queued = queued,
            deleted = local.deleted,
            failed = local.failed,
            truncated = truncated,
        )
    }

    // ----------------------------------------------------------- shared bits

    /** The plan's actions with deletions dropped unless this job asked for them. */
    private fun effectiveActions(actions: List<SyncAction>, deleteExtras: Boolean): List<SyncAction> =
        if (deleteExtras) actions else actions.filterNot { it is SyncAction.Delete }

    /**
     * The extras to remove as whole subtrees: a deleted path whose parent is
     * also deleted is covered by removing the parent, so only the shallowest of
     * each run is asked for. The same reduction the on-screen mirror makes.
     */
    private fun topLevelDeletes(actions: List<SyncAction>): List<SyncAction.Delete> {
        val deletes = actions.filterIsInstance<SyncAction.Delete>()
        val rels = deletes.map { it.rel }.toSet()
        return deletes.filter { it.rel.substringBeforeLast('/', "").let { p -> p.isEmpty() || p !in rels } }
    }

    private suspend fun deleteRemoteExtras(
        graph: AppGraph,
        site: SiteEntity,
        remoteRoot: String,
        extras: List<SyncAction.Delete>,
    ): Int {
        if (extras.isEmpty()) return 0
        return graph.transfers.browse(site) { session ->
            var removed = 0
            for (extra in extras) {
                val parentRel = extra.rel.substringBeforeLast('/', "")
                val directory = if (parentRel.isEmpty()) remoteRoot else FilePath.child(remoteRoot, parentRel)
                val name = extra.rel.substringAfterLast('/')
                runCatching {
                    if (extra.isDir) {
                        removeRemoteTree(session, directory, name)
                    } else {
                        session.deleteFile(FilePath.child(directory, name))
                    }
                }.onSuccess { removed++ }
            }
            removed
        }
    }

    /**
     * Removes a folder and everything under it, depth-first.
     *
     * A bare remove of a non-empty folder fails on most servers, so the tree is
     * walked and emptied from the bottom. Kept here rather than reusing the
     * view model's own recursive delete so the runner carries no UI dependency.
     */
    private fun removeRemoteTree(
        session: org.filezilla.android.transfer.RemoteSession,
        directory: String,
        name: String,
    ) {
        val path = FilePath.child(directory, name)
        val rows: List<DirectoryEntry> = runCatching {
            session.changeDirectory(path)
            session.list()
        }.getOrDefault(emptyList())
        for (row in rows) {
            if (row.name == "." || row.name == "..") continue
            if (row.isDirectory) removeRemoteTree(session, path, row.name) else session.deleteFile(FilePath.child(path, row.name))
        }
        session.removeDirectory(path)
    }
}
