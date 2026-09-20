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
import org.filezilla.android.storage.numberedName
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.filezilla.android.AppGraph
import org.filezilla.android.data.SiteEntity
import org.filezilla.android.files.AccessRoute
import org.filezilla.android.files.FilePath
import org.filezilla.android.files.LocalOperations
import org.filezilla.android.files.LocalWalk
import org.filezilla.android.files.localParent
import org.filezilla.android.files.StorageRoot
import org.filezilla.android.transfer.ActiveProgress
import org.filezilla.android.transfer.LogLine
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.journal.TransferState
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
    /**
     * Empty until the pane has been somewhere.
     *
     * Not "/", which is what it was. A pane asks for its remembered folder
     * when its path is empty, and a root that is not empty answered that
     * question wrongly: a restored pane opened the filesystem root, which
     * cannot be listed, instead of the folder it was left in.
     */
    val path: String = "",
    val entries: List<DirectoryEntry> = emptyList(),
    val loading: Boolean = false,
    val error: ConnectionFailure? = null,

    /** Names of the selected rows; empty means selection mode is off. */
    val selection: Set<String> = emptySet(),
    /** True once the user has entered selection mode, even with nothing picked. */
    val selecting: Boolean = false,
    val filter: String = "",
    val filterOpen: Boolean = false,
    /**
     * A search below this folder, or null when only the filter is running.
     *
     * Kept apart from [filter] deliberately. The filter is a pure predicate
     * over [entries] and stays instant; this one reads folders, costs a
     * round trip each on a server, and is something the user asks for.
     */
    val search: SearchState? = null,
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

/**
 * A search below the folder on screen, as the screen sees it.
 *
 * Filled as the walk goes rather than at the end of it: on a server a deep
 * search is a round trip per folder, so waiting for the whole thing before
 * showing anything would mean a blank screen for however long that takes.
 */
