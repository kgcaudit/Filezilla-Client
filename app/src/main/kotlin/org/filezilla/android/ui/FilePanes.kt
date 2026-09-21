package org.filezilla.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.filezilla.android.R
import org.filezilla.android.files.FilePath

/**
 * The two panes, side by side in principle and one at a time on a phone.
 *
 * A pager rather than two columns because a phone has room for one listing
 * with names in it, and a file manager whose names are all truncated is one
 * nobody can use. The second pane is a swipe away, and which one is in front
 * is what every toolbar action means by "this pane".
 */
@Composable
fun FilePanes(
    model: MainViewModel,
    onOpenLog: () -> Unit,
    onGrant: () -> Unit,
    onPickSite: () -> Unit,
    onDownload: (org.filezilla.ftp.listing.DirectoryEntry) -> Unit,
    onRequestNotifications: () -> Unit,
    onTransfersQueued: (Int) -> Unit,
    onDownloadSelected: () -> Unit,
    onOpenLocalFile: (String) -> Unit,
    /** The same, but always asking which app rather than using the remembered one. */
    onOpenLocalFileWith: (String) -> Unit,
    /** Hands the given full paths, all on this phone, to another app. */
    onShareLocal: (List<String>) -> Unit,
    onNewDirectory: () -> Unit,
    onUpload: () -> Unit,
    onOpenScreen: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState(initialPage = pageOf(model.activePane)) { PaneId.entries.size }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Asked of the width every time, so folding the device back up puts it
    // back to one pane.
    val bothPanes = showsBothPanes(
        LocalConfiguration.current.screenWidthDp,
    )

    // The pager is the one record of which pane is in front. Tracking it
    // separately as well would give two answers that drift apart, and the
    // toolbar would start acting on the pane the user cannot see.
    LaunchedEffect(pager, bothPanes) {
        if (bothPanes) {
            // Both are on screen, so both need contents -- the one behind is
            // no longer filled by being swiped to.
            for (id in PaneId.entries) model.ensureOpen(id)
        } else {
            snapshotFlow { pager.currentPage }.collect { model.showPane(paneAt(it)) }
        }
    }

    // With both panes on screen the pager is not what says which one is being
    // acted on -- the last one touched is -- so the active pane comes from
    // the view model there and from the pager otherwise.
    val active = if (bothPanes) model.activePane else paneAt(pager.currentPage)
    val state = model.pane(active)
    var dialOpen by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<NewThing?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    // No inset taken here. The Scaffold already applies its content window
    // insets whether or not it has bars to apply them around, so taking them
    // again put two status bars' worth of nothing above the tab strip.
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!bothPanes) {
                PaneTabs(
                    current = pager.currentPage,
                    model = model,
                    onPick = { page -> scope.launch { pager.animateScrollToPage(page) } },
                )
            }

            if (bothPanes) {
                Row(modifier = Modifier.weight(1f)) {
                    for (id in PaneId.entries) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                // Touching a pane is what makes it the one the
                                // bars act on. Without this the toolbar would
                                // keep acting on whichever was active last,
                                // which with both in view is invisible.
                                .pointerInput(id) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitPointerEvent(PointerEventPass.Initial)
                                            model.focusPane(id)
                                        }
                                    }
                                }
                                .background(
                                    if (id == active) {
                                        MaterialTheme.colorScheme.surface
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    },
                                ),
                        ) {
                            PaneBody(
                                id = id,
                                model = model,
                                onOpenLog = onOpenLog,
                                onGrant = onGrant,
                                onPickSite = onPickSite,
                                onDownload = onDownload,
                                onOpenLocalFile = onOpenLocalFile,
                                onOpenLocalFileWith = onOpenLocalFileWith,
                                onNewDirectory = onNewDirectory,
                                onUpload = onUpload,
                                onOpenScreen = onOpenScreen,
                            )
                        }
                    }
                }
            } else {
                HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                    val id = paneAt(page)
                    PaneBody(
                        id = id,
                        model = model,
                                    onOpenLog = onOpenLog,
                        onGrant = onGrant,
                        onPickSite = onPickSite,
                        onDownload = onDownload,
                        onOpenLocalFile = onOpenLocalFile,
                        onOpenLocalFileWith = onOpenLocalFileWith,
                        onNewDirectory = onNewDirectory,
                        onUpload = onUpload,
                        onOpenScreen = onOpenScreen,
                    )
                }
            }

            // The bars belong to the pane in front, so they sit outside the
            // pager rather than inside each page: a selection made on one side
            // is acted on where it was made, and swiping does not carry the
            // bar to a pane the selection is not in.
            if (state.selecting && state.selection.isNotEmpty()) {
                // Folders are dropped: nothing can be handed a folder through
                // a share sheet, so picking one alongside six photos shares
                // the six rather than refusing the lot.
                val sharable = state.entries
                    .filter { it.name in state.selection && !it.isDirectory }
                    .map { FilePath.child(state.path, it.name) }
                SelectionBar(
                    count = state.selection.size,
                    canRename = state.selection.size == 1,
                    // Only on the phone's side. The rows on a server pane are
                    // not files on this device, and sharing one would mean
                    // downloading it first.
                    onShare = if (state.isLocal) ({ onShareLocal(sharable) }) else null,
                    canShare = sharable.isNotEmpty(),
                    // Folders are kept here, unlike sharing: a zip of a
                    // folder is the commonest thing anybody wants a zip of.
                    onCompress = if (state.isLocal) {
                        { model.compress(active, state.selection.toList()) }
                    } else {
                        null
                    },
                    onCut = { model.cutSelection(active) },
                    onCopy = { model.copySelection(active) },
                    onDelete = { confirmingDelete = true },
                    onRename = { renaming = true },
                    onClear = model::clearSelection,
                    onDownload = if (state.site != null) onDownloadSelected else null,
                )
            } else {
                model.clipboard?.let { held ->
                    PasteBar(
                        count = held.names.size,
                        refusal = model.pasteRefusal(active),
                        kind = model.pasteKind(active),
                        cut = held.mode == ClipboardMode.MOVE,
                        onPaste = {
                            when (model.pasteKind(active)) {
                                // Within one place it happens here and now;
                                // between two it joins the queue, which is
                                // what shows the progress and survives the
                                // app being closed.
                                PasteKind.FILE_OPERATION -> model.paste(active)
                                // Within one server: a rename across
                                // directories, which is instant and moves no
                                // bytes, so it does not join the queue.
                                PasteKind.REMOTE_MOVE -> model.moveOnServer(active)
                                null -> Unit
                                else -> {
                                    onRequestNotifications()
                                    model.pasteAcross(active) { count ->
                                        if (count > 0) onTransfersQueued(count)
                                    }
                                }
                            }
                        },
                        onCancel = model::clearClipboard,
                        // Only where a folder can be made: the phone always,
                        // a server once it is connected. An empty pane has
                        // nowhere to make one.
                        onNewFolder = if (state.source is PaneSource.Empty) {
                            null
                        } else {
                            { naming = NewThing.FOLDER }
                        },
                    )
                }
            }

        }

        // Hidden while a bar is up, and not only for tidiness: the button
        // floats over the bottom-right corner, which is exactly where the
        // paste button sits -- so the one action the paste bar exists for was
        // underneath it and could not be pressed.
        val barShowing = (state.selecting && state.selection.isNotEmpty()) || model.clipboard != null
        if (state.isLocal && model.storageGranted && !barShowing) {
            NewThingFab(
                expanded = dialOpen,
                onExpandedChange = { dialOpen = it },
                onPick = { thing ->
                    if (thing == NewThing.SERVER) onPickSite() else naming = thing
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    naming?.let { thing ->
        val folder = thing == NewThing.FOLDER
        OloPromptDialog(
            title = if (folder) R.string.new_folder_title else R.string.new_file_title,
            // The verb of the title, not "OK". A button labelled with an
            // acknowledgement says nothing about what pressing it does,
            // and every other action in the app names itself.
            confirmLabel = R.string.action_create,
            detail = if (folder) R.string.new_folder_detail else R.string.new_file_detail,
            label = R.string.prompt_name,
            onDismiss = { naming = null },
            onConfirm = { name ->
                // Through the pane, not through the phone: the dial can be
                // opened on one pane and confirmed after a swipe to the other.
                if (folder) model.createFolderIn(active, name)
                else model.createFileIn(active, name)
                naming = null
            },
        )
    }

    if (renaming) {
        val entry = state.entries.firstOrNull { it.name in state.selection }
        // The row can go out from under an open dialog -- a refresh lands and
        // the selection no longer matches anything. Closing it is a side
        // effect: assigning to state from the composition body is a write to
        // the very thing being read, which re-runs the composition that is
        // running and is what Compose warns about.
        LaunchedEffect(entry == null) { if (entry == null) renaming = false }
        if (entry != null) {
            OloPromptDialog(
                // Names the thing being renamed. It used to carry the menu
                // item's own label, so a dialog asking about one file was
                // titled with the button that had opened it.
                title = R.string.prompt_rename_title,
                confirmLabel = R.string.action_change,
                titleArg = entry.name,
                detail = R.string.rename_detail,
                label = R.string.prompt_new_name,
                initial = entry.name,
                onDismiss = { renaming = false },
                onConfirm = { name ->
                    model.renameIn(active, entry, name)
                    model.clearSelectionIn(active)
                    renaming = false
                },
            )
        }
    }

    if (confirmingDelete) {
        ConfirmDelete(
            count = state.selection.size,
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                model.deleteSelectionIn(active)
                confirmingDelete = false
            },
        )
    }
}

/** Deleting takes folders with everything in them, so it is asked about first. */
@Composable
private fun ConfirmDelete(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    OloConfirmDialog(
        title = pluralStringResource(R.plurals.confirm_delete_selected, count, count),
        detail = stringResource(R.string.confirm_delete_detail),
        confirmLabel = stringResource(R.string.action_delete),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/**
 * Which pane is which, and which one you are on.
 *
 * Named rather than numbered -- "내 파일" and the server's own name say what
 * a swipe would land on, where "1" and "2" would not.
 */
@Composable
private fun PaneTabs(current: Int, model: MainViewModel, onPick: (Int) -> Unit) {
    TabRow(
        selectedTabIndex = current,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        for (id in PaneId.entries) {
            val page = pageOf(id)
            Tab(
                selected = page == current,
                // Tapping is the other way to reach a pane, and the one that
                // works when there is nothing to tell you a swipe would do
                // anything. The label alone was not a control.
                onClick = { onPick(page) },
                text = {
                    Text(
                        paneLabel(model, id),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (page == current) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What a tab says.
 *
 * A local pane is named by the folder it is in rather than by the words "my
 * files", because both panes can be on the phone at once -- and then both
 * tabs said "my files" and neither said which was which.
 *
 * The name comes from the end of the crumb trail rather than from the path,
 * and that is the fix for a tab that read "0". A pane sitting at the phone's
 * own storage is at /storage/emulated/0, whose last segment is the digit
 * zero; the trail already knows that folder as "내부 저장소", because naming
 * the volumes is what its first crumb is for.
 *
 * A server keeps its own name instead: which server you are on is what
 * matters there, and the folder is in the trail a line below.
 */
@Composable
private fun paneLabel(model: MainViewModel, id: PaneId): String {
    val state = model.pane(id)
    return when (state.source) {
        PaneSource.Empty -> stringResource(R.string.pane_no_source)
        PaneSource.Local -> model.breadcrumbsFor(id).lastOrNull()?.label
            ?: stringResource(R.string.side_local)

        is PaneSource.Remote -> (state.source as PaneSource.Remote).site.let {
            it.name.ifBlank { it.host }
        }
    }
}

/** One pane: its header, then whichever body its source calls for. */
@Composable
private fun PaneBody(
    id: PaneId,
    model: MainViewModel,
    onOpenLog: () -> Unit,
    onGrant: () -> Unit,
    onPickSite: () -> Unit,
    onDownload: (org.filezilla.ftp.listing.DirectoryEntry) -> Unit,
    onOpenLocalFile: (String) -> Unit,
    onOpenLocalFileWith: (String) -> Unit,
    onNewDirectory: () -> Unit,
    onUpload: () -> Unit,
    onOpenScreen: (Screen) -> Unit,
) {
    val state = model.pane(id)

    // Asked for once and used for all three of the header, the listing and
    // the options dialog. It used to be a parameter as well, handed down
    // from the screen above -- and when folders were given settings of
    // their own, the header and the dialog were moved onto this and the
    // listing was not. So the dialog showed the folder's own sort and the
    // rows underneath stayed in the shared one, which is precisely what the
    // user reported. There is nowhere for that to hide now: the parameter
    // is gone, so a second source of settings would not compile.
    val options = model.optionsFor(id)

    // Sorting and filtering the listing is the one piece of real work this
    // composable does, and a folder can hold thousands of rows. Keyed on
    // everything that changes the answer, so a recomposition caused by
    // anything else -- a selection, a touch landing in the other pane -- does
    // not re-sort the whole listing before drawing a frame.
    val rows = remember(state.entries, options, state.filter) {
        BrowseListing.arrange(state.entries, options, state.filter)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PaneHeader(
            id = id,
            model = model,
            // This pane's settings, which are the shared ones unless the
            // folder it is in has been given some of its own.
            options = options,
            onNewDirectory = onNewDirectory,
            onUpload = onUpload,
            onGrant = onGrant,
            onOpenScreen = onOpenScreen,
        )
        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        when {
            state.source is PaneSource.Empty -> EmptyState(
                title = stringResource(R.string.pane_pick_title),
                detail = stringResource(R.string.pane_pick_detail),
                icon = R.drawable.ic_tile_server,
                modifier = Modifier.fillMaxWidth(),
            )

            state.isLocal && !model.storageGranted -> StorageGate(
                route = model.storageRoute,
                onGrant = onGrant,
                modifier = Modifier.fillMaxWidth(),
            )

            else -> BrowseScreen(
                state = state,
                rows = rows,
                options = options,
                onRefresh = { model.open(id) },
                onOpenLog = onOpenLog,
                onFilterChange = model::setFilter,
                onCloseFilter = model::toggleFilter,
                onSearchDeeper = { model.searchDeeper(id) },
                onStopWalking = { model.stopWalking(id) },
                onCloseSearch = { model.stopSearch(id) },
                onOpenHit = { model.openHit(id, it) },
                actions = EntryActions(
                    onOpen = { model.openChild(id, it.name) },
                    // The button beside a server row, and nothing else. It
                    // means "keep a copy", which is a different act from
                    // looking at the file -- the two shared this call, and
                    // that is why reading a text file on a server began by
                    // choosing where to save it.
                    onDownload = onDownload,
                    // A tap, on either side. On the phone the file is here;
                    // on a server a copy is fetched first and then opened
                    // the same way, by whichever app the extension is
                    // remembered against.
                    onOpenFile = { entry ->
                        if (state.isLocal) {
                            onOpenLocalFile(FilePath.child(model.pane(id).path, entry.name))
                        } else {
                            model.viewOnServer(id, entry)
                        }
                    },
                    onDelete = model::delete,
                    onRename = model::rename,
                    onProperties = { model.showProperties(it) },
                    onToggleSelected = { model.toggleSelected(it.name) },
                    onOpenWith = { entry ->
                        onOpenLocalFileWith(FilePath.child(model.pane(id).path, entry.name))
                    },
                    onChangeMode = { model.changeModeOf(id, it) },
                ),
            )
        }
    }
}
