package org.filezilla.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    options: BrowseOptions,
    downloadFolderName: String?,
    onOpenLog: () -> Unit,
    onGrant: () -> Unit,
    onPickSite: () -> Unit,
    onDownload: (org.filezilla.ftp.listing.DirectoryEntry) -> Unit,
    onRequestNotifications: () -> Unit,
    onTransfersQueued: (Int) -> Unit,
    onDownloadSelected: () -> Unit,
    onOpenLocalFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState(initialPage = pageOf(model.activePane)) { PaneId.entries.size }

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

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!bothPanes) PaneTabs(current = pager.currentPage, model = model)

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
                                options = options,
                                downloadFolderName = downloadFolderName,
                                onOpenLog = onOpenLog,
                                onGrant = onGrant,
                                onPickSite = onPickSite,
                                onDownload = onDownload,
                                onOpenLocalFile = onOpenLocalFile,
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
                        options = options,
                        downloadFolderName = downloadFolderName,
                        onOpenLog = onOpenLog,
                        onGrant = onGrant,
                        onPickSite = onPickSite,
                        onDownload = onDownload,
                        onOpenLocalFile = onOpenLocalFile,
                    )
                }
            }

            // The bars belong to the pane in front, so they sit outside the
            // pager rather than inside each page: a selection made on one side
            // is acted on where it was made, and swiping does not carry the
            // bar to a pane the selection is not in.
            if (state.selecting && state.selection.isNotEmpty()) {
                SelectionBar(
                    count = state.selection.size,
                    canRename = state.selection.size == 1,
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
        NameDialog(
            title = if (thing == NewThing.FOLDER) R.string.new_folder_title else R.string.new_file_title,
            onDismiss = { naming = null },
            onConfirm = { name ->
                if (thing == NewThing.FOLDER) model.createFolder(active, name)
                else model.createFile(active, name)
                naming = null
            },
        )
    }

    if (renaming) {
        val entry = state.entries.firstOrNull { it.name in state.selection }
        if (entry == null) {
            renaming = false
        } else {
            NameDialog(
                title = R.string.action_rename_selected,
                initial = entry.name,
                onDismiss = { renaming = false },
                onConfirm = { name ->
                    model.renameLocal(active, entry, name)
                    model.clearSelection()
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
                model.deleteSelection(active)
                confirmingDelete = false
            },
        )
    }
}

/** Deleting takes folders with everything in them, so it is asked about first. */
@Composable
private fun ConfirmDelete(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_delete_title, count)) },
        text = { Text(stringResource(R.string.confirm_delete_detail)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete_selected))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * Which pane is which, and which one you are on.
 *
 * Named rather than numbered -- "내 파일" and the server's own name say what
 * a swipe would land on, where "1" and "2" would not.
 */
@Composable
private fun PaneTabs(current: Int, model: MainViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (id in PaneId.entries) {
            val selected = pageOf(id) == current
            Text(
                paneLabel(model.pane(id).source),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun paneLabel(source: PaneSource): String = when (source) {
    PaneSource.Local -> stringResource(R.string.side_local)
    PaneSource.Empty -> stringResource(R.string.pane_no_source)
    is PaneSource.Remote -> source.site.name.ifBlank { source.site.host }
}

/** One pane: its header, then whichever body its source calls for. */
@Composable
private fun PaneBody(
    id: PaneId,
    model: MainViewModel,
    options: BrowseOptions,
    downloadFolderName: String?,
    onOpenLog: () -> Unit,
    onGrant: () -> Unit,
    onPickSite: () -> Unit,
    onDownload: (org.filezilla.ftp.listing.DirectoryEntry) -> Unit,
    onOpenLocalFile: (String) -> Unit,
) {
    val state = model.pane(id)

    // Sorting and filtering the listing is the one piece of real work this
    // composable does, and a folder can hold thousands of rows. Keyed on
    // everything that changes the answer, so a recomposition caused by
    // anything else -- a selection, a touch landing in the other pane -- does
    // not re-sort the whole listing before drawing a frame.
    val rows = remember(state.entries, options, state.filter) {
        BrowseListing.arrange(state.entries, options, state.filter)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PaneHeader(id = id, model = model, onPickSite = onPickSite)
        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        when {
            state.source is PaneSource.Empty -> EmptyState(
                title = stringResource(R.string.pane_pick_title),
                detail = stringResource(R.string.pane_pick_detail),
                icon = R.drawable.ic_flat_server,
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
                downloadFolderName = downloadFolderName,
                onUp = { model.up(id) },
                onRefresh = { model.open(id) },
                onOpenLog = onOpenLog,
                onFilterChange = model::setFilter,
                onCloseFilter = model::toggleFilter,
                actions = EntryActions(
                    onOpen = { model.openChild(id, it.name) },
                    // On the phone a tap opens the file; on a server it
                    // fetches it. Tapping a local file used to start a
                    // download of a file that was already here, and with no
                    // download folder chosen that opened the folder picker.
                    onDownload = { entry ->
                        if (state.isLocal) {
                            onOpenLocalFile(FilePath.child(model.pane(id).path, entry.name))
                        } else {
                            onDownload(entry)
                        }
                    },
                    onDelete = model::delete,
                    onRename = model::rename,
                    onProperties = { model.showProperties(it) },
                    onToggleSelected = { model.toggleSelected(it.name) },
                ),
            )
        }
    }
}

/**
 * What this pane is looking at, and the way to point it somewhere else.
 *
 * The source chip is the whole navigation for a pane: tapping it is how the
 * phone becomes a server and back again, which is what makes either side able
 * to be either thing.
 */
@Composable
private fun PaneHeader(id: PaneId, model: MainViewModel, onPickSite: () -> Unit) {
    val state = model.pane(id)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = { model.showLocal(id) },
            label = { Text(stringResource(R.string.side_local)) },
            leadingIcon = { SourceDot(active = state.isLocal) },
        )
        AssistChip(
            onClick = onPickSite,
            label = {
                Text(state.site?.let { it.name.ifBlank { it.host } } ?: stringResource(R.string.side_remote))
            },
            leadingIcon = { SourceDot(active = state.site != null) },
        )
        if (model.storageGranted && state.isLocal) {
            for (root in model.storageRoots()) {
                AssistChip(
                    onClick = { model.openPath(id, root.path) },
                    label = { Text(root.label) },
                )
            }
        }
        IconButton(onClick = { model.open(id) }) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.browse_refresh),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Marks which source a pane is actually on, since both chips are always there. */
@Composable
private fun SourceDot(active: Boolean) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(
                if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                CircleShape,
            ),
    )
}