data class SearchState(
    /** What is being looked for; kept so a changed filter can end the search. */
    val needle: String,
    val running: Boolean = true,
    val hits: List<SearchHit> = emptyList(),
    /** True when a limit was hit, so the count is not the whole answer. */
    val truncated: Boolean = false,
    val cancelled: Boolean = false,
    /** Folders opened so far, which is what the search has cost. */
    val foldersRead: Int = 0,
)

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

    /**
     * Makes [id] the pane the toolbar and the bars act on, without opening it.
     *
     * Separate from [showPane] because with both panes on screen focus moves
     * by touch, many times, and re-listing a folder on every touch would be a
     * listing nobody asked for -- and, on a server, a login.
     */
    fun focusPane(id: PaneId) {
        if (activePane != id) activePane = id
    }

    /** Lists [id] if it has nothing yet, and leaves it alone if it has. */
    fun ensureOpen(id: PaneId) {
        val state = pane(id)
        if (state.entries.isEmpty() && !state.loading && state.error == null) open(id)
    }

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
     * Which listing each pane is waiting for.
     *
     * A listing is asked for on the main thread and answered later, so two
     * can be in flight at once and the one that lands last wins -- whichever
     * was asked for last. Tapping into a folder while the one above it was
     * still being read left the header naming the folder tapped and the rows
     * belonging to the folder left behind, and the app looked like it had
     * simply failed to open anything.
     *
     * Comparing paths is not enough to sort that out: a refresh lists the
     * path it is already on, and a server decides for itself where a path
     * landed. So each ask takes a number and only the newest may speak.
     */
    private val listingAsked = mutableMapOf<PaneId, Int>()

    /** Claims the pane for a new listing, and hands back the number to quote. */
    private fun askForListing(id: PaneId): Int {
        val next = (listingAsked[id] ?: 0) + 1
        listingAsked[id] = next
        return next
    }

    /** Whether an answer that started as [asked] is still the one being waited for. */
    private fun stillWanted(id: PaneId, asked: Int): Boolean = listingAsked[id] == asked

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
        pane(id).let { BrowseListing.arrange(it.entries, optionsFor(id), it.filter) }

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

    /**
     * Where a download from [from] would land; see [destinationFor].
     *
     * The other pane, always. What this replaces was a folder chosen once
     * through the system picker and then never seen again -- so a transfer
     * could be running while the header said no folder had been chosen, and
     * both were true.
     */
    fun downloadDestination(from: PaneId = activePane): Destination =
        destinationFor(pane(facing(from)), storageGranted)

    /** The pane that is not this one. There are two, and that is the point. */
    fun facing(id: PaneId): PaneId = PaneId.entries.first { it != id }

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
            // A null path lets the server choose, which is what PWD is for.
            is PaneSource.Remote -> loadRemote(
                id,
                source.site,
                pane(id).path.ifEmpty { rememberedRemote(id, source.site).orEmpty() }
                    .takeIf { it.isNotEmpty() },
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
        parentOf(id)?.let { openPath(id, it) }
    }

    fun canGoUp(id: PaneId): Boolean = parentOf(id) != null

    /**
     * The folder above this pane's, bounded by what the pane can reach.
     *
     * A server's tree ends at its own root, which plain path arithmetic
     * already gets right. The phone's does not: above a volume there are
     * folders no app may list, and walking into one turned the up button into
     * three more presses that each produced an error.
     */
    private fun parentOf(id: PaneId): String? {
        val state = pane(id)
        return if (state.isLocal) {
            localParent(state.path, graph.volumes.volumePaths())
        } else {
            FilePath.parent(state.path)
        }
    }

    /** Points a pane at the phone, remembering that it is there. */
    fun showLocal(id: PaneId) = showLocalAt(id, rememberedLocal(id))

    /**
     * Points a pane at one folder on the phone, whatever it was showing.
     *
     * Separate from [showLocal] because picking a volume from the storage
     * list means that volume, not the folder the pane was last left in --
     * which on a pane already showing the phone would ignore the tap.
     */
    fun showLocalAt(id: PaneId, path: String) {
        graph.preferences.setPaneIsLocal(id.name, true)
        graph.preferences.setPaneSiteId(id.name, null)
        update(id) { BrowseState(source = PaneSource.Local) }
        refreshStorageAccess()
        if (storageGranted) openLocal(id, path)
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
        // Assigned rather than going through refreshStorageAccess, which
        // re-lists on a change and would list the active pane twice here --
        // once for the change from its starting false, once below.
        storageGranted = graph.storageAccess.isGranted()
        storageRoute = graph.storageAccess.route
        open(activePane)
    }

    /**
     * Where this pane last was on the phone.
     *
     * Kept apart from where it last was on a server, which is the bug this
     * shape fixes: one slot per pane meant switching a pane from a server to
     * the phone reused the server's path. A server sitting at "/" therefore
     * sent the pane to the root of the filesystem, which cannot be listed.
     *
     * A folder that has since gone -- deleted, or on a card that was removed
     * -- falls back to the default rather than opening an error, since the
     * pane has somewhere sensible to be and no reason not to be there.
     */
    private fun rememberedLocal(id: PaneId): String {
        val remembered = graph.preferences.panePath(id.name, LOCAL_SOURCE_KEY)
        if (remembered != null && java.io.File(remembered).isDirectory) return remembered
        return graph.volumes.defaultPath()
    }

    private fun rememberedRemote(id: PaneId, site: SiteEntity): String? =
        graph.preferences.panePath(id.name, siteSourceKey(site))
            ?: site.initialPath?.takeIf { it.isNotBlank() }

    /**
     * The slot a path is remembered in.
     *
     * Per server rather than one for all of them, so moving a pane between
     * two servers does not take one's folder to the other -- the same
     * mistake, one level down.
     */
    private fun siteSourceKey(site: SiteEntity) = "site:${site.id}"


    // ------------------------------------------------------- the clipboard

    /** What was cut or copied, and where from. Null until something is. */
    var clipboard by mutableStateOf<Clipboard?>(null)
        private set

    /** Picks up the pane's selection, leaving the originals where they are. */
    fun copySelection(id: PaneId) = pickUp(id, ClipboardMode.COPY)

    /** Picks it up to be moved: the originals go when it is put down. */
    fun cutSelection(id: PaneId) = pickUp(id, ClipboardMode.MOVE)

    private fun pickUp(id: PaneId, mode: ClipboardMode) {
        val state = pane(id)
        if (state.selection.isEmpty()) return
        clipboard = Clipboard(mode, state.source, state.path, state.selection.toList())
        update(id) { it.copy(selection = emptySet(), selecting = false) }
    }

    fun clearClipboard() {
        clipboard = null
    }

    /** Why a paste into [id] would not work, or null when it would. */
    fun pasteRefusal(id: PaneId): PasteRefusal? =
        PasteRules.refusal(clipboard, pane(id).source, pane(id).path)

    fun pasteKind(id: PaneId): PasteKind? = PasteRules.kind(clipboard, pane(id).source)

    /**
     * Puts down what is held.
     *
     * Each item is done in turn and the failures are collected rather than
     * thrown, because stopping at the first one leaves the user with half a
     * paste and no idea which half. A move empties the clipboard afterwards;
     * a copy keeps it, so the same thing can be put in several places.
     */
    /**
     * Puts down what is held, as a transfer when the two sides differ.
     *
     * The same gesture either way, which is the point of the two panes: copy
     * on one side, paste on the other, and whether that is a file operation
     * or a transfer is the app's problem rather than the user's.
     */
    fun pasteAcross(id: PaneId, onQueued: (Int) -> Unit) {
        val held = clipboard ?: return
        val target = pane(id)
        val from = held.source
        val to = target.source

        when {
            from is PaneSource.Local && to is PaneSource.Remote ->
                uploadHeld(held, to.site, target.path, onQueued)

            from is PaneSource.Remote && to is PaneSource.Local ->
                downloadHeld(held, from.site, target.path, onQueued)

            else -> Unit
        }
    }

    /**
     * One thing on its way to a server.
     *
     * A file a pane walked to and a document the system picker returned are
     * the same job once they are here, and giving them one shape is what lets
     * both go through the one check -- the app-bar upload used to have a
     * queueing path of its own, and it asked the server nothing.
     */
    data class Outgoing(
        val source: Uri,
        val name: String,
        val size: Long?,
        /** Folders to recreate on the server; empty for a file picked directly. */
        val subPath: List<String>,
    )

    /** An upload waiting on the user to say what to do about files already there. */
    data class PendingUpload(
        val files: List<Outgoing>,
        /** Including the empty ones, which no file would imply. */
        val folders: List<List<String>>,
        val site: SiteEntity,
        val remoteDirectory: String,
        val conflicts: List<DownloadConflict>,
        /** The names the server already has, so the choice is applied to those. */
        val clashing: Set<String>,
        /**
         * Cut rather than copied.
         *
         * Carried this far because the conflict dialog sits between the
         * paste and the queue, and it is the queue that has to know: each
         * upload removes its own file once the server has it.
         */
        val moving: Boolean = false,
        /** The folders on the phone the move may have emptied. */
        val sourceFolders: List<String> = emptyList(),
    )

    /** Set when the server already has files of the same name. */
    var pendingUploadConflicts by mutableStateOf<PendingUpload?>(null)
        private set

    fun dismissUploadConflicts() {
        pendingUploadConflicts = null
    }

    /** Goes ahead with [choice] applied to every clashing file. */
    fun resolveUploadConflicts(choice: ConflictChoice, onQueued: (Int) -> Unit) {
        val pending = pendingUploadConflicts ?: return
        pendingUploadConflicts = null
        viewModelScope.launch {
            onQueued(
                queueUploads(
                    pending.files,
                    pending.folders,
                    pending.site,
                    pending.remoteDirectory,
                    choice,
                    pending.clashing,
                    pending.moving,
                    pending.sourceFolders,
                ),
            )
        }
    }

    /**
     * Walks the held folders and queues every file under them.
     *
     * The server is asked what it already has before anything is queued. It
     * was not, and an upload with resume left on treats a file already there
     * as a half-sent copy of this one -- so sending over an existing file
     * silently spliced two different files together, with nothing asked and
     * nothing said.
     */
    private fun uploadHeld(
        held: Clipboard,
        site: SiteEntity,
        remoteDirectory: String,
        onQueued: (Int) -> Unit,
    ) {
        val moving = held.mode == ClipboardMode.MOVE
        viewModelScope.launch {
            val picked = held.paths()
            val files = withContext(Dispatchers.IO) {
                picked.flatMap { LocalWalk.filesUnder(it) }.map {
                    Outgoing(Uri.fromFile(java.io.File(it.path)), it.name, it.size, it.subPath)
                }
            }
            // Folders too, and not only the ones a file implies. A folder
            // holding nothing produces no files, so an upload built out of
            // files alone simply lost it -- nothing queued, nothing said,
            // and nothing on the server afterwards.
            val folders = withContext(Dispatchers.IO) {
                picked.flatMap { LocalWalk.foldersUnder(it) }.distinct()
            }
            // The folders that were cut, as paths on the phone, so the
            // sweep after the queue drains knows where to look. Only the ones
            // picked: a sweep is not licensed to wander up out of them.
            val sourceFolders = if (moving) {
                withContext(Dispatchers.IO) { picked.filter { java.io.File(it).isDirectory } }
            } else {
                emptyList()
            }
            sendToServer(files, folders, site, remoteDirectory, moving, sourceFolders, onQueued)
        }
    }

    /**
     * Asks the server what it already has, then queues what the user decided.
     *
     * The one way anything reaches the upload queue. Queued straight past
     * this, an upload with resume left on treats a file already there as a
     * half-sent copy of the one being sent and appends the rest -- so sending
     * over an existing file spliced two different files together, with
     * nothing asked and nothing said.
     */
    private fun sendToServer(
        files: List<Outgoing>,
        folders: List<List<String>>,
        site: SiteEntity,
        remoteDirectory: String,
        /** Cut rather than copied: each upload takes its own file away after. */
        moving: Boolean = false,
        /** The folders on the phone that the move may empty. */
        sourceFolders: List<String> = emptyList(),
        onQueued: (Int) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { remoteNames(site, remoteDirectory, files) }
                .onSuccess { existing ->
                    val conflicts = files.filter { it.name in existing }.map { file ->
                        DownloadConflict(
                            displayName = file.name,
                            // The local copy is the one being sent, so it is
                            // the "remote" side of the comparison from the
                            // dialog's point of view -- the one arriving.
                            remoteSize = file.size,
                            remoteModifiedMillis = null,
                            localSize = existing.getValue(file.name),
                            localModifiedMillis = 0,
                        )
                    }
                    if (conflicts.isEmpty()) {
                        onQueued(
                            queueUploads(
                                files,
                                folders,
                                site,
                                remoteDirectory,
                                ConflictChoice.DEFAULT,
                                emptySet(),
                                moving,
                                sourceFolders,
                            ),
                        )
                    } else {
                        pendingUploadConflicts = PendingUpload(
                            files,
                            folders,
                            site,
                            remoteDirectory,
                            conflicts,
                            conflicts.map { it.displayName }.toSet(),
                            moving,
                            sourceFolders,
                        )
                        onQueued(0)
                    }
                }
                .onFailure { error ->
                    update(activePane) {
                        it.copy(error = describeFailure(error, graph.networkGate.currentlyOnline()))
                    }
                    onQueued(0)
                }
        }
    }

    /**
     * Makes the copied folders on the server, shallowest first.
     *
     * Every folder in the selection, not only the empty ones: the ones with
     * files in them cost a single `MKD` that the server answers "already
     * there", which is cheaper than working out which is which and much
     * easier to be sure of.
     *
     * A failure is not fatal to the paste. The usual reason is that the
     * folder is already there, and the files still have their own folders
     * made for them as they go -- so a refusal here loses an empty folder
     * rather than the copy.
     */
    private suspend fun makeRemoteFolders(
        site: SiteEntity,
        remoteDirectory: String,
        folders: List<List<String>>,
    ) {
        if (folders.isEmpty()) return
        runCatching {
            graph.transfers.browse(site) { session ->
                for (folder in folders.sortedBy { it.size }) {
                    val path = folder.fold(remoteDirectory, FilePath::child)
                    runCatching { session.createDirectory(path) }
                }
            }
        }
    }

    /** What the destination folder on the server already holds, by name and size. */
    private suspend fun remoteNames(
        site: SiteEntity,
        remoteDirectory: String,
        files: List<Outgoing>,
    ): Map<String, Long> {
        // Only the folders the upload will actually write into, so a deep
        // folder costs one listing per level rather than one per file.
        val directories = files.map { it.subPath }.distinct()
        return graph.transfers.browse(site) { session ->
            buildMap {
                for (subPath in directories) {
                    val path = subPath.fold(remoteDirectory, FilePath::child)
                    // A folder that is not there yet clashes with nothing.
                    runCatching {
                        session.changeDirectory(path)
                        for (entry in session.list()) {
                            if (!entry.isDirectory) put(entry.name, entry.size)
                        }
                    }
                }
            }
        }
    }

    private suspend fun queueUploads(
        files: List<Outgoing>,
        folders: List<List<String>>,
        site: SiteEntity,
        remoteDirectory: String,
        choice: ConflictChoice,
        clashing: Set<String>,
        moving: Boolean = false,
        sourceFolders: List<String> = emptyList(),
    ): Int {
        // Made here rather than left to the transfers, because a transfer
        // only ever makes the folders above the file it is carrying -- and
        // an empty folder has no file to carry. Made before anything is
        // queued, so a copy of a folder tree arrives as a folder tree even
        // if the files in it are still on their way.
        makeRemoteFolders(site, remoteDirectory, folders)

        var queued = 0
        val taken = clashing.toMutableSet()
        for (file in files) {
            val clashes = file.name in clashing
            var name = file.name
            if (clashes) {
                when (choice) {
                    ConflictChoice.SKIP -> continue
                    ConflictChoice.OVERWRITE -> Unit
                    ConflictChoice.KEEP_BOTH -> {
                        // Numbered the same way a download is, so a file kept
                        // beside another reads the same wherever it lands.
                        var n = 1
                        while (numberedName(file.name, n) in taken) n++
                        name = numberedName(file.name, n)
                        taken += name
                    }
                }
            }
            graph.transfers.enqueueUpload(
                site = site,
                // The folders a file sat in, spliced back in, so uploading a
                // folder gives a folder rather than its contents strewn
                // across the one it landed in. Making them on the server is
                // the transfer's job, not the queue's -- see
                // FtpFileOperations.ensureParentsOf. This comment used to
                // say they were recreated, which nothing was doing.
                remotePath = FilePath.child(
                    remoteDirectory,
                    (file.subPath + name).joinToString(FilePath.SEPARATOR.toString()),
                ),
                source = file.source,
                totalBytes = file.size,
                overwrite = clashes && choice == ConflictChoice.OVERWRITE,
                // A move: this upload takes its own file away once the
                // server has it. Not before -- an upload that has only been
                // queued has not moved anything.
                removeSource = moving,
            )
            queued++
        }
        // Not deleted here. A move whose upload has only been queued would
        // remove the original before it had gone anywhere, and a failed
        // transfer would then have lost the file. Each upload takes its own
        // file away when it lands, and the folders left behind are cleared
        // when the queue drains -- noted now, because that may be minutes
        // later and may be after this process has been killed.
        if (moving && queued > 0) noteFoldersToClear(null, sourceFolders)
        clipboard = null
        return queued
    }

    /**
     * Writes down folders a move may empty, for the sweep after the queue.
     *
     * Added to rather than replacing: two moves can be in the queue at once,
     * and the second one must not make the app forget the first one's
     * folders.
     */
    private fun noteFoldersToClear(siteId: String?, paths: List<String>) {
        if (paths.isEmpty()) return
        val preferences = graph.preferences
        preferences.foldersToClearAfterMove = preferences.foldersToClearAfterMove +
            paths.map { org.filezilla.android.data.MovedFolder(siteId, it) }
    }


    /** Queues the held remote files into an ordinary folder on the phone. */
    /**
     * The listing a clipboard was taken from, as the panes still hold it.
     *
     * A clipboard carries names, not rows, and a name on its own does not say
     * whether it is a folder. The pane it came from still has the rows, so
     * this finds it by what the clipboard remembers about its origin.
     */
    private fun rowsHeldIn(held: Clipboard): List<DirectoryEntry> =
        PaneId.entries.map { pane(it) }
            .firstOrNull {
                it.source == held.source &&
                    FilePath.normalize(it.path) == FilePath.normalize(held.directory)
            }
            ?.entries
            .orEmpty()

    private fun downloadHeld(
        held: Clipboard,
        site: SiteEntity,
        localDirectory: String,
        onQueued: (Int) -> Unit,
    ) {
        // The rows of the pane the items were picked up in, not of the active
        // one -- the active pane is the target, which is the phone. Taken
        // from there, no name ever matched and every pick fell back to a bare
        // DirectoryEntry, whose isDirectory is false. So a folder pasted from
        // a server was planned as if it were a file, and the download fetched
        // nothing.
        val rows = rowsHeldIn(held)
        val moving = held.mode == ClipboardMode.MOVE
        viewModelScope.launch {
            val picks = held.names.map { name ->
                rows.firstOrNull { it.name == name } ?: DirectoryEntry(name = name)
            }
            runCatching {
                val plan = if (FolderDownload.needsRemoteWalk(picks)) {
                    graph.transfers.browse(site) { session ->
                        FolderDownload.plan(
                            lister = { path ->
                                session.changeDirectory(path)
                                session.list()
                            },
                            directory = held.directory,
                            picks = picks,
                        )
                    }
                } else {
                    FolderDownload.plan({ emptyList() }, held.directory, picks)
                }
                val folder = android.net.Uri.fromFile(java.io.File(localDirectory))
                // Through the one path that looks in the destination first.
                // Queued straight from here, this would have asked nothing
                // about files already in the folder and quietly saved a
                // second numbered copy -- which is the bug the single-file
                // download had, arriving again by a new route.
                // The folders the move may empty: the ones cut, plus every
                // one a fetched file sat in. Both, because a folder that had
                // nothing in it produces no file to point at it, and a
                // folder deep in the tree is not named by the pick above it.
                val emptied = if (moving) {
                    (
                        picks.filter { it.isDirectory }
                            .map { FilePath.child(held.directory, it.name) } +
                            plan.files.mapNotNull { FilePath.parent(it.remotePath) }
                        ).distinct()
                } else {
                    emptyList()
                }
                val conflicts = findConflicts(plan, folder)
                if (conflicts.isEmpty()) {
                    val queued = enqueuePlan(plan, site, folder, ConflictChoice.DEFAULT, moving)
                    if (moving && queued.files.isNotEmpty()) noteFoldersToClear(site.id, emptied)
                    queued.files.size
                } else {
                    pendingConflicts =
                        PendingDownload(plan, site, folder, conflicts, moving, emptied)
                    0
                }
            }.onSuccess { count ->
                if (held.mode == ClipboardMode.MOVE) clipboard = null
                onQueued(count)
            }.onFailure { error ->
                update(activePane) { it.copy(error = describeFailure(error, graph.networkGate.currentlyOnline())) }
                onQueued(0)
            }
        }
    }

    /** A paste waiting on the user to say what to do about what is there. */
    data class PendingPaste(
        val held: Clipboard,
        val pane: PaneId,
        val target: String,
        val conflicts: List<DownloadConflict>,
        /** True for a move within one server, which is carried out differently. */
        val remote: Boolean = false,
    )

    var pendingPasteConflicts by mutableStateOf<PendingPaste?>(null)
        private set

    fun dismissPasteConflicts() {
        pendingPasteConflicts = null
    }

    fun resolvePasteConflicts(choice: ConflictChoice) {
        val pending = pendingPasteConflicts ?: return
        pendingPasteConflicts = null
        if (pending.remote) {
            runRemoteMove(pending.held, pending.pane, pending.target, choice)
        } else {
            runPaste(pending.held, pending.pane, pending.target, choice)
        }
    }

    /**
     * Moves what is held to another folder on the same server.
     *
     * FTP has no copy and no move, but `RNFR`/`RNTO` renames across
     * directories, which is a move. What this replaces reported a same-server
     * paste as a plain file operation -- "the same place, so a file
     * operation" -- and a file operation is `java.io.File` work on the phone.
     * So cutting on a server and pasting quietly asked the phone to move a
     * file at a path it does not have, and the listing came back unchanged.
     */
    fun moveOnServer(id: PaneId) {
        val held = clipboard ?: return
        if (pasteRefusal(id) != null) return
        val site = pane(id).site ?: return
        val target = pane(id).path
        update(id) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                graph.transfers.browse(site) { session ->
                    session.changeDirectory(target)
                    val existing = session.list().map { it.name }.toSet()
                    held.names.filter { it in existing }
                }
            }.onSuccess { clashing ->
                if (clashing.isEmpty()) {
                    runRemoteMove(held, id, target, ConflictChoice.DEFAULT)
                } else {
                    // Sizes are not offered: the two sides of this comparison
                    // are both on the server, and a listing of one folder does
                    // not describe the other. The names are the question.
                    pendingPasteConflicts = PendingPaste(
                        held = held,
                        pane = id,
                        target = target,
                        conflicts = clashing.map {
                            DownloadConflict(it, null, null, -1, 0)
                        },
                        remote = true,
                    )
                    update(id) { it.copy(loading = false) }
                }
            }.onFailure { error ->
                update(id) {
                    it.copy(loading = false, error = describeFailure(error, graph.networkGate.currentlyOnline()))
                }
            }
        }
    }

    private fun runRemoteMove(
        held: Clipboard,
        id: PaneId,
        target: String,
        choice: ConflictChoice,
    ) {
        val site = pane(id).site ?: return
        update(id) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val failure = runCatching {
                graph.transfers.browse(site) { session ->
                    session.changeDirectory(target)
                    val rows = session.list()
                    val taken = rows.map { it.name }.toMutableSet()
                    for (name in held.names) {
                        var asName = name
                        if (name in taken) {
                            when (choice) {
                                ConflictChoice.SKIP -> continue
                                // Removed first, contents and all: RNTO onto
                                // an existing name is refused by some servers
                                // and silently overwrites on others, and
                                // neither is a thing to leave to chance.
                                ConflictChoice.OVERWRITE -> {
                                    val row = rows.first { it.name == name }
                                    val plan = RemoteDelete.plan(
                                        lister = { path ->
                                            session.changeDirectory(path)
                                            session.list()
                                        },
                                        directory = target,
                                        picks = listOf(row),
                                    )
                                    if (plan.truncated) throw TooMuchToDeleteException()
                                    for (step in plan.steps) {
                                        if (step.isDirectory) {
                                            session.removeDirectory(step.path)
                                        } else {
                                            session.deleteFile(step.path)
                                        }
                                    }
                                }

                                ConflictChoice.KEEP_BOTH -> {
                                    var n = 1
                                    while (numberedName(name, n) in taken) n++
                                    asName = numberedName(name, n)
                                }
                            }
                        }
                        taken += asName
                        session.rename(
                            FilePath.child(held.directory, name),
                            FilePath.child(target, asName),
                        )
                    }
                    // The walk an overwrite does leaves the connection deep in
                    // the tree it removed, and the re-list starts from here.
                    session.changeDirectory(target)
                }
            }.exceptionOrNull()

            clipboard = null
            // The folder the items left has to be redrawn too, or the other
            // pane goes on showing things that are no longer there.
            for (other in PaneId.entries) {
                if (other != id && pane(other).path == held.directory && !pane(other).isLocal) {
                    open(other)
                }
            }
            open(id)
            failure?.let { error ->
                update(id) {
                    it.copy(error = describeFailure(error, graph.networkGate.currentlyOnline()))
                }
            }
        }
    }

    /**
     * Puts down what is held, asking first about anything already there.
     *
     * It did not ask. The operations refuse to write over something, so a
     * paste onto an existing name failed outright -- and the pane, re-listed
     * and unchanged, looked as though the paste had simply not happened.
     */
    fun paste(id: PaneId) {
        val held = clipboard ?: return
        if (pasteRefusal(id) != null) return
        val target = pane(id).path
        viewModelScope.launch {
            val conflicts = withContext(Dispatchers.IO) {
                localPasteConflicts(target, held.directory, held.names.toList())
            }
            if (conflicts.isEmpty()) {
                runPaste(held, id, target, ConflictChoice.DEFAULT)
            } else {
                pendingPasteConflicts = PendingPaste(held, id, target, conflicts)
            }
        }
    }

    private fun runPaste(
        held: Clipboard,
        id: PaneId,
        target: String,
        choice: ConflictChoice,
    ) {
        update(id) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    for (path in held.paths()) {
                        val name = FilePath.name(path)
                        val existing = java.io.File(FilePath.child(target, name))
                        var asName: String? = null
                        if (existing.exists()) {
                            when (choice) {
                                ConflictChoice.SKIP -> continue
                                // Removed first: the operations refuse to
                                // write over anything, which is what makes
                                // them safe, so replacing is a decision taken
                                // here rather than a rule bent down there.
                                ConflictChoice.OVERWRITE ->
                                    LocalOperations.delete(existing.absolutePath)

                                ConflictChoice.KEEP_BOTH -> asName = freeNameIn(target, name)
                            }
                        }
                        when (held.mode) {
                            ClipboardMode.COPY -> LocalOperations.copy(path, target, asName)
                            ClipboardMode.MOVE -> LocalOperations.move(path, target, asName)
                        }
                    }
                }.exceptionOrNull()
            }
            // Emptied whichever it was. Keeping a copy on the clipboard so it
            // could be put down twice left the bar across the bottom of the
            // screen for good, with no sign that anything had happened -- and
            // putting the same thing in two places is rarer than wondering why
            // the bar will not go away.
            clipboard = null
            if (held.mode == ClipboardMode.MOVE) {
                // The folder the items left also has to be redrawn, or the
                // other pane goes on showing things that are no longer there.
                for (other in PaneId.entries) {
                    if (other != id && pane(other).path == held.directory) open(other)
                }
            }
            listLocal(id, pane(id).path)
            failure?.let { error -> update(id) { it.copy(error = describeLocalFailure(error)) } }
        }
    }

    // ------------------------------------------------------- file operations

    // ------------------------------------ what the screen asks of one pane

    /*
     * These three dispatch on the pane's source, and that is the whole reason
     * they exist. The screen used to call the phone's own versions directly,
     * on whichever pane was in front -- so selecting files on a server and
     * pressing delete ran java.io.File work against the server's path. The
     * phone has no such path, so nothing was deleted, and the pane was then
     * re-listed by the phone's file reader, which reported the server's
     * folder as missing in the phone's words. Three call sites, one wrong
     * assumption each, and nothing in a compile to say so.
     */

    /** Removes [id]'s selection, wherever that pane is pointed. */
    fun deleteSelectionIn(id: PaneId) {
        if (pane(id).isLocal) {
            deleteSelection(id)
            return
        }
        val chosen = pane(id).selection.toSet()
        removeRemotely(id, pane(id).entries.filter { it.name in chosen })
        clearSelectionIn(id)
    }

    /** Renames one row of [id], wherever that pane is pointed. */
    fun renameIn(id: PaneId, entry: DirectoryEntry, newName: String) {
        if (pane(id).isLocal) {
            renameLocal(id, entry, newName)
        } else {
            mutate(id) { it.rename(entry.name, newName) }
        }
    }

    /** Makes a folder in [id]'s current directory, wherever that is. */
    fun createFolderIn(id: PaneId, name: String) {
        if (pane(id).isLocal) {
            createFolder(id, name)
        } else {
            mutate(id) { it.createDirectory(name) }
        }
    }

    /**
     * Makes an empty file, which only the phone's side offers.
     *
     * A server could be sent an empty file, but "new file" on a server is not
     * something this app claims to do, and doing it silently by upload would
     * be a surprise. Refused visibly instead of half-done.
     */
    fun createFileIn(id: PaneId, name: String) {
        if (pane(id).isLocal) createFile(id, name)
    }

    fun clearSelectionIn(id: PaneId) {
        update(id) { it.copy(selecting = false, selection = emptySet()) }
    }

    /** Makes a folder in [id]'s current directory. */
    fun createFolder(id: PaneId, name: String) = writeThen(id) {
        LocalOperations.createDirectory(pane(id).path, name)
    }

    /** Makes an empty file, which is what the screenshot's "new file" does. */
    fun createFile(id: PaneId, name: String) = writeThen(id) {
        LocalOperations.createFile(pane(id).path, name)
    }

    fun renameLocal(id: PaneId, entry: DirectoryEntry, newName: String) = writeThen(id) {
        LocalOperations.rename(FilePath.child(pane(id).path, entry.name), newName)
    }

    /** Removes the pane's selection, folders and all. */
    fun deleteSelection(id: PaneId) {
        val names = pane(id).selection.toList()
        if (names.isEmpty()) return
        update(id) { it.copy(selection = emptySet(), selecting = false) }
        writeThen(id) {
            for (name in names) LocalOperations.delete(FilePath.child(pane(id).path, name))
        }
    }

    /**
     * Runs a write and re-lists, whatever it did.
     *
     * Re-listing even on failure, because a write that failed partway still
     * changed something, and a screen showing what was there before the
     * attempt is the one that misleads.
     */
    private fun writeThen(id: PaneId, block: () -> Unit) {
        update(id) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) { runCatching(block).exceptionOrNull() }
            // Waited for, not launched alongside: the refresh clears the
            // error field, so reporting the failure first meant the refresh
            // erased it a moment later.
            listLocal(id, pane(id).path)
            failure?.let { error -> update(id) { it.copy(error = describeLocalFailure(error)) } }
        }
    }

    // ------------------------------------------------------------ local files

    /** Whether the app may read the device's storage at all. Device-wide, not per pane. */
    var storageGranted by mutableStateOf(false)
        private set

    var storageRoute by mutableStateOf(AccessRoute.ALL_FILES_SETTING)
        private set

    /** The volumes and shortcuts a local pane can jump to. */
    fun storageRoots(): List<StorageRoot> = graph.volumes.roots()

    /** How full a volume is, for the storage list. Null when it cannot be read. */
    fun capacityOf(path: String) = graph.volumes.capacityOf(path)

    /** Points a pane at nothing, so it offers the choice again. */
    fun showEmpty(id: PaneId) {
        graph.preferences.setPaneIsLocal(id.name, false)
        graph.preferences.setPaneSiteId(id.name, null)
        update(id) { BrowseState(source = PaneSource.Empty) }
    }

    /**
     * This pane's path as a row of places; see [breadcrumbs].
     *
     * The trail stops at the volume the folder is on, or at the server's own
     * root. Shortcuts are not floors: Downloads sits inside internal storage,
     * and a trail that began there would offer no way back up to the rest of
     * the phone.
     */
    fun breadcrumbsFor(id: PaneId): List<Crumb> {
        val state = pane(id)
        if (state.isLocal) {
            val volume = storageRoots()
                .filter { it.kind != StorageRoot.Kind.SHORTCUT }
                .firstOrNull { FilePath.isWithin(state.path, it.path) }
            return breadcrumbs(
                state.path,
                volume?.path ?: FilePath.ROOT,
                volume?.label ?: FilePath.ROOT,
            )
        }
        val name = state.site?.let { it.name.ifBlank { it.host } } ?: FilePath.ROOT
        return breadcrumbs(state.path, FilePath.ROOT, name)
    }

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
        // A card put in while the app was away shows up here, and nowhere
        // else: this is the one moment storage is looked at again.
        graph.volumes.forget()
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
        viewModelScope.launch { listLocal(id, path) }
    }

    /**
     * The listing itself, as something a caller can wait for.
     *
     * [openLocal] fires and forgets, which is right for a tap. It is wrong
     * for a write: a write re-lists and then reports what went wrong, and a
     * re-list that has not finished yet clears the error on its way in and
     * again when it lands. So a failed rename set an error, the refresh wiped
     * it, and the pane came back looking exactly as it had -- which is what
     * "it does nothing" looks like from outside.
     */
    private suspend fun listLocal(id: PaneId, path: String) {
        val target = FilePath.normalize(path)
        val cameFrom = pane(id).path
        val asked = askForListing(id)
        update(id) { it.copy(path = target, loading = true, error = null) }
        run {
            val rows = withContext(Dispatchers.IO) {
                runCatching { graph.localFiles.list(target) }
            }
            // Somewhere else was asked for while this was being read, so
            // these rows are a folder nobody is looking at any more -- and
            // the pane they would land in is not the one they came from.
            if (!stillWanted(id, asked)) return
            rows.onSuccess { entries ->
                graph.preferences.setPanePath(id.name, LOCAL_SOURCE_KEY, target)
                update(id) {
                    it.copy(
                        entries = entries,
                        selection = if (target == it.path) it.prunedSelection(entries) else emptySet(),
                        loading = false,
                        error = null,
                    )
                }
            }.onFailure { failure ->
                // Back to where the pane was. The rows already on screen are
                // kept -- losing someone's place because one folder would not
                // open is the worse answer -- but the path has to go back with
                // them, or the header names a folder the rows did not come
                // from and the screen is telling two different stories.
                update(id) {
                    it.copy(path = cameFrom, loading = false, error = describeLocalFailure(failure))
                }
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
        val asked = askForListing(id)
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
                if (!stillWanted(id, asked)) return@launch
                graph.preferences.setPanePath(id.name, siteSourceKey(site), here)
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
                if (!stillWanted(id, asked)) return@launch
                update(id) {
                    it.copy(
                        loading = false,
                        error = describeFailure(error, graph.networkGate.currentlyOnline()),
                    )
                }
            }
        }
    }

    /**
     * Folders that have been given settings of their own, by key.
     *
     * Mirrored in memory as well as in preferences so that changing one
     * redraws the pane: preferences are not state Compose watches.
     */
    private var folderOptions by mutableStateOf<Map<String, BrowseOptions>>(emptyMap())

    /**
     * What identifies a folder across runs: which side, and where.
     *
     * The site's id rather than its name, so renaming a server does not
     * lose every folder's arrangement -- and "local", not an empty string,
     * so a path on the phone cannot collide with one on a server that has
     * no id.
     */
    private fun folderKeyFor(state: BrowseState): String? {
        if (state.path.isEmpty()) return null
        val side = (state.source as? PaneSource.Remote)?.site?.id ?: "local"
        return "$side:" + FilePath.normalize(state.path)
    }

    /** The settings [id] is arranged by: its own if it has any, else the shared ones. */
    fun optionsFor(id: PaneId): BrowseOptions {
        val key = folderKeyFor(pane(id)) ?: return options
        return folderOptions[key] ?: graph.preferences.optionsForFolder(key) ?: options
    }

    /** True when this folder is arranged by settings of its own. */
    fun hasOwnOptions(id: PaneId): Boolean {
        val key = folderKeyFor(pane(id)) ?: return false
        return folderOptions.containsKey(key) || graph.preferences.optionsForFolder(key) != null
    }

    /**
     * Applies [next] to [id], to this folder alone or to everything.
     *
     * [onlyHere] off does not merely stop writing the folder's own
     * settings: it takes them away. Leaving them behind would mean turning
     * "이 폴더만" off and watching the folder go on being arranged by the
     * settings it is no longer supposed to have.
     */
    fun applyOptions(id: PaneId, next: BrowseOptions, onlyHere: Boolean) {
        val key = folderKeyFor(pane(id))
        if (onlyHere && key != null) {
            folderOptions = folderOptions + (key to next)
            graph.preferences.setOptionsForFolder(key, next)
            return
        }
        if (key != null) {
            folderOptions = folderOptions - key
            graph.preferences.setOptionsForFolder(key, null)
        }
        options = next
        graph.preferences.browseOptions = next
    }

    /** The shared settings, for the screens that have no pane behind them. */
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
        // A search is an answer to the old text. Left standing it would sit
        // there claiming to be results for what is now in the box.
        if (browse.search != null && browse.search?.needle != text.trim()) stopSearch(activePane)
        browse = browse.copy(filter = text)
    }

    fun toggleFilter() {
        // Closing the bar clears the filter: leaving a hidden one applied is
        // how a directory comes to look empty for no visible reason.
        browse = if (browse.filterOpen) {
            stopSearch(activePane)
            browse.copy(filterOpen = false, filter = "", search = null)
        } else {
            browse.copy(filterOpen = true)
        }
    }

    // ------------------------------------------------------------ searching

    /**
     * Walks in progress, by pane, so one can be called off.
     *
     * Per pane rather than one for the app: both sides can be searched, and
     * starting one on the server must not silently end the one running on
     * the phone.
     */
    private val searchJobs = mutableMapOf<PaneId, Job>()

    /**
     * Looks for the filter's text below the folder [id] is showing.
     *
     * Asked for explicitly, never automatic. The filter above it stays a
     * pure predicate over the rows already on screen -- instant, no network
     * -- and this is the paid-for version: on a server it is a round trip
     * per folder, which is fine once and would not be fine on a keystroke.
     */
    fun searchDeeper(id: PaneId) {
        val state = pane(id)
        val needle = state.filter.trim()
        if (needle.isEmpty()) return

        stopSearch(id)
        update(id) { it.copy(search = SearchState(needle = needle)) }

        searchJobs[id] = viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { walkFor(state, needle) { hit -> reportHit(id, needle, hit) } }
            }
            update(id) { pane ->
                // Only if this is still the same search. A second one
                // started while this was walking owns the state now.
                val current = pane.search ?: return@update pane
                if (current.needle != needle) return@update pane
                val found = outcome.getOrNull()
                pane.copy(
                    search = current.copy(
                        running = false,
                        // The streamed hits, not the returned ones: a search
                        // called off mid-folder has already shown them.
                        truncated = found?.truncated ?: false,
                        cancelled = found?.cancelled ?: (outcome.isFailure),
                        foldersRead = found?.foldersRead ?: current.foldersRead,
                    ),
                )
            }
            searchJobs.remove(id)
        }
    }

    /** Calls off [id]'s search and forgets its results. */
    fun stopSearch(id: PaneId) {
        searchJobs.remove(id)?.cancel()
        update(id) { it.copy(search = null) }
    }

    /** Calls off the walk but keeps what it has already found on screen. */
    fun stopWalking(id: PaneId) {
        searchJobs.remove(id)?.cancel()
        update(id) { pane ->
            pane.copy(search = pane.search?.copy(running = false, cancelled = true))
        }
    }

    /** Goes to where a result lives: the folder itself, or the one holding it. */
    fun openHit(id: PaneId, hit: SearchHit) {
        val target = if (hit.entry.isDirectory && !hit.entry.isLink) hit.path else hit.folder
        stopSearch(id)
        // The filter stays on, so the row that was tapped is the one in
        // front when the folder opens.
        openPath(id, target)
    }

    private fun reportHit(id: PaneId, needle: String, hit: SearchHit) {
        viewModelScope.launch {
            update(id) { pane ->
                val current = pane.search ?: return@update pane
                if (current.needle != needle) return@update pane
                pane.copy(search = current.copy(hits = current.hits + hit))
            }
        }
    }

    /** The same walk on either side; only what lists a folder differs. */
    private suspend fun walkFor(
        state: BrowseState,
        needle: String,
        onHit: (SearchHit) -> Unit,
    ): SearchOutcome {
        val source = state.source
        val showHidden = options.showHidden
        // Captured here, where this is still a suspend function. The walk
        // itself is blocking, so asking the coroutine context from inside it
        // would answer about whatever thread it ended up on.
        val job = kotlinx.coroutines.currentCoroutineContext()[Job]
        val stopped = { job?.isActive == false }
        return when (source) {
            is PaneSource.Local -> DeepSearch.walk(
                root = state.path,
                lister = RemoteLister { path ->
                    org.filezilla.android.files.LocalFileSource("").list(path)
                },
                needle = needle,
                showHidden = showHidden,
                cancelled = stopped,
                onHit = onHit,
            )

            is PaneSource.Remote -> graph.transfers.browse(source.site) { session ->
                DeepSearch.walk(
                    root = state.path,
                    // One session for the whole walk. A login per folder
                    // would cost more than the listings do.
                    lister = RemoteLister { path ->
                        session.changeDirectory(path)
                        session.list()
                    },
                    needle = needle,
                    showHidden = showHidden,
                    cancelled = stopped,
                    onHit = onHit,
                )
            }

            PaneSource.Empty -> SearchOutcome()
        }
    }

    // ------------------------------------------------------------ properties

    fun showProperties(entry: DirectoryEntry?) {
        browse = browse.copy(properties = entry)
    }

    fun createDirectory(name: String) = createFolderIn(activePane, name)

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
        val destination = downloadDestination() as? Destination.Folder ?: return false
        // A file:// tree, which is what a paste has always used. The storage
        // layer reads both kinds, so a transfer queued before this change
        // still finds the folder it was given.
        val folder = Uri.fromFile(java.io.File(destination.path))
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
        /** Cut rather than copied: each file goes from the server once it lands. */
        val moving: Boolean = false,
        /** The folders on the server the move may have emptied. */
        val sourceFolders: List<String> = emptyList(),
    )

    /** Set when files of the same name are already in the chosen folder. */
    var pendingConflicts by mutableStateOf<PendingDownload?>(null)
        private set

    /** Goes ahead with [choice] applied to every clashing file. */
    fun resolveConflicts(choice: ConflictChoice, onQueued: (DownloadPlan) -> Unit) {
        val pending = pendingConflicts ?: return
        pendingConflicts = null
        viewModelScope.launch {
            val queued =
                enqueuePlan(pending.plan, pending.site, pending.folder, choice, pending.moving)
            if (pending.moving && queued.files.isNotEmpty()) {
                noteFoldersToClear(pending.site.id, pending.sourceFolders)
            }
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
        /** Cut rather than copied: each file goes from the server once it lands. */
        moving: Boolean = false,
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
                // Only once it is in the user's own folder, and never when
                // they chose to keep the file already there -- those bytes
                // are dropped, which would make the server's copy the last
                // one. See TransferManager.publish.
                removeSource = moving,
            )
        }
        return plan.copy(files = queued)
    }

    fun deleteSelected() = deleteSelectionIn(activePane)

    /**
     * Removes one row from whichever kind of pane it is in.
     *
     * Dispatched on the source rather than assuming a server. Keyed on the
     * site, as it was, a local pane had no site and the call returned having
     * done nothing at all -- a delete that silently did not happen.
     */
    fun delete(entry: DirectoryEntry) {
        val id = activePane
        if (pane(id).isLocal) {
            writeThen(id) { LocalOperations.delete(FilePath.child(pane(id).path, entry.name)) }
            return
        }
        removeRemotely(id, listOf(entry))
    }

    /**
     * Removes rows from the server, contents and all.
     *
     * FTP has no recursive delete: `RMD` refuses a directory that is not
     * empty, so this used to come back as "550 Directory not empty" and the
     * folder stayed where it was -- while deleting a file worked, which made
     * it look like the app was broken rather than the protocol being narrow.
     * The walk that empties it first is [RemoteDelete].
     */
    private fun removeRemotely(id: PaneId, rows: List<DirectoryEntry>) {
        if (rows.isEmpty()) return
        val directory = pane(id).path
        mutate(id) { session ->
            val plan = RemoteDelete.plan(
                // One connection for the whole walk, and the same one that
                // then does the removing: a session per folder would
                // reconnect for every level of the tree.
                lister = { path ->
                    session.changeDirectory(path)
                    session.list()
                },
                directory = directory,
                picks = rows,
            )
            // Refused rather than part-done. Half a delete leaves a tree the
            // user did not ask for and cannot see the shape of, and the
            // failing RMD at the end of it would not say which half.
            if (plan.truncated) throw TooMuchToDeleteException()
            for (step in plan.steps) {
                if (step.isDirectory) session.removeDirectory(step.path) else session.deleteFile(step.path)
            }
            // Back where the pane is looking, because the walk left the
            // connection wherever the deepest folder was -- and the re-list
            // that follows starts from the current directory.
            session.changeDirectory(directory)
        }
    }

    fun rename(entry: DirectoryEntry, to: String) = renameIn(activePane, entry, to)

    private fun mutate(
        id: PaneId = activePane,
        block: (org.filezilla.android.transfer.FtpSession) -> Unit,
    ) {
        val site = pane(id).site ?: return
        val path = pane(id).path
        val asked = askForListing(id)
        update(id) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                graph.transfers.browse(site) { session ->
                    session.changeDirectory(path)
                    block(session)
                    // Where the server says it is, not where the pane thought
                    // it was: an operation can leave the connection somewhere
                    // else, and a pane whose path and rows disagree shows a
                    // trail of folders the rows did not come from.
                    session.currentDirectory() to session.list()
                }
            }.onSuccess { (here, entries) ->
                if (!stillWanted(id, asked)) return@launch
                update(id) {
                    it.copy(
                        path = here,
                        entries = entries,
                        selection = if (here == it.path) it.prunedSelection(entries) else emptySet(),
                        loading = false,
                        // Cleared, which it was not. A successful operation
                        // left the last failure's card sitting above the rows
                        // for the rest of the session -- so the app went on
                        // reporting a folder as unreadable while listing it.
                        error = null,
                    )
                }
            }.onFailure { error ->
                if (!stillWanted(id, asked)) return@launch
                update(id) {
                    it.copy(
                        loading = false,
                        error = describeFailure(error, graph.networkGate.currentlyOnline()),
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------- transfers

    /**
     * Sends a document the system picker returned.
     *
     * Through the same check as a paste. It had a queueing path of its own
     * that asked the server nothing, so picking a file already on the server
     * appended to it rather than asking -- the same fault as the paste, by a
     * second route.
     */
    fun enqueueUpload(source: Uri, onQueued: (Int) -> Unit) {
        val site = browse.site ?: return
        val described = graph.storage.describeDocument(source) ?: return
        graph.storage.persistReadPermission(source)
        val (name, size) = described
        sendToServer(
            listOf(Outgoing(source, name, size.takeIf { it > 0 }, emptyList())),
            // One document the system picker handed over; there is no folder
            // around it to recreate.
            emptyList(),
            site,
            browse.path,
            onQueued = onQueued,
        )
    }

    fun pause(id: String) = viewModelScope.launch { graph.transfers.pause(id) }.let { }

    fun resume(id: String) = viewModelScope.launch { graph.transfers.resume(id) }.let { }

    fun cancel(id: String) = viewModelScope.launch { graph.transfers.cancel(id) }.let { }

    fun clearCompleted() = viewModelScope.launch { graph.transfers.clearCompleted() }.let { }

    fun clearLog() = graph.log.clear()

    /**
     * Ids of the transfers already seen finished, so each is acted on once.
     *
     * Seeded from the first emission without re-listing anything: everything
     * completed in an earlier run is already finished as far as the panes are
     * concerned, and re-listing for it would mean a connection on every start.
     */
    private var finishedSeen: Set<String>? = null

    /**
     * Re-lists a pane when a transfer puts something new in the folder it is
     * showing; see [finishedTransferTouches].
     */
    private fun watchFinishedTransfers() {
        viewModelScope.launch {
            transfers.collect { records ->
                val finished = records
                    .filter { it.state == TransferState.COMPLETED }
                    .associateBy { it.id }
                val seen = finishedSeen
                finishedSeen = finished.keys
                if (seen == null) return@collect
                val fresh = finished.filterKeys { it !in seen }.values
                if (fresh.isEmpty()) return@collect
                for (id in PaneId.entries) {
                    val state = pane(id)
                    if (state.path.isEmpty()) continue
                    val touched = fresh.any {
                        finishedTransferTouches(
                            record = it,
                            isLocal = state.isLocal,
                            path = state.path,
                            host = state.site?.host,
                            port = state.site?.port,
                            user = state.site?.user,
                        )
                    }
                    if (touched) open(id)
                }
            }
        }
    }

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
        watchFinishedTransfers()
    }

    private companion object {
        /** The slot the phone's own folder is remembered in, per pane. */
        const val LOCAL_SOURCE_KEY = "local"
    }
}
