package org.filezilla.android.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.filezilla.android.storage.ConflictChoice
import org.filezilla.android.storage.DownloadConflict
import org.filezilla.android.storage.DownloadDestination
import kotlinx.coroutines.launch
import org.filezilla.android.AppGraph
import org.filezilla.android.data.SiteEntity
import org.filezilla.android.transfer.ActiveProgress
import org.filezilla.android.transfer.LogLine
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.listing.DirectoryEntry

/** What the browse screen is showing, and whether it is busy or broken. */
data class BrowseState(
    val site: SiteEntity? = null,
    val path: String = "/",
    val entries: List<DirectoryEntry> = emptyList(),
    val loading: Boolean = false,
    val error: ConnectionFailure? = null,

    /** Names of the selected rows; empty means selection mode is off. */
    val selection: Set<String> = emptySet(),
    /** True once the user has entered selection mode, even with nothing picked. */
    val selecting: Boolean = false,
    val filter: String = "",
    val filterOpen: Boolean = false,
    /** The entry whose properties are being shown, if any. */
    val properties: DirectoryEntry? = null,
) {
    /** Selection survives a refresh only for rows that are still there. */
    fun prunedSelection(rows: List<DirectoryEntry>): Set<String> =
        selection intersect rows.map { it.name }.toSet()

    /**
     * Picks or unpicks one row.
     *
     * Selecting always turns selection mode on, because tapping a row's icon
     * is a way *into* selection mode -- otherwise the first tap would add to a
     * selection the screen is not showing, and appear to do nothing.
     */
    fun withToggled(name: String): BrowseState = copy(
        selecting = true,
        selection = if (name in selection) selection - name else selection + name,
    )
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = AppGraph.of(application)

    val sites: StateFlow<List<SiteEntity>> = graph.database.sites().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val transfers: StateFlow<List<TransferRecord>> = graph.transfers.observeTransfers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val log: StateFlow<List<LogLine>> get() = graph.log.log

    /**
     * The transfers moving right now, keyed by id.
     *
     * Two run at once, so the queue screen matches each card against this
     * rather than against one "current" transfer.
     */
    val active: StateFlow<Map<String, ActiveProgress>> = graph.transfers.activeTransfers

    var browse by mutableStateOf(BrowseState())
        private set

    /** Sort, view and folder options, remembered between runs. */
    var options by mutableStateOf(graph.preferences.browseOptions)
        private set

    /** The listing as the screen shows it: filtered, sorted, arranged. */
    val visibleEntries: List<DirectoryEntry>
        get() = BrowseListing.arrange(browse.entries, options, browse.filter)

    /** Whether transfers wait for Wi-Fi rather than using mobile data. */
    var wifiOnly by mutableStateOf(graph.preferences.wifiOnly)
        private set

    /**
     * Takes effect at once, not on the next app start: someone turning this on
     * because they just noticed a 1.9 GB download on mobile data means now.
     */
    fun applyWifiOnly(enabled: Boolean) {
        graph.preferences.wifiOnly = enabled
        wifiOnly = enabled
        graph.networkGate.policy = graph.preferences.networkPolicy
    }

    /**
     * Whether a transfer started now would actually run.
     *
     * False when the user asked for Wi-Fi only and the phone is on mobile
     * data. The queue screen uses it to explain why a transfer it was just
     * told to restart has gone back to waiting, rather than leaving the button
     * looking broken in exactly the case it is most likely to be pressed.
     */
    fun transfersAllowedNow(): Boolean = graph.networkGate.currentlyAllowed()

    /** Set once the user has picked a folder for downloads to land in. */
    var downloadFolder by mutableStateOf(graph.preferences.downloadFolder)
        private set

    val downloadFolderName: String?
        get() = downloadFolder?.let { graph.storage.displayNameOfTree(it) }

    // ----------------------------------------------------------------- sites

    /** Encrypts the password on its way to the row; see [SiteDraft]. */
    fun saveSite(draft: SiteDraft) {
        viewModelScope.launch { graph.database.sites().upsert(draft.toEntity(graph.passwords)) }
    }

    /** Opens a site for editing, decrypting its password for the form only. */
    fun draftOf(site: SiteEntity): SiteDraft = SiteDraft.of(site, graph.passwords)

    fun deleteSite(site: SiteEntity) {
        viewModelScope.launch {
            graph.database.sites().delete(site)
            if (browse.site?.id == site.id) browse = BrowseState()
        }
    }

    fun newSite(): SiteDraft = SiteDraft.blank()

    // ---------------------------------------------------------------- browse

    fun connect(site: SiteEntity) {
        browse = BrowseState(site = site, loading = true)
        load(site, site.initialPath?.takeIf { it.isNotBlank() })
    }

    fun openDirectory(name: String) {
        val site = browse.site ?: return
        load(site, remotePathOf(browse.path, name))
    }

    fun goUp() {
        val site = browse.site ?: return
        val parent = browse.path.trimEnd('/').substringBeforeLast('/', "")
        load(site, if (parent.isEmpty()) "/" else parent)
    }

    fun refresh() {
        val site = browse.site ?: return
        load(site, browse.path)
    }

    private fun load(site: SiteEntity, path: String?) {
        browse = browse.copy(site = site, loading = true, error = null)
        viewModelScope.launch {
            runCatching {
                graph.transfers.browse(site) { session ->
                    if (path != null) session.changeDirectory(path)
                    // PWD rather than the path that was asked for: the server
                    // decides where a relative or symlinked path landed, and
                    // building the next path on a guess is how a browser ends
                    // up listing the wrong directory.
                    val here = session.currentDirectory()
                    here to session.list()
                }
            }.onSuccess { (here, entries) ->
                browse = browse.copy(
                    path = here,
                    entries = entries,
                    // A directory the user moved into has nothing selected,
                    // and a refresh keeps only what is still there.
                    selection = if (here == browse.path) browse.prunedSelection(entries) else emptySet(),
                    loading = false,
                    error = null,
                )
            }.onFailure { error ->
                browse = browse.copy(
                    loading = false,
                    error = describeFailure(error, graph.networkGate.currentlyOnline()),
                )
            }
        }
    }

    fun applyOptions(next: BrowseOptions) {
        options = next
        graph.preferences.browseOptions = next
    }

    // ------------------------------------------------------------- selection

    fun toggleSelectionMode() {
        browse = if (browse.selecting) {
            browse.copy(selecting = false, selection = emptySet())
        } else {
            browse.copy(selecting = true)
        }
    }

    fun toggleSelected(name: String) {
        browse = browse.withToggled(name)
    }

    fun selectAll() {
        browse = browse.copy(selecting = true, selection = visibleEntries.map { it.name }.toSet())
    }

    fun clearSelection() {
        browse = browse.copy(selecting = false, selection = emptySet())
    }

    // ---------------------------------------------------------------- filter

    fun setFilter(text: String) {
        browse = browse.copy(filter = text)
    }

    fun toggleFilter() {
        // Closing the bar clears the filter: leaving a hidden one applied is
        // how a directory comes to look empty for no visible reason.
        browse = if (browse.filterOpen) {
            browse.copy(filterOpen = false, filter = "")
        } else {
            browse.copy(filterOpen = true)
        }
    }

    // ------------------------------------------------------------ properties

    fun showProperties(entry: DirectoryEntry?) {
        browse = browse.copy(properties = entry)
    }

    fun createDirectory(name: String) = mutate { it.createDirectory(name) }

    /**
     * Queues everything selected, walking into any selected folder.
     *
     * The walk needs the network, so this reports through [onQueued] rather
     * than returning a count: a folder of a thousand files takes a moment to
     * list, and the alternative is a button that appears to do nothing while
     * it works. The return value still says only whether there was anywhere to
     * put the files, so the caller can ask for a folder first.
     */
    fun enqueueSelected(onQueued: (DownloadPlan) -> Unit): Boolean =
        enqueuePicks(visibleEntries.filter { it.name in browse.selection }, onQueued)

    /**
     * Queues one row -- a file, or a folder and everything under it.
     *
     * A file used to have a path of its own that queued it directly, and the
     * bug that came of it is the reason this does not: only the selection and
     * folder paths checked the destination for a file of the same name, so
     * downloading a single file twice never asked anything and quietly saved
     * a second numbered copy. Everything now goes through [enqueuePicks], so
     * there is one place where that check can be forgotten rather than three.
     */
    fun enqueueEntry(entry: DirectoryEntry, onQueued: (DownloadPlan) -> Unit): Boolean =
        enqueuePicks(listOf(entry), onQueued)

    private fun enqueuePicks(picks: List<DirectoryEntry>, onQueued: (DownloadPlan) -> Unit): Boolean {
        val site = browse.site ?: return false
        val folder = downloadFolder ?: return false
        if (picks.isEmpty()) {
            onQueued(DownloadPlan())
            return true
        }
        val directory = browse.path

        browse = browse.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching {
                val plan = if (FolderDownload.needsRemoteWalk(picks)) {
                    // One connection for the whole walk: a session per folder
                    // would reconnect for every level of the tree.
                    graph.transfers.browse(site) { session ->
                        FolderDownload.plan(
                            lister = { path ->
                                session.changeDirectory(path)
                                session.list()
                            },
                            directory = directory,
                            picks = picks,
                        )
                    }
                } else {
                    // Files name themselves, so there is nothing to ask the
                    // server. Connecting anyway would put a login in front of
                    // every single-file download, which is most of them.
                    FolderDownload.plan({ emptyList() }, directory, picks)
                }
                plan to findConflicts(plan, folder)
            }.onSuccess { (plan, conflicts) ->
                browse = browse.copy(loading = false)
                clearSelection()
                if (conflicts.isEmpty()) {
                    enqueuePlan(plan, site, folder, ConflictChoice.DEFAULT)
                    onQueued(plan)
                } else {
                    // Nothing is queued yet. Asking before spending the data
                    // is the point: the user may well be about to say skip.
                    pendingConflicts = PendingDownload(plan, site, folder, conflicts)
                }
            }.onFailure { error ->
                browse = browse.copy(
                    loading = false,
                    error = describeFailure(error, graph.networkGate.currentlyOnline()),
                )
            }
        }
        return true
    }

    // -------------------------------------------------------- name conflicts

    /** A download waiting on the user to say what to do about existing files. */
    data class PendingDownload(
        val plan: DownloadPlan,
        val site: SiteEntity,
        val folder: Uri,
        val conflicts: List<DownloadConflict>,
    )

    /** Set when files of the same name are already in the chosen folder. */
    var pendingConflicts by mutableStateOf<PendingDownload?>(null)
        private set

    /** Goes ahead with [choice] applied to every clashing file. */
    fun resolveConflicts(choice: ConflictChoice, onQueued: (DownloadPlan) -> Unit) {
        val pending = pendingConflicts ?: return
        pendingConflicts = null
        viewModelScope.launch {
            val queued = enqueuePlan(pending.plan, pending.site, pending.folder, choice)
            onQueued(queued)
        }
    }

    /** Queues nothing and forgets the plan. */
    fun dismissConflicts() {
        pendingConflicts = null
    }

    private suspend fun findConflicts(plan: DownloadPlan, folder: Uri): List<DownloadConflict> =
        withContext(Dispatchers.IO) {
            plan.files.mapNotNull { file ->
                val destination = DownloadDestination(folder, file.subPath)
                val existing = graph.storage.existingDocument(destination, file.displayName)
                    ?: return@mapNotNull null
                DownloadConflict(
                    displayName = file.displayName,
                    remoteSize = file.size,
                    remoteModifiedMillis = file.modifiedMillis,
                    localSize = existing.size,
                    localModifiedMillis = existing.modifiedMillis,
                )
            }
        }

    /**
     * Queues the plan, applying [choice] to the files that clash.
     *
     * Skipping drops them here rather than at the end of the transfer. The
     * user said they did not want them; fetching them anyway and throwing the
     * bytes away afterwards would spend their data to reach the same place.
     *
     * @return the plan as it was actually queued.
     */
    private suspend fun enqueuePlan(
        plan: DownloadPlan,
        site: SiteEntity,
        folder: Uri,
        choice: ConflictChoice,
    ): DownloadPlan {
        val clashing = if (choice == ConflictChoice.SKIP) {
            findConflicts(plan, folder).map { it.displayName }.toSet()
        } else {
            emptySet()
        }
        val queued = plan.files.filterNot { it.displayName in clashing }
        for (file in queued) {
            graph.transfers.enqueueDownload(
                site = site,
                remotePath = file.remotePath,
                totalBytes = file.size,
                destinationTree = folder,
                subPath = file.subPath,
                onConflict = choice,
            )
        }
        return plan.copy(files = queued)
    }

    fun deleteSelected() {
        val chosen = browse.selection.toSet()
        val rows = browse.entries.filter { it.name in chosen }
        mutate { session ->
            for (entry in rows) {
                if (entry.isDirectory) session.removeDirectory(entry.name) else session.deleteFile(entry.name)
            }
        }
        clearSelection()
    }

    fun delete(entry: DirectoryEntry) = mutate { session ->
        if (entry.isDirectory) session.removeDirectory(entry.name) else session.deleteFile(entry.name)
    }

    fun rename(entry: DirectoryEntry, to: String) = mutate { it.rename(entry.name, to) }

    private fun mutate(block: (org.filezilla.android.transfer.FtpSession) -> Unit) {
        val site = browse.site ?: return
        val path = browse.path
        browse = browse.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching {
                graph.transfers.browse(site) { session ->
                    session.changeDirectory(path)
                    block(session)
                    session.list()
                }
            }.onSuccess { entries ->
                browse = browse.copy(
                    entries = entries,
                    selection = browse.prunedSelection(entries),
                    loading = false,
                )
            }.onFailure { error ->
                browse = browse.copy(
                    loading = false,
                    error = describeFailure(error, graph.networkGate.currentlyOnline()),
                )
            }
        }
    }

    // -------------------------------------------------------------- transfers

    fun chooseDownloadFolder(tree: Uri) {
        graph.storage.persistTreePermission(tree)
        graph.preferences.downloadFolder = tree
        downloadFolder = tree
    }

    fun enqueueUpload(source: Uri, onQueued: () -> Unit) {
        val site = browse.site ?: return
        val described = graph.storage.describeDocument(source) ?: return
        graph.storage.persistReadPermission(source)
        val (name, size) = described
        viewModelScope.launch {
            graph.transfers.enqueueUpload(
                site = site,
                remotePath = remotePathOf(browse.path, name),
                source = source,
                totalBytes = size.takeIf { it > 0 },
            )
            onQueued()
        }
    }

    fun pause(id: String) = viewModelScope.launch { graph.transfers.pause(id) }.let { }

    fun resume(id: String) = viewModelScope.launch { graph.transfers.resume(id) }.let { }

    fun cancel(id: String) = viewModelScope.launch { graph.transfers.cancel(id) }.let { }

    fun clearCompleted() = viewModelScope.launch { graph.transfers.clearCompleted() }.let { }

    fun clearLog() = graph.log.clear()
}
