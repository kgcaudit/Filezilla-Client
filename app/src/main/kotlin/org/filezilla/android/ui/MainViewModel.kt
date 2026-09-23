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
import org.filezilla.android.R
import org.filezilla.android.archive.ArchiveBrowsing
import org.filezilla.android.archive.ArchiveNav
import org.filezilla.android.archive.WrongPassword
import org.filezilla.android.archive.ArchiveSession
import org.filezilla.android.archive.ArchiveEntry
import org.filezilla.android.archive.ArchiveExtract
import org.filezilla.android.archive.ArchiveWriter
import org.filezilla.android.archive.Archives
import org.filezilla.android.archive.RarNative
import org.filezilla.android.archive.SevenZipNative
import org.filezilla.android.archive.ExtractResult
import org.filezilla.android.data.SiteEntity
import org.filezilla.android.files.FileMode
import org.filezilla.ftp.transfer.TransferAbort
import org.filezilla.android.storage.ViewCache
import org.filezilla.ftp.net.CertificateNotTrusted
import org.filezilla.android.files.AccessRoute
import org.filezilla.android.files.FilePath
import org.filezilla.android.files.LocalOperations
import org.filezilla.android.files.LocalWalk
import org.filezilla.android.files.localParent
import org.filezilla.android.files.StorageRoot
import org.filezilla.android.transfer.ActiveProgress
import org.filezilla.android.transfer.LogLine
import org.filezilla.android.viewer.ComicSeries
import org.filezilla.android.viewer.ImageFiles
import org.filezilla.android.viewer.TextFiles
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

    /**
     * The archive this pane is looking inside, or null for an ordinary folder.
     *
     * When set, the pane's rows are the archive's, its breadcrumb continues
     * into the archive, and the write actions are put away: an archive is
     * browsed, not edited in place.
     */
    val archive: ArchiveSession? = null,
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
                // This is the refresh gesture as well as the first load, and
                // refresh means ask the server. A pane that answered a pull
                // from memory would be a pane that cannot be made to tell
                // the truth, which is worse than a slow one.
                fresh = true,
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
        if (pane(id).archive != null) { archiveUp(id); return }
        parentOf(id)?.let { openPath(id, it) }
    }

    fun canGoUp(id: PaneId): Boolean = pane(id).archive != null || parentOf(id) != null

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
                        it.copy(error = failureOn(pane(activePane).site, error))
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
            val stop = Stoppable()
            runCatching {
                val plan = if (FolderDownload.needsRemoteWalk(picks)) {
                    serverWork = ServerWork(ServerWork.Kind.SCANNING, stopper = stop::stop)
                    graph.transfers.browse(site) { session ->
                        planDownload(session, held.directory, picks, stop)
                    }
                } else {
                    FolderDownload.plan({ emptyList() }, held.directory, picks)
                }
                serverWork = null
                if (plan.cancelled) {
                    // Nothing queued, and said so: a paste that looked like
                    // it did nothing is the thing being avoided here.
                    workOutcome = WorkOutcome(org.filezilla.android.R.string.work_scan_stopped, 0, 0)
                    return@runCatching 0
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
                update(activePane) { it.copy(error = failureOn(pane(activePane).site, error)) }
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
                    it.copy(loading = false, error = failureOn(pane(id).site, error))
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
                        // The item being replaced can contain the one
                        // replacing it -- a folder moved up onto a same-named
                        // parent. Removing the destination would then take the
                        // source down with it, so it is moved aside under a
                        // free name first and only then is the destination
                        // cleared and the set-aside item renamed into place.
                        val fromWithin = name in taken && choice == ConflictChoice.OVERWRITE &&
                            FilePath.isWithin(FilePath.child(held.directory, name), FilePath.child(target, name))
                        if (fromWithin) {
                            var n = 1
                            while (numberedName(name, n) in taken) n++
                            val aside = numberedName(name, n)
                            session.rename(
                                FilePath.child(held.directory, name),
                                FilePath.child(target, aside),
                            )
                            deleteRemoteTree(session, target, rows.first { it.name == name })
                            session.rename(
                                FilePath.child(target, aside),
                                FilePath.child(target, name),
                            )
                            taken += name
                            continue
                        }
                        if (name in taken) {
                            when (choice) {
                                ConflictChoice.SKIP -> continue
                                // Removed first, contents and all: RNTO onto
                                // an existing name is refused by some servers
                                // and silently overwrites on others, and
                                // neither is a thing to leave to chance.
                                ConflictChoice.OVERWRITE ->
                                    deleteRemoteTree(session, target, rows.first { it.name == name })

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
                    it.copy(error = failureOn(pane(id).site, error))
                }
            }
        }
    }

    /**
     * Removes [row] under [directory] on the server, contents and all.
     *
     * RNTO onto an existing name is refused by some servers and silently
     * overwrites on others, so an overwrite clears the way first. Walked with
     * [RemoteDelete] rather than a single command because a folder has to be
     * emptied depth first before it can go.
     */
    private fun deleteRemoteTree(
        session: org.filezilla.android.transfer.FtpSession,
        directory: String,
        row: org.filezilla.ftp.listing.DirectoryEntry,
    ) {
        val plan = RemoteDelete.plan(
            lister = { path ->
                session.changeDirectory(path)
                session.list()
            },
            directory = directory,
            picks = listOf(row),
        )
        if (plan.truncated) throw TooMuchToDeleteException()
        for (step in plan.steps) {
            if (step.isDirectory) session.removeDirectory(step.path) else session.deleteFile(step.path)
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
                    pasteLocally(held.mode, held.paths(), target, choice)
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

    /**
     * The row whose permissions are being changed, if any.
     *
     * Only ever a row on a server. The phone's files are reached through
     * the storage framework, which hands out documents rather than a
     * filesystem and has no permission bits to set -- so this is offered
     * where it means something and absent where it does not, rather than
     * offered everywhere and refused.
     */
    var changingMode by mutableStateOf<Pair<PaneId, DirectoryEntry>?>(null)
        private set

    fun changeModeOf(id: PaneId, entry: DirectoryEntry) {
        if (!pane(id).isLocal) changingMode = id to entry
    }

    fun dismissChangeMode() {
        changingMode = null
    }

    /**
     * Sets [entry]'s permissions to [mode], keeping any bit this cannot edit.
     *
     * [extra] is the setuid/setgid/sticky digit the server reported. It is
     * sent back unchanged: `SITE CHMOD 777` on a sticky shared folder
     * clears the bit that keeps people from deleting each other's files,
     * and nobody editing "who may read this" is asking for that.
     */
    fun applyMode(id: PaneId, entry: DirectoryEntry, mode: FileMode, extra: String?) {
        changingMode = null
        mutate(id) { it.changeMode(entry.name, extra.orEmpty() + mode.toString()) }
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
        state.archive?.let { session ->
            // The whole nesting chain, outermost first, so the trail reads
            // folder / outer.7z / ... / inner.zip / ... rather than exposing
            // the cache the inner copies sit in.
            val chain = generateSequence(session) { it.parent }.toList().asReversed()
            // The base is the real folder the outermost archive sits in.
            val out = folderCrumbs(state, chain.first().home).toMutableList()
            chain.forEachIndexed { depth, link ->
                out += Crumb(link.origin ?: link.name, ArchiveNav.crumb(depth, ""))
                var walked = ""
                for (segment in link.at.split('/').filter { it.isNotEmpty() }) {
                    walked = if (walked.isEmpty()) segment else "$walked/$segment"
                    out += Crumb(segment, ArchiveNav.crumb(depth, walked))
                }
            }
            return out
        }
        return folderCrumbs(state)
    }

    private fun folderCrumbs(state: BrowseState, path: String = state.path): List<Crumb> {
        if (state.isLocal) {
            val volume = storageRoots()
                .filter { it.kind != StorageRoot.Kind.SHORTCUT }
                .firstOrNull { FilePath.isWithin(path, it.path) }
            return breadcrumbs(
                path,
                volume?.path ?: FilePath.ROOT,
                volume?.label ?: FilePath.ROOT,
            )
        }
        val name = state.site?.let { it.name.ifBlank { it.host } } ?: FilePath.ROOT
        return breadcrumbs(path, FilePath.ROOT, name)
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
            val before = dao.byId(draft.id)
            val position = before?.position ?: dao.nextPosition()
            dao.upsert(draft.toEntity(graph.passwords).copy(position = position))
            // The kept browsing connection was opened against the old
            // settings. An edited host, port or encoding would otherwise
            // go on being talked to for as long as the connection is
            // considered fresh.
            before?.let { graph.transfers.forget(it) }
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
            graph.transfers.forget(site)
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

    /**
     * Lists [path] on [site] into pane [id].
     *
     * [fresh] asks the server even when the last answer is still being
     * held. Navigation leaves it off -- walking back up a tree is where
     * every cache hit comes from -- and refresh turns it on.
     */
    // ------------------------------------------------ long work on a server

    /**
     * A flag a walk and a loop both read, set from the screen.
     *
     * Not coroutine cancellation: that would interrupt whatever command is
     * in flight and leave the control connection with a reply nobody read,
     * which the next borrower of that connection would read as its own.
     * Asked between steps instead, so the protocol is always at a boundary
     * when the work stops.
     */
    class Stoppable {
        @Volatile
        private var asked = false

        fun stop() {
            asked = true
        }

        fun stopped(): Boolean = asked
    }

    /**
     * Walks a server tree to plan a download, saying how far it has got.
     *
     * The walk is a listing per folder and nothing is queued until it ends,
     * so on a deep tree the app has taken a selection and gone quiet. The
     * transfer list takes over once there is something in it; this covers
     * the part before that.
     */
    private fun planDownload(
        session: org.filezilla.android.transfer.FtpSession,
        directory: String,
        picks: List<DirectoryEntry>,
        stop: Stoppable,
    ): DownloadPlan = FolderDownload.plan(
        lister = { path ->
            session.changeDirectory(path)
            session.list()
        },
        directory = directory,
        picks = picks,
        cancelled = stop::stopped,
        onFolder = { read ->
            serverWork = ServerWork(
                ServerWork.Kind.SCANNING,
                foldersRead = read,
                stopper = stop::stop,
            )
        },
    )

    /** What to say about work that stopped early. Nothing to say otherwise. */
    data class WorkOutcome(@androidx.annotation.StringRes val message: Int, val done: Int, val total: Int)

    var serverWork by mutableStateOf<ServerWork?>(null)
        private set

    var workOutcome by mutableStateOf<WorkOutcome?>(null)
        private set

    fun stopServerWork() {
        serverWork?.stop()
    }

    fun outcomeShown() {
        workOutcome = null
    }

    // -------------------------------------------------- opening a server file

    /**
     * A server file on its way to being looked at.
     *
     * Tapping a row on a server used to queue a download, which meant a
     * destination folder had to be chosen before anything could be read --
     * so looking at a text file began with deciding where to keep it. The
     * download button beside the row still does that, because that is what
     * it is for; the tap now fetches a copy and opens it.
     */
    data class Viewing(
        val name: String,
        val bytes: Long,
        val total: Long?,
        private val abort: TransferAbort,
    ) {
        fun stop() = abort.stop()
    }

    var viewing by mutableStateOf<Viewing?>(null)
        private set

    /**
     * The fetched copy, waiting for the screen to open it.
     *
     * Handed over rather than opened here: which app opens a file, whether
     * it is an APK that needs permission first, and what the chooser looks
     * like are all the screen's business and all already written for files
     * on the phone. This is the same file by the time it gets there.
     */
    var readyToOpen by mutableStateOf<java.io.File?>(null)
        private set

    /** Said once, before the first server file is ever opened. */
    var warnReadOnly by mutableStateOf(false)
        private set

    var viewingFailure by mutableStateOf<ConnectionFailure?>(null)
        private set

    fun openedReady() {
        readyToOpen = null
    }

    fun dismissViewingFailure() {
        viewingFailure = null
    }

    /**
     * How much the viewing cache is holding, recounted when it is looked at.
     *
     * Not kept as state: it changes when files are fetched and when Android
     * decides it wants the space back, and the second of those happens
     * without this app being told.
     */
    fun viewCacheBytes(): Long = graph.viewCache.totalBytes()

    fun emptyViewCache() = graph.viewCache.clear()

    // -------------------------------------------------------- in-app viewers

    /** A text file shown in the app's own reader, editable only on the phone. */
    data class TextViewer(val file: java.io.File, val name: String, val editable: Boolean)

    var textViewer by mutableStateOf<TextViewer?>(null)
        private set

    /** One image the viewer can show, on the phone or still inside an archive. */
    sealed interface ImageRef {
        val name: String

        data class OnDisk(val file: java.io.File) : ImageRef {
            override val name: String get() = file.name
        }

        data class InArchive(val session: ArchiveSession, val entry: ArchiveEntry) : ImageRef {
            override val name: String get() = entry.name
        }
    }

    /**
     * The next volume to run on to when a comic ends, wherever it lives.
     *
     * A series is a run of volumes, and a volume is a file beside this one, a
     * folder beside this one inside an archive, or another archive beside this
     * one inside an outer archive -- a whole collection bundled into a single
     * file. One card, one "continue", three ways of being the next book.
     */
    sealed interface NextVolume {
        /** The volume's name, without its extension, for the end card. */
        val label: String

        /** A sibling archive file on the phone. */
        data class LocalFile(val file: java.io.File) : NextVolume {
            override val label: String get() = file.nameWithoutExtension
        }

        /** A sibling folder inside the same archive: volumes as sub-folders. */
        data class InArchiveFolder(val session: ArchiveSession, val at: String) : NextVolume {
            override val label: String get() = at.substringAfterLast('/')
        }

        /** A sibling archive inside the outer one: volumes as sub-archives. */
        data class InArchiveEntry(val outer: ArchiveSession, val name: String) : NextVolume {
            override val label: String get() = name.substringBeforeLast('.')
        }
    }

    /**
     * The images the viewer can swipe through, which one is on screen, and --
     * for a comic, whose pages are worth remembering -- the key its place is
     * saved under. Null [comicKey] is a one-off view that keeps no place.
     *
     * [book] marks a comic archive rather than a loose folder of pictures, so
     * the reader can offer an end and a next; [nextVolume] is the following
     * volume in the same series, when there is one to go on to.
     */
    data class ImageViewer(
        val images: List<ImageRef>,
        val index: Int,
        val comicKey: String?,
        val book: Boolean = false,
        val nextVolume: NextVolume? = null,
        /**
         * The reading mode to open in when nothing is saved for this book:
         * carried from the volume just read so a series keeps its shape on
         * "continue". Null means fall back to what the first page looks like.
         * [initialWebtoonExplicit] says whether that carried mode was a choice
         * rather than a guess, so a guess is not written down as one.
         */
        val initialWebtoon: Boolean? = null,
        val initialWebtoonExplicit: Boolean = false,
    )

    var imageViewer by mutableStateOf<ImageViewer?>(null)
        private set

    /** Right-to-left paging for manga, remembered across books. */
    var readerRtl by mutableStateOf(graph.preferences.readerRtl)
        private set

    /** Two pages side by side on a wide screen, remembered across books. */
    var readerTwoPage by mutableStateOf(graph.preferences.readerTwoPage)
        private set

    /** A webtoon read in a narrow centred column rather than the full width. */
    var readerWebtoonNarrow by mutableStateOf(graph.preferences.readerWebtoonNarrow)
        private set

    /** That column's width, as a percent of the screen, remembered across books. */
    var readerWebtoonWidthPercent by mutableStateOf(graph.preferences.readerWebtoonWidthPercent)
        private set

    fun applyReaderRtl(value: Boolean) {
        readerRtl = value
        graph.preferences.readerRtl = value
    }

    fun applyReaderTwoPage(value: Boolean) {
        readerTwoPage = value
        graph.preferences.readerTwoPage = value
    }

    fun applyReaderWebtoonNarrow(value: Boolean) {
        readerWebtoonNarrow = value
        graph.preferences.readerWebtoonNarrow = value
    }

    fun applyReaderWebtoonWidthPercent(value: Int) {
        val clamped = value.coerceIn(
            org.filezilla.android.data.AppPreferences.MIN_WEBTOON_WIDTH,
            org.filezilla.android.data.AppPreferences.MAX_WEBTOON_WIDTH,
        )
        readerWebtoonWidthPercent = clamped
        graph.preferences.readerWebtoonWidthPercent = clamped
    }

    // The reading mode of the comic on screen, carried to the next volume so a
    // series keeps its shape without being set again on every book. Read only
    // when opening the next volume, so a fresh, unrelated comic does not inherit.
    private var inheritWebtoon: Boolean? = null
    private var inheritWebtoonExplicit = false

    /** How this book was last read on purpose, or null to judge by its first page. */
    fun comicWebtoon(key: String?): Boolean? = key?.let { graph.preferences.comicWebtoon(it) }

    /**
     * The reader's word on how the book on screen is being read.
     *
     * Kept so the next volume opens the same way, and -- when the mode was
     * chosen rather than guessed -- written down for this book so it, too,
     * reopens the way it was left.
     */
    fun rememberReaderWebtoon(key: String?, webtoon: Boolean, explicit: Boolean) {
        inheritWebtoon = webtoon
        inheritWebtoonExplicit = explicit
        if (explicit && key != null) graph.preferences.setComicWebtoon(key, webtoon)
    }

    fun openTextViewer(file: java.io.File, editable: Boolean) {
        textViewer = TextViewer(file, file.name, editable)
    }

    fun closeTextViewer() {
        textViewer = null
    }

    /**
     * Opens the viewer on [images], at [index] unless a place was kept for
     * [comicKey] -- then it reopens quietly where it was last left.
     */
    fun openImageViewer(
        images: List<ImageRef>,
        index: Int,
        comicKey: String?,
        book: Boolean = false,
        nextVolume: NextVolume? = null,
        initialWebtoon: Boolean? = null,
        initialWebtoonExplicit: Boolean = false,
    ) {
        if (images.isEmpty()) return
        val start = comicKey?.let { graph.preferences.comicPage(it) } ?: index
        imageViewer = ImageViewer(
            images = images,
            index = start.coerceIn(0, images.size - 1),
            comicKey = comicKey,
            book = book,
            nextVolume = nextVolume,
            initialWebtoon = initialWebtoon,
            initialWebtoonExplicit = initialWebtoonExplicit,
        )
    }

    /**
     * Opens [file] in the image viewer with the other pictures in its folder,
     * so a folder of photos swipes through like the pages of a comic does.
     */
    fun openLocalImage(id: PaneId, file: java.io.File) {
        val folder = pane(id).path
        // Ordered for reading, not by whatever the browser is sorted by: a
        // folder shown newest-first would otherwise open its pictures in that
        // order, so the pages are put back into natural name order here.
        val images = pane(id).entries
            .filter { !it.isDirectory && ImageFiles.looksImage(it.name) }
            .sortedWith(org.filezilla.android.files.NaturalOrder.by { it.name })
        openImageViewer(
            images = images.map { ImageRef.OnDisk(java.io.File(folder, it.name)) },
            index = images.indexOfFirst { it.name == file.name },
            // No remembered place for a folder of photos: tapping a picture
            // opens that picture, not wherever the folder was last left -- a
            // saved page would override the very image the tap chose.
            comicKey = null,
        )
    }

    /**
     * The next comic in the same series as [file], sitting beside it, or null.
     *
     * Only a real file on the phone has a "beside": a comic unpacked into the
     * viewing cache has none, so it never runs on to a next this way.
     */
    private fun nextComicAfter(file: java.io.File): java.io.File? {
        if (graph.viewCache.holds(file)) return null
        val folder = file.parentFile ?: return null
        val names = folder.list()?.toList() ?: return null
        return ComicSeries.nextVolume(file.name, names)?.let { java.io.File(folder, it) }
    }

    /**
     * The next volume after the comic [session] is reading, wherever it lives.
     *
     * A collection is bundled in one of three shapes, and each is looked for in
     * turn: a folder of the series sitting beside this one inside the same
     * archive, an archive of the series sitting beside this one inside the outer
     * archive, or -- for a comic that is its own file -- another file beside it
     * on the phone. The first that has a next in the series answers.
     */
    private fun nextVolumeAfter(session: ArchiveSession): NextVolume? {
        folderVolumeAfter(session)?.let { return it }
        val parent = session.parent
        val origin = session.origin
        if (parent != null && origin != null) archiveVolumeAfter(parent, origin)?.let { return it }
        if (parent == null) nextComicAfter(session.file)?.let { return NextVolume.LocalFile(it) }
        return null
    }

    /** The sibling folder that continues the one [session] is reading, or null. */
    private fun folderVolumeAfter(session: ArchiveSession): NextVolume? {
        val at = session.at
        if (at.isEmpty()) return null
        val here = at.substringAfterLast('/')
        val parent = ArchiveBrowsing.upFrom(at).orEmpty()
        // Sibling folders that actually hold pages, so an empty or stray folder
        // is not offered as the next book.
        val siblings = ArchiveBrowsing.rowsIn(session.entries, parent)
            .filter { it.isDirectory }
            .map { it.name }
            .filter { name ->
                val folder = if (parent.isEmpty()) name else "$parent/$name"
                session.entries.any { !it.isDirectory && it.parent == folder && ImageFiles.looksImage(it.name) }
            }
        val next = ComicSeries.nextVolume(here, siblings) ?: return null
        return NextVolume.InArchiveFolder(session, if (parent.isEmpty()) next else "$parent/$next")
    }

    /** The sibling sub-archive that continues [origin] inside [outer], or null. */
    private fun archiveVolumeAfter(outer: ArchiveSession, origin: String): NextVolume? {
        val siblings = ArchiveNav.rows(outer)
            .filter { !it.isDirectory && ArchiveNav.browsable(it.name) }
            .map { it.name }
        val next = ComicSeries.nextVolume(origin, siblings) ?: return null
        return NextVolume.InArchiveEntry(outer, next)
    }

    /** Runs on to [next] when a comic ends, whichever shape of volume it is. */
    fun openNextVolume(next: NextVolume) {
        when (next) {
            is NextVolume.LocalFile -> openComicFile(next.file)
            is NextVolume.InArchiveFolder -> openArchiveFolderComic(next.session, next.at)
            is NextVolume.InArchiveEntry -> openNestedArchiveComic(next.outer, next.name)
        }
    }

    /** The image entries directly inside [at] of [session], in reading order. */
    private fun pagesUnder(session: ArchiveSession, at: String): List<ImageRef> =
        session.entries
            .filter { !it.isDirectory && it.parent == at && ImageFiles.looksImage(it.name) }
            .sortedWith(org.filezilla.android.files.NaturalOrder.by { it.path })
            .map { ImageRef.InArchive(session, it) }

    /**
     * Opens a comic archive straight into the reader, at its first page or
     * wherever it was last left. Used to run on to the next volume when one
     * ends, so it lands in the reader rather than in a list of its pages.
     */
    fun openComicFile(file: java.io.File) {
        archiveOpening = file.name
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) { runCatching { Archives.open(file).use { it.entries } } }
            archiveOpening = null
            val entries = opened.getOrNull()
            if (entries == null) {
                archiveOutcome = ArchiveOutcome(R.string.archive_not_readable)
                return@launch
            }
            val session = ArchiveSession(file, file.name, file.parent ?: "", entries)
            val pages = pagesUnder(session, "")
            if (pages.isEmpty()) {
                archiveOutcome = ArchiveOutcome(R.string.archive_not_readable)
                return@launch
            }
            val next = withContext(Dispatchers.IO) { nextVolumeAfter(session) }
            openImageViewer(
                images = pages,
                index = 0,
                comicKey = "arc\u0000${file.path}\u0000",
                book = true,
                nextVolume = next,
                initialWebtoon = inheritWebtoon,
                initialWebtoonExplicit = inheritWebtoonExplicit,
            )
        }
    }

    /** Opens the sub-folder [at] of [session] into the reader, as the next volume. */
    private fun openArchiveFolderComic(session: ArchiveSession, at: String) {
        val pages = pagesUnder(session, at)
        if (pages.isEmpty()) {
            archiveOutcome = ArchiveOutcome(R.string.archive_not_readable)
            return
        }
        val next = nextVolumeAfter(session.copy(at = at))
        openImageViewer(
            images = pages,
            index = 0,
            comicKey = "arc\u0000${session.file.path}\u0000$at",
            book = true,
            nextVolume = next,
            initialWebtoon = inheritWebtoon,
            initialWebtoonExplicit = inheritWebtoonExplicit,
        )
    }

    /**
     * Extracts the sub-archive [name] from [outer] and opens it in the reader.
     *
     * The next volume when a bundle is a folder of archives. A locked one is
     * handed to the ordinary open-and-browse path, which knows how to ask for a
     * password; there is nowhere to put that question mid-read.
     */
    private fun openNestedArchiveComic(outer: ArchiveSession, name: String) {
        val entry = ArchiveNav.entryFor(outer, name) ?: return
        if (entry.encrypted) {
            openArchiveEntry(activePane, outer, name, password = null)
            return
        }
        archiveOpening = name
        viewModelScope.launch {
            val held = withContext(Dispatchers.IO) {
                runCatching { cachedArchiveEntry(outer, entry, null) }.getOrNull()
            }
            val entries = held?.let {
                withContext(Dispatchers.IO) { runCatching { Archives.open(it).use { a -> a.entries } }.getOrNull() }
            }
            archiveOpening = null
            if (held == null || entries == null) {
                archiveOutcome = ArchiveOutcome(R.string.archive_open_failed, listOf(name, ""))
                return@launch
            }
            val inner = ArchiveSession(held, name, held.parent ?: "", entries, parent = outer, origin = name)
            val pages = pagesUnder(inner, "")
            if (pages.isEmpty()) {
                archiveOutcome = ArchiveOutcome(R.string.archive_not_readable)
                return@launch
            }
            val next = withContext(Dispatchers.IO) { nextVolumeAfter(inner) }
            openImageViewer(
                images = pages,
                index = 0,
                comicKey = "arc\u0000${held.path}\u0000",
                book = true,
                nextVolume = next,
                initialWebtoon = inheritWebtoon,
                initialWebtoonExplicit = inheritWebtoonExplicit,
            )
        }
    }

    fun closeImageViewer() {
        imageViewer = null
    }

    fun setImageIndex(index: Int) {
        imageViewer = imageViewer?.let { viewer ->
            val at = index.coerceIn(0, viewer.images.size - 1)
            // A comic keeps its place as the pages turn, so it reopens here.
            viewer.comicKey?.let { graph.preferences.setComicPage(it, at) }
            viewer.copy(index = at)
        }
    }

    /** Reads a text file off the IO thread, or null when it is too big or unreadable. */
    suspend fun loadText(file: java.io.File): TextFiles.Loaded? = withContext(Dispatchers.IO) {
        if (file.length() > TextFiles.MAX_BYTES) return@withContext null
        runCatching { TextFiles.decode(file.readBytes()) }.getOrNull()
    }

    /**
     * Writes edited text back to [file] in the encoding it came in.
     *
     * Only ever a phone file: the viewer offers editing on those alone, so
     * there is no server round-trip to make here. The panes are re-listed
     * because the size and time on the row have just changed.
     */
    fun saveText(file: java.io.File, loaded: TextFiles.Loaded, text: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { file.writeBytes(TextFiles.encode(text, loaded)) }.isSuccess
            }
            if (ok) relistLocalPanes()
            onDone(ok)
        }
    }

    /**
     * Decodes an image off the IO thread.
     *
     * A page still inside its archive is read straight into memory and
     * decoded there, rather than unpacked to a cache file and read back --
     * one pass over the bytes instead of a write and two reads, which is what
     * a page turn was waiting on.
     */
    suspend fun loadImage(ref: ImageRef, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            when (ref) {
                is ImageRef.OnDisk -> ImageFiles.decode(ref.file, reqWidth, reqHeight)
                is ImageRef.InArchive -> {
                    val bytes = imageBytes(ref) ?: return@withContext null
                    ImageFiles.decode(bytes, reqWidth, reqHeight)
                }
            }
        }

    /**
     * A few recently read image files, kept whole in memory.
     *
     * A webtoon strip is decoded a band at a time, and every band reads the
     * same source file -- so without this, scrolling one strip would unpack it
     * from its archive dozens of times over. Small and access-ordered: the
     * strip in front and its neighbours stay, the rest fall out, and a handful
     * of image files is little beside the bitmaps they decode to.
     */
    private val imageByteCache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
            size > IMAGE_BYTE_CACHE
    }

    private fun imageCacheKey(ref: ImageRef): String = when (ref) {
        is ImageRef.OnDisk -> "f:${ref.file.path}"
        is ImageRef.InArchive -> "a:${ref.session.file.path}\u0000${ref.entry.path}"
    }

    /** The raw bytes of [ref]'s image, from the small cache or freshly read. */
    private fun imageBytes(ref: ImageRef): ByteArray? {
        val key = imageCacheKey(ref)
        synchronized(imageByteCache) { imageByteCache[key] }?.let { return it }
        val bytes = runCatching {
            when (ref) {
                is ImageRef.OnDisk -> ref.file.readBytes()
                is ImageRef.InArchive -> Archives.open(ref.session.file).use { archive ->
                    archive.open(ref.entry).use { it.readBytes() }
                }
            }
        }.getOrNull() ?: return null
        synchronized(imageByteCache) { imageByteCache[key] = bytes }
        return bytes
    }

    /** [ref]'s real pixel size, read from its header alone, for planning a strip. */
    suspend fun imageSize(ref: ImageRef): android.util.Size? = withContext(Dispatchers.IO) {
        imageBytes(ref)?.let { ImageFiles.sizeOf(it) }
    }

    /**
     * The sizes of a whole strip's pages, in order, for laying it out.
     *
     * A run of pages inside one archive is measured in a single pass over it:
     * the archive is opened once and each page's header sniffed from its own
     * stream, rather than the archive being reopened and a page extracted whole
     * for every one of them -- which, on a long webtoon, is the difference
     * between a moment and a stall. Pages on disk are measured one by one,
     * which is already cheap. A page that will not read is a null in its place.
     */
    suspend fun imageSizes(refs: List<ImageRef>): List<android.util.Size?> = withContext(Dispatchers.IO) {
        val session = (refs.firstOrNull() as? ImageRef.InArchive)?.session
        // The common case, and the one worth the single pass: every page is an
        // entry of the one archive. Anything else falls back to one at a time.
        if (session != null && refs.all { it is ImageRef.InArchive && it.session === session }) {
            runCatching {
                Archives.open(session.file).use { archive ->
                    refs.map { ref ->
                        val entry = (ref as ImageRef.InArchive).entry
                        runCatching { archive.open(entry).use { ImageFiles.sizeOf(it) } }.getOrNull()
                    }
                }
            }.getOrElse { refs.map { null } }
        } else {
            refs.map { ref -> imageBytes(ref)?.let { ImageFiles.sizeOf(it) } }
        }
    }

    /** Decodes the rows [top, bottom) of [ref], shrunk by [sample], as one band of a strip. */
    suspend fun loadBand(ref: ImageRef, top: Int, bottom: Int, sample: Int): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            imageBytes(ref)?.let { ImageFiles.decodeRegion(it, top, bottom, sample) }
        }

    fun acknowledgeReadOnly() {
        graph.preferences.warnedThatViewingIsReadOnly = true
        warnReadOnly = false
    }

    fun cancelViewing() {
        viewing?.stop()
        viewing = null
    }

    /**
     * Fetches [entry] from [id]'s server and hands it to the screen to open.
     *
     * A copy already held is used as it is -- that is the whole point of
     * holding it -- but only when it is a copy of *this* version: the cache
     * is keyed on the size and the modification time as well as the path,
     * so a file edited on the server is fetched again rather than shown as
     * it used to be.
     */
    fun viewOnServer(id: PaneId, entry: DirectoryEntry) {
        if (pane(id).isLocal || entry.isDirectory) return
        val site = pane(id).site ?: return
        val remotePath = FilePath.child(pane(id).path, entry.name)

        val key = ViewCache.Key(
            serverKey = listOf(site.host, site.port.toString(), site.user).joinToString("\u0000"),
            path = remotePath,
            name = entry.name,
            size = entry.size,
            modifiedMillis = entry.time?.epochMillis ?: 0,
        )

        val home = pane(id).path
        graph.viewCache.readyFile(key)?.let { held ->
            graph.viewCache.touch(held)
            offerToOpen(id, home, held)
            return
        }

        val abort = TransferAbort()
        viewing = Viewing(entry.name, 0, entry.size.takeIf { it >= 0 }, abort)
        // Written under a name of its own and renamed into place once it
        // has all arrived. A stopped fetch then cannot delete what a second
        // attempt is writing, and one killed with the app cannot leave a
        // piece behind for the next tap to open as though it were whole.
        val partial = graph.viewCache.partialFor(key)

        viewModelScope.launch {
            runCatching {
                graph.transfers.fetchForViewing(site, remotePath, partial, abort) { bytes, total ->
                    viewing = viewing?.copy(bytes = bytes, total = total ?: viewing?.total)
                }
            }.onSuccess {
                viewing = null
                val held = graph.viewCache.finish(partial, key)
                if (held == null) {
                    viewingFailure = describeFailure(
                        java.io.IOException("the copy could not be put in place"),
                        graph.networkGate.currentlyOnline(),
                    )
                    return@launch
                }
                graph.viewCache.evictDownTo(keep = held)
                offerToOpen(id, home, held)
            }.onFailure { error ->
                viewing = null
                partial.delete()
                if (!abort.isStopped) viewingFailure = failureOn(site, error)
            }
        }
    }

    private fun offerToOpen(id: PaneId, home: String, file: java.io.File) {
        // An archive fetched from a server is browsed in the pane it came
        // from, the same as one on the phone; backing out returns to the
        // server folder. Anything else is handed to whatever reads it, with
        // the read-only note first because it is a copy of a server file.
        if (ArchiveNav.browsable(file.name)) {
            openArchive(id, file, home)
            return
        }
        if (!graph.preferences.warnedThatViewingIsReadOnly) warnReadOnly = true
        readyToOpen = file
    }

    // ---------------------------------------------------------------- archives

    /**
     * What an unpack or a compress had to say for itself.
     *
     * Separate from [WorkOutcome], which carries two counts: these
     * sentences carry a folder name and a file name, and squeezing a name
     * into an int was not going to work.
     */
    data class ArchiveOutcome(
        @androidx.annotation.StringRes val message: Int,
        val args: List<Any> = emptyList(),
    )

    /** A pane's current inside-an-archive location, resolved off [BrowseState]. */
    fun archiveIn(id: PaneId): ArchiveSession? = pane(id).archive

    var archiveBusy by mutableStateOf<ArchiveBusy?>(null)
        private set

    /**
     * The name of an archive being opened, while its index is read.
     *
     * Reading the index is usually instant, but a large archive on slow
     * storage is not, and a tap that shows nothing for two seconds looks
     * like a tap that did nothing. So this is set the moment the tap
     * lands, on the same frame, and a spinner rides on it.
     */
    var archiveOpening by mutableStateOf<String?>(null)
        private set

    var archiveOutcome by mutableStateOf<ArchiveOutcome?>(null)
        private set

    /**
     * Set when something locked has to be opened before it can be unpacked.
     *
     * Asked once, at the front, rather than at the seventh file of thirty:
     * a dialog that appears part way through a progress bar is a dialog
     * nobody is looking at.
     */
    var archivePasswordAsked by mutableStateOf(false)
        private set

    var archivePasswordWrong by mutableStateOf(false)
        private set

    /** What a password, once given, is for: an extract, or opening one file. */
    private sealed interface PendingPassword {
        val id: PaneId
        val session: ArchiveSession
        data class Extract(override val id: PaneId, override val session: ArchiveSession, val picks: Set<String>, val into: java.io.File, val overwrite: Boolean) : PendingPassword
        data class Open(override val id: PaneId, override val session: ArchiveSession, val name: String) : PendingPassword
    }

    private var pendingPassword: PendingPassword? = null

    /**
     * A just-extracted nested archive and the entry name it came from.
     *
     * The extracted copy is named by a digest, so [openArchive] reads the real
     * volume name from here (matched by the exact file) when the copy is opened
     * to browse -- for the breadcrumb, and to find the next volume in a bundle
     * of sub-archives. Set when the copy is handed on, consumed once.
     */
    private var nestedOrigin: Pair<java.io.File, String>? = null

    fun archiveOutcomeShown() {
        archiveOutcome = null
    }

    /** Lists the phone's panes again, because something wrote to it. */
    private fun relistLocalPanes() {
        for (id in PaneId.entries) if (pane(id).isLocal) openLocal(id, pane(id).path)
    }

    /**
     * Opens [file] as an archive inside pane [id], showing it like a folder.
     *
     * The pane keeps its real source and remembers [home] -- the folder to
     * return to when the archive is backed out of -- so an archive is a
     * place the pane walks into, not a window over the top of it. The bytes
     * are on disk whether the archive is on the phone or was fetched from a
     * server to be looked at, so this one path serves both.
     */
    fun openArchive(id: PaneId, file: java.io.File, home: String) {
        // The entry name this file was extracted from, when it is a nested
        // archive: the cache file is named by a digest, so its real volume name
        // is only knowable from what asked for it. Consumed once, here.
        val origin = nestedOrigin?.takeIf { it.first == file }?.second
        nestedOrigin = null
        archiveOpening = origin ?: file.name
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching { Archives.open(file).use { it.entries } }
            }
            archiveOpening = null
            opened.onSuccess { entries ->
                // Opened from inside another archive, the pane still holds that
                // outer one -- keep it as this one's parent so backing out
                // returns to it rather than to the cache the copy sits in.
                val parent = pane(id).archive
                val session = ArchiveSession(file, origin ?: file.name, home, entries, parent = parent, origin = origin)
                update(id) {
                    it.copy(
                        archive = session,
                        path = home,
                        entries = ArchiveNav.rows(session),
                        selection = emptySet(),
                        selecting = false,
                        filter = "",
                        filterOpen = false,
                    )
                }
            }.onFailure { failure ->
                // The reason, not a bare "cannot read": a truncated
                // download, an unsupported variant and a file that is not
                // an archive at all are different problems, and the one
                // word that tells them apart is the exception's own.
                archiveOutcome = ArchiveOutcome(
                    R.string.archive_open_failed,
                    listOf(file.name, failure.message ?: failure.javaClass.simpleName),
                )
            }
        }
    }

    /**
     * Where unpacking [file] would put things.
     *
     * Beside it, in a new folder, when the archive is on the phone: that
     * is what every file manager does and it needs no explaining. A file
     * fetched from a server is in the cache of copies kept for opening,
     * and that folder is emptied whenever Android wants the room -- so
     * there is no "beside it" and the answer is the phone's pane, which
     * is where a download from this app goes.
     */
    fun unpackInto(file: java.io.File): java.io.File {
        val beside = file.parentFile
        if (beside != null && !graph.viewCache.holds(file)) return beside
        val shown = PaneId.entries
            .firstOrNull { pane(it).isLocal && pane(it).path.isNotEmpty() }
            ?.let { java.io.File(pane(it).path) }
        return shown ?: beside ?: file
    }

    /** A row tapped inside an archive: into a folder, or open a file. */
    fun archiveTap(id: PaneId, name: String) {
        val session = pane(id).archive ?: return
        val rows = ArchiveNav.rows(session)
        val row = rows.firstOrNull { it.name == name } ?: return
        when {
            row.isDirectory -> {
                val next = ArchiveNav.into(session, name)
                update(id) { it.copy(archive = next, entries = ArchiveNav.rows(next), selection = emptySet(), filter = "") }
            }
            // A picture opens in the viewer, and the other pictures in the same
            // archive folder come with it so it can be swiped through -- which
            // is what reading a cbz is. The images are still inside the archive
            // and unpacked one at a time as they are reached.
            ImageFiles.looksImage(name) -> {
                val images = rows
                    .filter { !it.isDirectory && ImageFiles.looksImage(it.name) }
                    .sortedWith(org.filezilla.android.files.NaturalOrder.by { it.name })
                val refs = images.mapNotNull { r ->
                    ArchiveNav.entryFor(session, r.name)?.let { ImageRef.InArchive(session, it) }
                }
                // The book is the archive and the folder within it; its place is
                // kept under that, so it reopens where it was left, and it runs
                // on to the next volume beside it when it ends. Finding that
                // next volume reads the folder off disk, so it is done off the
                // main thread before the viewer is opened.
                val index = images.indexOfFirst { it.name == name }
                val comicKey = "arc\u0000${session.file.path}\u0000${session.at}"
                viewModelScope.launch {
                    val next = withContext(Dispatchers.IO) { nextVolumeAfter(session) }
                    openImageViewer(
                        images = refs,
                        index = index,
                        comicKey = comicKey,
                        book = true,
                        nextVolume = next,
                    )
                }
            }
            else -> openArchiveEntry(id, session, name, password = null)
        }
    }

    /**
     * Back a step inside an archive: a shallower folder, or out of it.
     *
     * Backing out of the root lists [ArchiveSession.home] again, so the
     * one gesture walks up through the archive and then out into the folder
     * it was opened from -- exactly what the back button does in a folder.
     */
    fun archiveUp(id: PaneId): Boolean {
        val session = pane(id).archive ?: return false
        val up = ArchiveNav.up(session)
        if (up != null) {
            update(id) { it.copy(archive = up, entries = ArchiveNav.rows(up), selection = emptySet(), filter = "") }
            return true
        }
        // At the archive's root: step out into the archive that contains this
        // one, or -- when there is none -- out to the folder it sits in.
        val parent = session.parent
        if (parent != null) {
            update(id) {
                it.copy(archive = parent, path = parent.home, entries = ArchiveNav.rows(parent), selection = emptySet(), filter = "")
            }
        } else {
            update(id) { it.copy(archive = null, entries = emptyList(), selection = emptySet(), selecting = false, filter = "") }
            openPath(id, session.home)
        }
        return true
    }

    /** A breadcrumb tap while inside an archive: another archive folder, or out. */
    fun crumbTap(id: PaneId, path: String) {
        val session = pane(id).archive
        if (session != null) {
            val leg = ArchiveNav.leg(path)
            if (leg != null) {
                // A folder in this archive or one that contains it: walk the
                // nesting chain to the depth the crumb names.
                val (depth, at) = leg
                val chain = generateSequence(session) { it.parent }.toList().asReversed()
                val next = chain.getOrNull(depth)?.copy(at = at) ?: return
                update(id) {
                    it.copy(archive = next, path = next.home, entries = ArchiveNav.rows(next), selection = emptySet(), filter = "")
                }
                return
            }
            // A real-path crumb: leave every archive and show that folder.
            update(id) { it.copy(archive = null, entries = emptyList(), selection = emptySet(), selecting = false, filter = "") }
        }
        openPath(id, path)
    }

    /** True while [id] is showing the inside of an archive. */
    fun inArchive(id: PaneId): Boolean = pane(id).archive != null

    fun dismissArchivePassword() {
        archivePasswordAsked = false
        archivePasswordWrong = false
        pendingPassword = null
    }

    /** The answer to the password prompt, dispatched to whatever asked for it. */
    fun submitArchivePassword(password: CharArray) {
        val pending = pendingPassword ?: return
        archivePasswordAsked = false
        when (pending) {
            is PendingPassword.Extract -> runExtract(pending.id, pending.session, pending.picks, pending.into, pending.overwrite, password)
            is PendingPassword.Open -> openArchiveEntry(pending.id, pending.session, pending.name, password)
        }
    }

    /**
     * Opens one file from inside an archive with whatever reads it.
     *
     * The bytes are unpacked to a private cache file and handed to the same
     * door a tapped phone file goes through, so the remembered app, the
     * chooser and the nested-archive case are the ones already written. A
     * locked entry asks for the password first.
     */
    private fun openArchiveEntry(id: PaneId, session: ArchiveSession, name: String, password: CharArray?) {
        val entry = ArchiveNav.entryFor(session, name) ?: return
        if (entry.unreadable != null) {
            archiveOutcome = ArchiveOutcome(R.string.archive_open_failed, listOf(name, ""))
            return
        }
        if (entry.encrypted && password == null) {
            pendingPassword = PendingPassword.Open(id, session, name)
            archivePasswordAsked = true
            return
        }
        // The copy goes in the viewing cache, not somewhere of its own. That
        // folder is the one this app declares to the FileProvider, so the URI
        // handed to "open with" can be built at all -- a copy written
        // anywhere else has no shareable URI, and the chooser comes up empty
        // with "no app can open this". It is also capped and evicted, so
        // opening files out of archives cannot quietly fill the phone.
        graph.viewCache.readyFile(archiveEntryKey(session, entry))?.let { held ->
            graph.viewCache.touch(held)
            if (ArchiveNav.browsable(name)) nestedOrigin = held to name
            readyToOpen = held
            return
        }
        archiveBusy = ArchiveBusy(
            getApplication<android.app.Application>().getString(R.string.archive_extracting),
            name, 0L, 0L, {},
        )
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching { cachedArchiveEntry(session, entry, password) }
            }
            archiveBusy = null
            opened.onSuccess { held ->
                if (ArchiveNav.browsable(name)) nestedOrigin = held to name
                readyToOpen = held
            }
                .onFailure { failure ->
                    if (failure is WrongPassword) {
                        pendingPassword = PendingPassword.Open(id, session, name)
                        archivePasswordAsked = true
                        archivePasswordWrong = true
                    } else {
                        archiveOutcome = ArchiveOutcome(R.string.archive_open_failed, listOf(name, failure.message ?: ""))
                    }
                }
        }
    }

    private fun archiveEntryKey(session: ArchiveSession, entry: ArchiveEntry) = ViewCache.Key(
        serverKey = "archive\u0000" + session.file.path,
        path = entry.path,
        name = entry.name,
        size = entry.size,
        modifiedMillis = entry.modifiedMillis ?: 0,
    )

    /**
     * The cache file holding one archive entry, unpacking it there if it is
     * not already held. Blocking, for an IO context; throws [WrongPassword] or
     * an IOException. The copy lives in the viewing cache -- capped, evicted,
     * and declared to the FileProvider -- so unpacking files out of archives
     * neither fills the phone nor lands somewhere with no shareable URI.
     */
    private fun cachedArchiveEntry(session: ArchiveSession, entry: ArchiveEntry, password: CharArray?): java.io.File {
        val key = archiveEntryKey(session, entry)
        graph.viewCache.readyFile(key)?.let { held ->
            graph.viewCache.touch(held)
            return held
        }
        val partial = graph.viewCache.partialFor(key)
        try {
            Archives.open(session.file).use { archive ->
                // The native readers unpack straight into the cache slot; the
                // rest hand back a stream to copy in. A 7z or rar entry is
                // otherwise written to a temp file and read all the way back
                // out to write here again -- twice the disk for a 100 MB comic.
                if (!archive.extractTo(entry, partial, password)) {
                    archive.open(entry, password).use { source ->
                        partial.outputStream().use { sink -> source.copyTo(sink) }
                    }
                }
            }
        } catch (failure: Throwable) {
            partial.delete()
            throw failure
        }
        val held = graph.viewCache.finish(partial, key)
            ?: throw java.io.IOException("the copy could not be put in place")
        graph.viewCache.evictDownTo(keep = held)
        return held
    }

    /**
     * Unpacks the selected rows of the archive open in [id].
     *
     * The selection is names in the current folder; a chosen folder brings
     * what is under it, the same as everywhere else. With nothing chosen
     * this unpacks the whole archive, which is what the button says then.
     */
    fun extractSelected(id: PaneId) {
        val session = pane(id).archive ?: return
        val names = pane(id).selection
        val picks = if (names.isEmpty()) emptySet() else names.mapTo(mutableSetOf()) { name ->
            val base = if (session.at.isEmpty()) name else session.at + "/" + name
            val isFolder = session.entries.none { it.path == base && !it.isDirectory }
            if (isFolder) "$base/" else base
        }.let { ArchiveBrowsing.expand(session.entries, it) }
        askDestination(id, session, picks)
    }

    /** An unpack whose destination folder already exists, awaiting an answer. */
    data class ArchiveConflict(
        val id: PaneId,
        val session: ArchiveSession,
        val picks: Set<String>,
        val folderName: String,
    )

    var archiveConflict by mutableStateOf<ArchiveConflict?>(null)
        private set

    /**
     * Decides where an unpack lands, asking once when a folder of that name
     * is already there.
     *
     * The whole archive gets one answer -- overwrite what is there, keep it,
     * or a new numbered folder -- and it is applied to every file, rather
     * than a question per file.
     */
    private fun askDestination(id: PaneId, session: ArchiveSession, picks: Set<String>) {
        val base = java.io.File(unpackInto(session.file), Archives.folderNameFor(session.name))
        if (base.exists()) {
            archiveConflict = ArchiveConflict(id, session, picks, base.name)
        } else {
            beginExtract(id, session, picks, base, overwrite = true)
        }
    }

    fun dismissArchiveConflict() {
        archiveConflict = null
    }

    /** The answer to "a folder of this name is already here". */
    fun resolveArchiveConflict(choice: ConflictChoice) {
        val conflict = archiveConflict ?: return
        archiveConflict = null
        val root = unpackInto(conflict.session.file)
        val base = java.io.File(root, Archives.folderNameFor(conflict.session.name))
        val into: java.io.File
        val overwrite: Boolean
        when (choice) {
            // Keep both: a new numbered folder, so nothing already there is
            // touched -- the safe default and today's behaviour.
            ConflictChoice.KEEP_BOTH -> {
                into = java.io.File(root, freeNameIn(root.path, Archives.folderNameFor(conflict.session.name)))
                overwrite = true
            }
            // Overwrite: into the existing folder, replacing same-name files.
            ConflictChoice.OVERWRITE -> { into = base; overwrite = true }
            // Skip: into the existing folder, keeping files already there --
            // resuming an unpack that was stopped part way.
            ConflictChoice.SKIP -> { into = base; overwrite = false }
        }
        beginExtract(conflict.id, conflict.session, conflict.picks, into, overwrite)
    }

    private fun beginExtract(id: PaneId, session: ArchiveSession, picks: Set<String>, into: java.io.File, overwrite: Boolean) {
        val locked = session.entries.any {
            !it.isDirectory && it.encrypted &&
                it.unreadable != ArchiveEntry.Unreadable.ENCRYPTED_METHOD &&
                (picks.isEmpty() || it.path in picks)
        }
        if (locked) {
            pendingPassword = PendingPassword.Extract(id, session, picks, into, overwrite)
            archivePasswordAsked = true
            return
        }
        runExtract(id, session, picks, into, overwrite, null)
    }

    /**
     * Unpacks a selected, unopened archive row -- the bottom bar's counterpart
     * to compress -- into a new folder beside it.
     */
    fun extractArchives(id: PaneId, names: List<String>) {
        val folder = pane(id).path.takeIf { it.isNotEmpty() && pane(id).isLocal } ?: return
        val files = names.map { java.io.File(folder, it) }.filter { ArchiveNav.browsable(it.name) }
        if (files.isEmpty()) return
        clearSelectionIn(id)
        // One archive gets the same destination question as one opened and
        // unpacked; several keep numbering, so a batch is not a wall of
        // prompts.
        if (files.size == 1) {
            val file = files.first()
            archiveOpening = file.name
            viewModelScope.launch {
                val entries = withContext(Dispatchers.IO) {
                    runCatching { Archives.open(file).use { it.entries } }
                }.getOrNull()
                archiveOpening = null
                if (entries == null) {
                    archiveOutcome = ArchiveOutcome(R.string.archive_not_readable)
                } else {
                    askDestination(id, ArchiveSession(file, file.name, file.parent ?: "", entries), emptySet())
                }
            }
        } else {
            extractNext(id, files, 0)
        }
    }

    /**
     * One archive at a time, so several selected archives each get their own
     * folder and each reports its own result -- then on to the next. A batch
     * keeps numbering rather than a folder dialog per archive, which would be
     * a wall of prompts.
     */
    private fun extractNext(id: PaneId, files: List<java.io.File>, index: Int) {
        if (index >= files.size) {
            relistLocalPanes()
            return
        }
        val file = files[index]
        archiveOpening = file.name
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) { runCatching { Archives.open(file).use { it.entries } } }
            archiveOpening = null
            val session = opened.getOrNull()?.let { ArchiveSession(file, file.name, file.parent ?: "", it) }
            val into = java.io.File(unpackInto(file), freeNameIn(file.parent ?: "", Archives.folderNameFor(file.name)))
            when {
                session == null -> {
                    archiveOutcome = ArchiveOutcome(R.string.archive_not_readable)
                    extractNext(id, files, index + 1)
                }
                // A locked one asks for its password; extracting it and going
                // on is left to the answer, so the prompt is not racing the
                // next archive's own progress.
                session.entries.any { it.encrypted } -> {
                    pendingPassword = PendingPassword.Extract(id, session, emptySet(), into, overwrite = true)
                    archivePasswordAsked = true
                }
                else -> runExtract(id, session, emptySet(), into, overwrite = true, null) {
                    extractNext(id, files, index + 1)
                }
            }
        }
    }

    /** How a native reader's extraction ended, the same for rar and 7z. */
    private enum class NativeOutcome { OK, CANCELLED, WRONG_PASSWORD, FAILED }

    private fun RarNative.Result.toOutcome() = when (this) {
        RarNative.Result.OK -> NativeOutcome.OK
        RarNative.Result.CANCELLED -> NativeOutcome.CANCELLED
        RarNative.Result.WRONG_PASSWORD -> NativeOutcome.WRONG_PASSWORD
        RarNative.Result.FAILED -> NativeOutcome.FAILED
    }

    private fun SevenZipNative.Result.toOutcome() = when (this) {
        SevenZipNative.Result.OK -> NativeOutcome.OK
        SevenZipNative.Result.CANCELLED -> NativeOutcome.CANCELLED
        SevenZipNative.Result.WRONG_PASSWORD -> NativeOutcome.WRONG_PASSWORD
        SevenZipNative.Result.FAILED -> NativeOutcome.FAILED
    }

    /**
     * The shared body of the native extractors (rar, 7z).
     *
     * Both unpack in one call rather than entry by entry -- a solid archive
     * would otherwise decode everything before a file once per file -- and
     * both report progress, stop and re-ask for a password the same way. Only
     * the call into the reader differs, which [extract] supplies: it is given
     * where to write, the expanded picks (null for everything), the byte
     * total, whether to keep existing files, a progress report and a stop
     * check, and returns how it ended.
     */
    private fun runNativeExtract(
        id: PaneId,
        session: ArchiveSession,
        picks: Set<String>,
        into: java.io.File,
        overwrite: Boolean,
        onDone: () -> Unit,
        extract: (
            into: java.io.File,
            picks: Set<String>?,
            total: Long,
            skipExisting: Boolean,
            onProgress: (Long, Long, String) -> Unit,
            stopRequested: () -> Boolean,
        ) -> NativeOutcome,
    ) {
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val extracting = getApplication<android.app.Application>().getString(R.string.archive_extracting)
        archiveBusy = ArchiveBusy(extracting, "", 0L, 0L, { stop.set(true) }, bytes = true)

        val covered = if (picks.isEmpty()) null else ArchiveBrowsing.expand(session.entries, picks)
        val total = session.entries
            .filter { !it.isDirectory && (covered == null || it.path in covered) }
            .sumOf { it.size.coerceAtLeast(0) }

        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    into.mkdirs()
                    extract(
                        into, covered, total, !overwrite,
                        { done, totalBytes, name -> archiveBusy = archiveBusy?.copy(done = done, total = totalBytes, path = name) },
                        { stop.get() },
                    ) to into
                }
            }
            archiveBusy = null

            outcome.onSuccess { (result, into) ->
                when (result) {
                    NativeOutcome.WRONG_PASSWORD -> {
                        runCatching { into.deleteRecursively() }
                        pendingPassword = PendingPassword.Extract(id, session, picks, into, overwrite)
                        archivePasswordAsked = true
                        archivePasswordWrong = true
                        return@onSuccess
                    }
                    NativeOutcome.CANCELLED -> {
                        archivePasswordWrong = false
                        val written = into.walkTopDown().count { it.isFile }
                        archiveOutcome = ArchiveOutcome(R.string.archive_extract_stopped, listOf(written))
                    }
                    else -> {
                        archivePasswordWrong = false
                        val written = into.walkTopDown().count { it.isFile }
                        archiveOutcome = when {
                            written == 0 -> ArchiveOutcome(R.string.archive_extract_none)
                            result == NativeOutcome.FAILED ->
                                ArchiveOutcome(R.string.archive_extracted_some, listOf(written, 0))
                            else -> ArchiveOutcome(R.string.archive_extracted, listOf(written, into.name))
                        }
                    }
                }
                relistLocalPanes()
                onDone()
            }.onFailure {
                archiveOutcome = ArchiveOutcome(R.string.archive_not_readable)
            }
        }
    }

    private fun runExtract(
        id: PaneId,
        session: ArchiveSession,
        picks: Set<String>,
        into: java.io.File,
        overwrite: Boolean,
        password: CharArray?,
        onDone: () -> Unit = {},
    ) {
        // RAR and 7z are unpacked by their native readers in one call rather
        // than entry by entry: a solid archive would otherwise decode
        // everything before a file once per file. The shared body handles the
        // bar, the stop button and the password re-ask; only the reader call
        // differs. A no-op entry() -- the listing is not wanted here.
        when (Archives.kindOf(session.file)) {
            Archives.Kind.RAR -> {
                runNativeExtract(id, session, picks, into, overwrite, onDone) { dest, expanded, total, skip, onProgress, stopRequested ->
                    RarNative.extract(session.file, dest, expanded, password, total, skip,
                        object : RarNative.Sink {
                            override fun entry(name: String, size: Long, isDirectory: Boolean, modifiedMillis: Long, encrypted: Boolean) = Unit
                            override fun progress(doneBytes: Long, totalBytes: Long, name: String) = onProgress(doneBytes, totalBytes, name)
                            override fun cancelled() = stopRequested()
                        }).toOutcome()
                }
                return
            }
            Archives.Kind.SEVENZ -> {
                runNativeExtract(id, session, picks, into, overwrite, onDone) { dest, expanded, total, skip, onProgress, stopRequested ->
                    SevenZipNative.extract(session.file, dest, expanded, password, total, skip,
                        object : SevenZipNative.Sink {
                            override fun entry(name: String, size: Long, isDirectory: Boolean, modifiedMillis: Long, encrypted: Boolean, unsupported: Boolean) = Unit
                            override fun progress(doneBytes: Long, totalBytes: Long, name: String) = onProgress(doneBytes, totalBytes, name)
                            override fun cancelled() = stopRequested()
                        }).toOutcome()
                }
                return
            }
            else -> Unit
        }
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val extracting = getApplication<android.app.Application>().getString(R.string.archive_extracting)
        archiveBusy = ArchiveBusy(extracting, "", 0L, 0L, { stop.set(true) }, bytes = true)

        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    into.mkdirs()
                    Archives.open(session.file).use { opened ->
                        ArchiveExtract.run(
                            archive = opened,
                            into = into,
                            picks = picks,
                            password = password,
                            overwrite = overwrite,
                            cancelled = { stop.get() },
                        ) { done, total, path ->
                            archiveBusy = archiveBusy?.copy(done = done, total = total, path = path)
                        }
                    } to into
                }
            }
            archiveBusy = null

            outcome.onSuccess { (result, into) ->
                val allLocked = result.written.isEmpty() &&
                    result.skipped.isNotEmpty() &&
                    result.skipped.all { it.reason == ExtractResult.Reason.PASSWORD }
                if (allLocked) {
                    runCatching { into.delete() }
                    pendingPassword = PendingPassword.Extract(id, session, picks, into, overwrite)
                    archivePasswordAsked = true
                    archivePasswordWrong = true
                    return@onSuccess
                }
                archivePasswordWrong = false
                archiveOutcome = when {
                    result.cancelled -> ArchiveOutcome(R.string.archive_extract_stopped, listOf(result.written.size))
                    result.written.isEmpty() -> ArchiveOutcome(R.string.archive_extract_none)
                    result.skipped.isEmpty() -> ArchiveOutcome(R.string.archive_extracted, listOf(result.written.size, into.name))
                    else -> ArchiveOutcome(R.string.archive_extracted_some, listOf(result.written.size, result.skipped.size))
                }
                if (result.written.isNotEmpty()) relistLocalPanes()
                onDone()
            }.onFailure {
                archiveOutcome = ArchiveOutcome(R.string.archive_not_readable)
            }
        }
    }

    /**
     * Makes a zip of [picks] in [folder], and puts it there.
     *
     * Always a zip; see [ArchiveWriter] for why there is no choice to
     * make. The name is the folder's own when one folder was chosen and
     * the containing folder's otherwise, which is what a person would
     * have typed.
     */
    fun compress(id: PaneId, picks: List<String>) {
        if (picks.isEmpty()) return
        val folder = pane(id).path.takeIf { it.isNotEmpty() && pane(id).isLocal } ?: return
        val sources = picks.map { java.io.File(folder, it) }
        val stem = if (sources.size == 1) Archives.folderNameFor(sources.first().name)
        else java.io.File(folder).name.ifEmpty { "archive" }
        val target = java.io.File(folder, freeNameIn(folder, "$stem.zip"))

        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val compressing = getApplication<android.app.Application>()
            .getString(R.string.archive_compressing)
        archiveBusy = ArchiveBusy(compressing, "", 0L, 0L, onStop = { stop.set(true) }, bytes = true)

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ArchiveWriter.zip(sources, target, cancelled = { stop.get() }) { done, total, name ->
                        archiveBusy = archiveBusy?.copy(done = done, total = total, path = name)
                    }
                }
            }
            archiveBusy = null
            result.onSuccess { written ->
                if (written.cancelled) {
                    // Half a zip is a file that opens and is wrong, so it
                    // goes rather than being left to be found later.
                    runCatching { target.delete() }
                    archiveOutcome = ArchiveOutcome(
                        R.string.archive_compress_stopped,
                        listOf(target.name),
                    )
                } else {
                    archiveOutcome = ArchiveOutcome(
                        R.string.archive_compressed,
                        listOf(target.name, written.entries),
                    )
                }
                clearSelectionIn(id)
                relistLocalPanes()
            }.onFailure {
                runCatching { target.delete() }
                archiveOutcome = ArchiveOutcome(R.string.archive_compress_stopped, listOf(target.name))
            }
        }
    }

    // ------------------------------------------------- the server's certificate

    /**
     * The certificate somebody is being asked to recognise, if any.
     *
     * One at a time and app-wide rather than per pane: the question is
     * about a server, both panes can be pointed at the same one, and being
     * asked the same question twice is how people learn to dismiss it
     * without reading.
     */
    var certificateQuestion by mutableStateOf<CertificateQuestion?>(null)
        private set

    /**
     * Turns a failure into something to show, and raises the certificate
     * question when that is what the failure was.
     *
     * Every connection failure in this class goes through here, so a
     * refused certificate cannot reach the user as a bare "could not
     * connect" from whichever operation happened to be first.
     *
     * A refusal during a queued transfer is not routed here and does not
     * need to be: it fails the transfer, which is the safe outcome, and the
     * next time the user browses that server they get asked properly.
     */
    private fun failureOn(site: SiteEntity?, error: Throwable): ConnectionFailure {
        val refused = generateSequence(error) { it.cause }
            .filterIsInstance<CertificateNotTrusted>()
            .firstOrNull()
        if (refused != null && site != null && certificateQuestion == null) {
            certificateQuestion = CertificateQuestion(
                site = site,
                certificate = refused.certificate,
                replacing = refused.previouslyTrusted,
            )
        }
        return describeFailure(error, graph.networkGate.currentlyOnline())
    }

    fun dismissCertificateQuestion() {
        certificateQuestion = null
    }

    /**
     * Records that this certificate is the server, and goes back in.
     *
     * The fingerprint is saved against the site before anything reconnects,
     * and the pooled connection for that site is dropped -- its key
     * includes the pin, but a connection opened under the old settings
     * would otherwise be handed to the retry.
     */
    fun trustCertificate() {
        val question = certificateQuestion ?: return
        certificateQuestion = null
        viewModelScope.launch {
            val pinned = question.site.copy(
                pinnedCertificate = question.certificate.fingerprint,
            )
            graph.database.sites().upsert(pinned)
            graph.transfers.forget(question.site)

            // Both panes, because both may be sitting on the failure. The
            // site object each pane holds is the one from before the pin,
            // so it is replaced rather than reused -- a stale copy would
            // reconnect with no pin and be refused again.
            for (id in PaneId.entries) {
                val source = pane(id).source
                if (source is PaneSource.Remote && source.site.id == pinned.id) {
                    update(id) { it.copy(source = PaneSource.Remote(pinned), error = null) }
                    loadRemote(id, pinned, pane(id).path.takeIf { it.isNotEmpty() }, fresh = true)
                }
            }
        }
    }

    private fun loadRemote(id: PaneId, site: SiteEntity, path: String?, fresh: Boolean = false) {
        val asked = askForListing(id)
        if (!fresh && path != null) {
            // Straight onto the screen, in this frame, with no coroutine and
            // no loading state: a spinner for a listing that is already in
            // hand would put a flicker where the win was supposed to be.
            val known = graph.transfers.listings.recall(site, path)
            if (known != null) {
                graph.preferences.setPanePath(id.name, siteSourceKey(site), known.path)
                update(id) {
                    it.copy(
                        source = PaneSource.Remote(site),
                        path = known.path,
                        entries = known.entries,
                        selection = if (known.path == it.path) {
                            it.prunedSelection(known.entries)
                        } else {
                            emptySet()
                        },
                        loading = false,
                        error = null,
                    )
                }
                return
            }
        }
        update(id) { it.copy(source = PaneSource.Remote(site), loading = true, error = null) }
        // Read before the question is asked, handed back with the answer.
        // A write that lands while this listing is in flight empties the
        // cache, and without this the answer -- taken before the write --
        // would be put back in afterwards.
        val asOf = graph.transfers.listings.asOf()
        viewModelScope.launch {
            runCatching {
                graph.transfers.browse(site) { session ->
                    // Where the server says we are, never the path that was
                    // asked for: it may have been relative or a symbolic
                    // link, and building the next path on a guess is how a
                    // browser ends up listing the wrong directory.
                    //
                    // Many servers answer that in the CWD reply itself, and
                    // taking it there saves a round trip on every folder
                    // opened -- a quarter of the cost, on a server far
                    // enough away to notice. The ones that do not get asked.
                    val moved = if (path != null) session.changeDirectory(path) else null
                    val here = moved ?: session.currentDirectory()
                    here to session.list()
                }
            }.onSuccess { (here, entries) ->
                if (!stillWanted(id, asked)) return@launch
                // Kept under the path that was asked for, not under the one
                // the server resolved it to: the next visit will ask by the
                // same name this one did.
                if (path != null) graph.transfers.listings.remember(site, path, here, entries, asOf)
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
                        error = failureOn(pane(id).site, error),
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
        val stop = Stoppable()
        viewModelScope.launch {
            runCatching {
                val plan = if (FolderDownload.needsRemoteWalk(picks)) {
                    // One connection for the whole walk: a session per folder
                    // would reconnect for every level of the tree.
                    serverWork = ServerWork(ServerWork.Kind.SCANNING, stopper = stop::stop)
                    graph.transfers.browse(site) { session ->
                        planDownload(session, directory, picks, stop)
                    }
                } else {
                    // Files name themselves, so there is nothing to ask the
                    // server. Connecting anyway would put a login in front of
                    // every single-file download, which is most of them.
                    FolderDownload.plan({ emptyList() }, directory, picks)
                }
                plan to findConflicts(plan, folder)
            }.onSuccess { (plan, conflicts) ->
                serverWork = null
                browse = browse.copy(loading = false)
                if (plan.cancelled) {
                    workOutcome = WorkOutcome(org.filezilla.android.R.string.work_scan_stopped, 0, 0)
                    onQueued(DownloadPlan())
                    return@onSuccess
                }
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
                serverWork = null
                browse = browse.copy(
                    loading = false,
                    error = failureOn(site, error),
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
        val stop = Stoppable()
        serverWork = ServerWork(ServerWork.Kind.SCANNING, stopper = stop::stop)

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
                cancelled = stop::stopped,
                onFolder = { read ->
                    serverWork = ServerWork(
                        ServerWork.Kind.SCANNING,
                        foldersRead = read,
                        stopper = stop::stop,
                    )
                },
            )
            // Stopped before anything was removed. Nothing to report beyond
            // the pane coming back as it was.
            if (plan.cancelled) return@mutate
            // Refused rather than part-done. Half a delete leaves a tree the
            // user did not ask for and cannot see the shape of, and the
            // failing RMD at the end of it would not say which half.
            //
            // Not the same thing as the stop above, which is somebody
            // deciding to halt a plan they can see the size of. This is the
            // app not knowing the shape of the tree at all.
            if (plan.truncated) throw TooMuchToDeleteException()

            val done = RemoteDelete.remove(
                steps = plan.steps,
                remover = { step ->
                    if (step.isDirectory) {
                        session.removeDirectory(step.path)
                    } else {
                        session.deleteFile(step.path)
                    }
                },
                cancelled = stop::stopped,
                onProgress = { sent, total, next ->
                    serverWork = ServerWork(
                        ServerWork.Kind.DELETING,
                        done = sent,
                        total = total,
                        current = next.path.substringAfterLast('/'),
                        stopper = stop::stop,
                    )
                },
            )
            if (done < plan.steps.size) {
                workOutcome = WorkOutcome(
                    org.filezilla.android.R.string.work_delete_stopped,
                    done,
                    plan.steps.size,
                )
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
                serverWork = null
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
                serverWork = null
                if (!stillWanted(id, asked)) return@launch
                update(id) {
                    it.copy(
                        loading = false,
                        error = failureOn(pane(id).site, error),
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

    fun pauseAll() = viewModelScope.launch { graph.transfers.pauseAll() }.let { }

    /**
     * Sets everything outstanding going again.
     *
     * [onReady] runs once the journal has been written, and starts the
     * service. Ordered, because a service that drains the queue before the
     * records say PENDING drains an empty one and stops.
     */
    fun startAll(onReady: () -> Unit) = viewModelScope.launch {
        graph.transfers.startAll()
        onReady()
    }.let { }

    fun clearFailed() = viewModelScope.launch { graph.transfers.clearFailed() }.let { }

    fun clearAllTransfers() = viewModelScope.launch { graph.transfers.clearAll() }.let { }

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

        /** How many whole image files the band decoder keeps around at once. */
        const val IMAGE_BYTE_CACHE = 4
    }
}
