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
import org.filezilla.android.files.AccessRoute
import org.filezilla.android.files.FilePath
import org.filezilla.android.files.StorageRoot
import org.filezilla.android.transfer.ActiveProgress
import org.filezilla.android.transfer.LogLine
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.listing.DirectoryEntry

/**
 * What one pane is showing, and whether it is busy or broken.
 *
 * One type for both sides. The phone and a server differ in how their rows
 * are fetched and in nothing else the screen cares about, so a second
 * near-identical state would only be a second place for the two to drift --
 * which is exactly how the single-file download path came to skip a check
 * the other paths made.
 */
data class BrowseState(
    val source: PaneSource = PaneSource.Empty,
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

    /** The server this pane is on, or null when it is the phone or empty. */
    val site: SiteEntity? get() = (source as? PaneSource.Remote)?.site

    val isLocal: Boolean get() = source is PaneSource.Local

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

/** Which side the file tab is showing. The seed of the two panes. */
enum class FileSide { LOCAL, REMOTE }

/** What the local pane is showing, and why it is not showing anything. */
data class LocalBrowseState(
    val path: String = "",
    val entries: List<DirectoryEntry> = emptyList(),
    val loading: Boolean = false,
    /** Already readable; the pane has nowhere better to put a failure. */
    val error: String? = null,
    /** False while the app cannot see the device's storage at all. */
    val granted: Boolean = false,
    val route: AccessRoute = AccessRoute.ALL_FILES_SETTING,
)

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

    // ------------------------------------------------------------ the panes

    private var paneStates by mutableStateOf(
        mapOf(
            PaneId.LEFT to BrowseState(source = defaultSourceFor(PaneId.LEFT)),
            PaneId.RIGHT to BrowseState(source = defaultSourceFor(PaneId.RIGHT)),
        ),
    )

    /**
     * The pane the user is looking at.
     *
     * Read from the pager rather than tracked alongside it: two ideas of
     * which pane is in front is one more than can be kept in step, and the
     * toolbar acting on the pane you cannot see is the bug that would follow.
     */
    var activePane by mutableStateOf(PaneId.LEFT)
        private set

    fun pane(id: PaneId): BrowseState = paneStates.getValue(id)

    fun showPane(id: PaneId) {
        activePane = id
        val state = pane(id)
        // A pane swiped to for the first time has nothing in it yet.
        if (state.entries.isEmpty() && !state.loading && state.error == null) open(id)
    }

    private fun update(id: PaneId, block: (BrowseState) -> BrowseState) {
        paneStates = paneStates + (id to block(paneStates.getValue(id)))
    }

    /**
     * The pane every existing action works on.
     *
     * The toolbar, the selection and the download button were all written
     * against one browser, and they all mean "the one in front" -- so rather
     * than thread a pane through every one of them, the one in front is what
     * this returns.
     */
    var browse: BrowseState
        get() = pane(activePane)
        private set(value) {
            paneStates = paneStates + (activePane to value)
        }

    /** Sort, view and folder options, remembered between runs. */
    var options by mutableStateOf(graph.preferences.browseOptions)
        private set

    /** The listing as the screen shows it: filtered, sorted, arranged. */
    val visibleEntries: List<DirectoryEntry>
        get() = visibleEntries(activePane)

    /** One pane's rows as the screen shows them, through the shared ordering. */
    fun visibleEntries(id: PaneId): List<DirectoryEntry> =
        pane(id).let { BrowseListing.arrange(it.entries, options, it.filter) }

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

    // ------------------------------------------------------- pane navigation

    /**
     * Lists whatever [id] is pointed at, from its own remembered folder.
     *
     * One entry point for both sides. Which one it goes to is the pane's
     * source and nothing else, so a pane that is switched from the phone to a
     * server keeps behaving like the same pane.
     */
    fun open(id: PaneId) {
        when (val source = pane(id).source) {
            is PaneSource.Local -> openLocal(id, pane(id).path.ifEmpty { rememberedLocal(id) })
            is PaneSource.Remote -> loadRemote(
                id,
                source.site,
                pane(id).path.takeIf { it.isNotEmpty() && it != FilePath.ROOT }
                    ?: rememberedRemote(id, source.site),
            )

            PaneSource.Empty -> Unit
        }
    }

    /** Lists [path] in [id], whichever kind of place it is. */
    fun openPath(id: PaneId, path: String) {
        when (val source = pane(id).source) {
            is PaneSource.Local -> openLocal(id, path)
            is PaneSource.Remote -> loadRemote(id, source.site, path)
            PaneSource.Empty -> Unit
        }
    }

    fun openChild(id: PaneId, name: String) = openPath(id, FilePath.child(pane(id).path, name))

    /** Walks up, or does nothing at the top rather than looping. */
    fun up(id: PaneId) {
        FilePath.parent(pane(id).path)?.let { openPath(id, it) }
    }

    fun canGoUp(id: PaneId): Boolean = FilePath.parent(pane(id).path) != null

    /** Points a pane at the phone, remembering that it is there. */
    fun showLocal(id: PaneId) {
        graph.preferences.setPaneIsLocal(id.name, true)
        graph.preferences.setPaneSiteId(id.name, null)
        update(id) { BrowseState(source = PaneSource.Local) }
        refreshStorageAccess()
        if (storageGranted) openLocal(id, rememberedLocal(id))
    }

    /** Points a pane at a server, remembering which. */
    fun showSite(id: PaneId, site: SiteEntity) {
        graph.preferences.setPaneIsLocal(id.name, false)
        graph.preferences.setPaneSiteId(id.name, site.id)
        update(id) { BrowseState(source = PaneSource.Remote(site), loading = true) }
        loadRemote(id, site, site.initialPath?.takeIf { it.isNotBlank() })
    }

    /**
     * Puts each pane back where it was left.
     *
     * Run once, from the view model's own start rather than from the screen,
     * so that a pane is already pointed somewhere before it is first drawn --
     * otherwise the left pane appears empty for as long as the first listing
     * takes, which reads as having lost the folder.
     */
    private fun restorePanes() {
        for (id in PaneId.entries) {
            val siteId = graph.preferences.paneSiteId(id.name)
            when {
                graph.preferences.paneIsLocal(id.name) ->
                    update(id) { it.copy(source = PaneSource.Local) }

                siteId != null -> viewModelScope.launch {
                    // The saved server may have been deleted since. Then the
                    // pane offers a choice rather than pointing at nothing.
                    graph.database.sites().byId(siteId)?.let { site ->
                        update(id) { it.copy(source = PaneSource.Remote(site)) }
                        if (activePane == id) open(id)
                    }
                }
            }
        }
        refreshStorageAccess()
        open(activePane)
    }

    private fun rememberedLocal(id: PaneId): String =
        graph.preferences.panePath(id.name) ?: graph.volumes.defaultPath()

    private fun rememberedRemote(id: PaneId, site: SiteEntity): String? =
        graph.preferences.panePath(id.name)
            ?: site.initialPath?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------ local files

    /** Whether the app may read the device's storage at all. Device-wide, not per pane. */
    var storageGranted by mutableStateOf(false)
        private set

    var storageRoute by mutableStateOf(AccessRoute.ALL_FILES_SETTING)
        private set

    /** The volumes and shortcuts a local pane can jump to. */
    fun storageRoots(): List<StorageRoot> = graph.volumes.roots()

    /**
     * Re-asks the platform whether the app may read storage, and lists if so.
     *
     * Called again every time the screen comes back, because granting this
     * happens in Settings -- the user leaves the app to do it, and something
     * has to notice that they did. Remembering the answer instead would show
     * them an empty pane after they had just said yes.
     */
    fun refreshStorageAccess() {
        val was = storageGranted
        storageGranted = graph.storageAccess.isGranted()
        storageRoute = graph.storageAccess.route
        if (storageGranted && !was) {
            // Only the panes actually pointed at the phone, and only once the
            // answer has changed: re-listing a server every time the screen
            // resumes would be a login nobody asked for.
            for (id in PaneId.entries) if (pane(id).isLocal) openLocal(id, rememberedLocal(id))
        }
    }

    private fun openLocal(id: PaneId, path: String) {
        val target = FilePath.normalize(path)
        update(id) { it.copy(path = target, loading = true, error = null) }
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                runCatching { graph.localFiles.list(target) }
            }
            rows.onSuccess { entries ->
                graph.preferences.setPanePath(id.name, target)
                update(id) {
                    it.copy(
                        entries = entries,
                        selection = if (target == it.path) it.prunedSelection(entries) else emptySet(),
                        loading = false,
                        error = null,
                    )
                }
            }.onFailure { failure ->
                // The rows already on screen are left alone: replacing a
                // listing with nothing because one folder could not be opened
                // loses the user their place as well as the folder.
                update(id) { it.copy(loading = false, error = describeLocalFailure(failure)) }
            }
        }
    }

    /** Where to send the user to grant access, or null when asking is the way. */
    fun storageSettingsIntent(): android.content.Intent? = graph.storageAccess.settingsIntent()

    fun storagePermissionsToRequest(): Array<String> = graph.storageAccess.permissionsToRequest()

    // ----------------------------------------------------------------- sites

    /** Encrypts the password on its way to the row; see [SiteDraft]. */
    fun saveSite(draft: SiteDraft) {
        viewModelScope.launch {
            val dao = graph.database.sites()
            // A site being edited keeps where the user put it. Only a new one
            // needs a place, and its place is the end -- see [SiteDao.nextPosition].
            val position = dao.byId(draft.id)?.position ?: dao.nextPosition()
            dao.upsert(draft.toEntity(graph.passwords).copy(position = position))
        }
    }

    /**
     * Moves a site one place up or down the list.
     *
     * The order comes from what is on screen rather than from the database,
     * because that is what the user was looking at when they pressed the
     * button. Reading it again could answer with a list they have not seen.
     */
    fun moveSite(site: SiteEntity, towards: Move) {
        val order = SiteOrder.moved(sites.value.map { it.id }, site.id, towards) ?: return
        viewModelScope.launch { graph.database.sites().reorder(order) }
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

    /**
     * Opens a server from the site list.
     *
     * Into the right pane, which is the server side of the arrangement the
     * screen is built around: what you have on the left, what you are sending
     * it to on the right.
     */
    fun connect(site: SiteEntity) {
        showSite(PaneId.RIGHT, site)
        activePane = PaneId.RIGHT
    }

    // These three are what the toolbar has always called, and they now mean
    // the same thing on either kind of pane -- which is the point of there
    // being one pane type rather than two.
    fun openDirectory(name: String) = openChild(activePane, name)

    fun goUp() = up(activePane)

    fun refresh() = open(activePane)

    private fun loadRemote(id: PaneId, site: SiteEntity, path: String?) {
        update(id) { it.copy(source = PaneSource.Remote(site), loading = true, error = null) }
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
                graph.preferences.setPanePath(id.name, here)
                update(id) {
                    it.copy(
                        path = here,
                        entries = entries,
                        // A directory the user moved into has nothing selected,
                        // and a refresh keeps only what is still there.
                        selection = if (here == it.path) it.prunedSelection(entries) else emptySet(),
                        loading = false,
                        error = null,
                    )
                }
            }.onFailure { error ->
                update(id) {
                    it.copy(
                        loading = false,
                        error = describeFailure(error, graph.networkGate.currentlyOnline()),
                    )
                }
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

    /**
     * Last in the class on purpose, not for tidiness.
     *
     * An init block runs where it is written, and this one assigns to state
     * declared further down -- which, placed at the top, meant writing to a
     * delegate that did not exist yet and crashing the app before its first
     * frame. Anything it touches is therefore already built by the time it
     * runs.
     */
    init {
        restorePanes()
    }
}
