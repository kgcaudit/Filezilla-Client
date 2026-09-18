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
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = AppGraph.of(application)

    val sites: StateFlow<List<SiteEntity>> = graph.database.sites().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val transfers: StateFlow<List<TransferRecord>> = graph.transfers.observeTransfers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val log: StateFlow<List<LogLine>> get() = graph.log.log

    val active: StateFlow<ActiveProgress?> get() = graph.transfers.active

    var browse by mutableStateOf(BrowseState())
        private set

    /** Sort, view and folder options, remembered between runs. */
    var options by mutableStateOf(graph.preferences.browseOptions)
        private set

    /** The listing as the screen shows it: filtered, sorted, arranged. */
    val visibleEntries: List<DirectoryEntry>
        get() = BrowseListing.arrange(browse.entries, options, browse.filter)

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
        val next = if (name in browse.selection) browse.selection - name else browse.selection + name
        browse = browse.copy(selecting = true, selection = next)
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

    /** Queues every selected file; directories are skipped, not recursed. */
    fun enqueueSelected(onQueued: (Int) -> Unit): Boolean {
        val site = browse.site ?: return false
        val folder = downloadFolder ?: return false
        val files = visibleEntries.filter { it.name in browse.selection && !it.isDirectory }
        if (files.isEmpty()) return true
        val directory = browse.path
        viewModelScope.launch {
            for (entry in files) {
                graph.transfers.enqueueDownload(
                    site,
                    remotePathOf(directory, entry.name),
                    entry.size.takeIf { it >= 0 },
                    folder,
                )
            }
            clearSelection()
            onQueued(files.size)
        }
        return true
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

    /**
     * Queues a download. Returns false when there is nowhere to put it yet, so
     * the caller can ask the user for a folder first -- better than starting a
     * transfer that has nowhere to land.
     */
    fun enqueueDownload(entry: DirectoryEntry, onQueued: () -> Unit): Boolean {
        val site = browse.site ?: return false
        val folder = downloadFolder ?: return false
        val path = remotePathOf(browse.path, entry.name)
        viewModelScope.launch {
            graph.transfers.enqueueDownload(site, path, entry.size.takeIf { it >= 0 }, folder)
            onQueued()
        }
        return true
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
