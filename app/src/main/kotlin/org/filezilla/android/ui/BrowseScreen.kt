package org.filezilla.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.ftp.listing.DirectoryEntry

/** What a row can have done to it, gathered so the two layouts share one set. */
class EntryActions(
    val onOpen: (DirectoryEntry) -> Unit,
    val onDownload: (DirectoryEntry) -> Unit,
    val onDelete: (DirectoryEntry) -> Unit,
    /** Both halves: the row being renamed, and what to call it. */
    val onRename: (DirectoryEntry, String) -> Unit,
    val onProperties: (DirectoryEntry) -> Unit,
    val onToggleSelected: (DirectoryEntry) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    state: BrowseState,
    rows: List<DirectoryEntry>,
    options: BrowseOptions,
    onRefresh: () -> Unit,
    onOpenLog: () -> Unit,
    onFilterChange: (String) -> Unit,
    onCloseFilter: () -> Unit,
    actions: EntryActions,
    modifier: Modifier = Modifier,
) {
    // On the source, not on the site: a pane showing the phone has no site
    // and is not "not connected" -- it is exactly where it should be. Keyed
    // on the site, every local pane claimed to need a server.
    if (state.source is PaneSource.Empty) {
        EmptyState(
            title = stringResource(R.string.browse_not_connected_title),
            detail = stringResource(R.string.browse_not_connected_detail),
            icon = R.drawable.ic_tile_server,
            modifier = modifier,
        )
        return
    }

    var renaming by remember { mutableStateOf<DirectoryEntry?>(null) }
    var deleting by remember { mutableStateOf<DirectoryEntry?>(null) }

    Column(modifier = modifier) {
        if (state.filterOpen) {
            FilterBar(state.filter, onFilterChange, onCloseFilter)
        }

        state.error?.let { failure ->
            ErrorPanel(failure = failure, onRetry = onRefresh, onOpenLog = onOpenLog)
        }

        HorizontalDivider()

        // Counted rather than flagged: see [refreshIndicatorShown]. The
        // indicator's whole lifecycle hangs off this going up.
        var pulls by remember { mutableLongStateOf(0L) }
        PullToRefreshBox(
            isRefreshing = refreshIndicatorShown(pulls, state.loading),
            onRefresh = {
                pulls++
                onRefresh()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                // A filter that matches nothing must say so. An empty list
                // otherwise looks like an empty directory, and the user has
                // no way to tell which it is.
                rows.isEmpty() && state.filter.isNotBlank() -> EmptyState(
                    title = stringResource(R.string.filter_none, state.filter),
                    detail = stringResource(R.string.filter_hint),
            icon = R.drawable.ic_tile_search,
                )

                options.viewMode == ViewMode.GRID -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(rows, key = { it.name }) { entry ->
                        GridTile(entry, entry.name in state.selection, state.selecting, actions)
                    }
                }

                else -> Box(modifier = Modifier.fillMaxSize()) {
                    val listState = rememberLazyListState()
                    // Letters only when the rows are in an order letters
                    // describe. Sorted by date, a rail reading ㄱ ㄴ ㄷ would
                    // be a lie about where a tap lands.
                    val stops = remember(rows, options.sortKey) {
                        if (options.sortKey == SortKey.NAME) {
                            ScrollIndex.stopsFor(rows.map { it.name })
                        } else {
                            emptyList()
                        }
                    }
                    val railed = rows.size >= FAST_SCROLL_FROM
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // The rail gets a lane of its own. Drawn over the
                        // rows it lands on the row menus, and a list whose
                        // last control is under a scroll bar is a list with
                        // two things fighting for the same thumb.
                        contentPadding = PaddingValues(
                            end = if (railed) FAST_SCROLL_WIDTH else 0.dp,
                        ),
                    ) {
                        items(rows, key = { it.name }) { entry ->
                            EntryRow(
                                entry = entry,
                                selected = entry.name in state.selection,
                                selecting = state.selecting,
                                isLocal = state.isLocal,
                                actions = actions,
                                onRename = { renaming = entry },
                                onDelete = { deleting = entry },
                            )
                            HorizontalDivider()
                        }
                    }
                    FastScroller(
                        state = listState,
                        rowCount = rows.size,
                        stops = stops,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
    }

    // Asked about, which it was not. Deleting a selection has always asked;
    // this one route -- a row's own menu -- went straight through, and since
    // a folder now takes everything inside it, one tap there could remove a
    // tree. There is no undo on a server.
    deleting?.let { entry ->
        OloConfirmDialog(
            title = stringResource(R.string.confirm_delete_one, entry.name),
            detail = stringResource(R.string.confirm_delete_detail),
            confirmLabel = stringResource(R.string.action_delete),
            onDismiss = { deleting = null },
            onConfirm = {
                actions.onDelete(entry)
                deleting = null
            },
        )
    }

    renaming?.let { entry ->
        OloPromptDialog(
            title = R.string.prompt_rename_title,
            titleArg = entry.name,
            detail = R.string.rename_detail,
            label = R.string.prompt_new_name,
            initial = entry.name,
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                actions.onRename(entry, newName)
                renaming = null
            },
        )
    }
}

@Composable
private fun FilterBar(filter: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    OloTextField(
        value = filter,
        onValueChange = onChange,
        label = stringResource(R.string.menu_filter),
        placeholder = stringResource(R.string.filter_hint),
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.filter_clear))
            }
        },
        // Headroom for the floating label, which rides the top border.
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun EntryRow(
    entry: DirectoryEntry,
    selected: Boolean,
    selecting: Boolean,
    isLocal: Boolean,
    actions: EntryActions,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val chip = if (entry.isDirectory) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            )
            .clickable {
                // In selection mode every tap picks rather than navigates, or
                // choosing several files would keep walking into folders.
                when {
                    selecting -> actions.onToggleSelected(entry)
                    entry.isDirectory -> actions.onOpen(entry)
                    else -> actions.onDownload(entry)
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(
                checked = selected,
                onCheckedChange = { actions.onToggleSelected(entry) },
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        // The icon is a second way into selection mode, and the quickest one:
        // picking a folder otherwise means finding "select" in the overflow
        // menu first. The checkbox beside it still shows the state.
        val kind = remember(entry.name, entry.isDirectory) {
            kindOf(entry.name, entry.isDirectory)
        }
        FileTile(
            kind = kind,
            colour = colourFor(kind),
            contentDescription = stringResource(R.string.browse_select, entry.name),
            modifier = Modifier.clickable { actions.onToggleSelected(entry) },
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (entry.isDirectory) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Remembered against the row: formatting a date builds a
            // calendar and a formatted string, and doing that for every
            // visible row on every recomposition is paid for in dropped
            // frames while the list is moving.
            val detail = remember(entry) {
                listOfNotNull(
                    if (entry.isDirectory) null else formatSize(entry.size).ifBlank { null },
                    formatEntryTime(entry).ifBlank { null },
                ).joinToString("  ·  ")
            }
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (!selecting) {
            if (!entry.isDirectory) {
                // The same action, and two different things: on a server it
                // fetches the file, on the phone it opens it. A download
                // arrow beside a file that is already on the phone says the
                // button does something it does not.
                IconButton(onClick = { actions.onDownload(entry) }) {
                    Icon(
                        if (isLocal) Icons.AutoMirrored.Filled.OpenInNew else Icons.Filled.Download,
                        contentDescription = stringResource(
                            if (isLocal) R.string.browse_open else R.string.browse_download,
                            entry.name,
                        ),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // The Box is the anchor. Without it the menu hangs off the row
            // that contains the button, which is the full width of the
            // screen, and a menu opened from the right edge appears at the
            // left one.
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.browse_more, entry.name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // A file has the download button beside it; a folder has
                    // nowhere else to be asked for on its own.
                    if (entry.isDirectory && !isLocal) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browse_download_folder)) },
                            onClick = {
                                menuOpen = false
                                actions.onDownload(entry)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_properties)) },
                        onClick = {
                            menuOpen = false
                            actions.onProperties(entry)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GridTile(
    entry: DirectoryEntry,
    selected: Boolean,
    selecting: Boolean,
    actions: EntryActions,
) {
    val kind = remember(entry.name, entry.isDirectory) { kindOf(entry.name, entry.isDirectory) }
    val chip = colourFor(kind)
    Column(
        modifier = Modifier
            .padding(4.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(12.dp),
            )
            .clickable {
                when {
                    selecting -> actions.onToggleSelected(entry)
                    entry.isDirectory -> actions.onOpen(entry)
                    else -> actions.onDownload(entry)
                }
            }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // A tile has no room for a checkbox beside it, so the icon carries the
        // state as well as the tap: selected, it becomes a filled check.
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(chip)
                .clickable { actions.onToggleSelected(entry) },
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = stringResource(R.string.browse_select, entry.name),
                    tint = Color.White,
                )
            } else {
                Icon(
                    painter = painterResource(kind.glyph),
                    contentDescription = stringResource(R.string.browse_select, entry.name),
                    // Already white, and part of it white at reduced alpha,
                    // which is what keeps a page distinct from its lines.
                    tint = Color.Unspecified,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Text(
            entry.name,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!entry.isDirectory) {
            Text(
                formatSize(entry.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


