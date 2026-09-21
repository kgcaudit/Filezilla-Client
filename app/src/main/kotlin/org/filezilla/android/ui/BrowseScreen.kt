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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.filezilla.android.files.FilePath
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
    /**
     * Opens a file on the phone with an app chosen now.
     *
     * Not the same act as tapping it: a tap honours whatever "always open
     * .srt this way" was ticked, and this deliberately ignores it. Without
     * it, a choice made once could only be undone by finding the default
     * apps list, clearing the entry, and coming back -- which is a long way
     * round for "not that one, the other one".
     */
    val onOpenWith: (DirectoryEntry) -> Unit = {},
    /**
     * Looks at a file: opens it on the phone, fetches and opens it on a
     * server. What the download button does not do.
     */
    val onOpenFile: (DirectoryEntry) -> Unit = {},
    /**
     * Changes a row's permissions, which only a server has.
     *
     * The phone's files are reached through the storage framework, which
     * hands out documents rather than a filesystem: there are no bits to
     * set, so the item is absent rather than present and refused.
     */
    val onChangeMode: (DirectoryEntry) -> Unit = {},
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
    /** Walks below this folder for the filter's text. Asked for, never automatic. */
    onSearchDeeper: () -> Unit,
    onStopWalking: () -> Unit,
    onCloseSearch: () -> Unit,
    onOpenHit: (SearchHit) -> Unit,
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

        // Results stand in for the listing rather than joining it. They come
        // from other folders, and a selection spanning several folders has
        // no single place to paste into -- so this is a way *to* somewhere,
        // and every ordinary action waits until you are there.
        state.search?.let { search ->
            SearchResults(
                search = search,
                onOpen = onOpenHit,
                onStopWalking = onStopWalking,
                onClose = onCloseSearch,
            )
            return@Column
        }

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
                rows.isEmpty() && state.filter.isNotBlank() -> Column {
                    EmptyState(
                        title = stringResource(R.string.filter_none, state.filter),
                        detail = stringResource(R.string.filter_hint),
                        icon = R.drawable.ic_tile_search,
                        modifier = Modifier.weight(1f),
                    )
                    // Nothing here is exactly when the rest of the tree is
                    // worth offering, so the offer is not buried under an
                    // empty state that looks like the end of the matter.
                    HorizontalDivider()
                    SearchDeeperRow(shown = 0, onSearch = onSearchDeeper)
                }

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
                                folder = state.path,
                                actions = actions,
                                onRename = { renaming = entry },
                                onDelete = { deleting = entry },
                            )
                            HorizontalDivider()
                        }
                        // After the matches, where it reads as "and also"
                        // rather than as a second search box. Only while a
                        // filter is on: with none there is nothing to look
                        // for, and the row would be an invitation to walk
                        // the whole tree for nothing.
                        if (state.filter.isNotBlank()) {
                            item {
                                SearchDeeperRow(shown = rows.size, onSearch = onSearchDeeper)
                                HorizontalDivider()
                            }
                        }
                        // What the folder adds up to. At the foot rather
                        // than in the header, which is one line on purpose
                        // -- a permanent second line costs every screen,
                        // and this is a thing looked at once on arriving
                        // or after scrolling to the end.
                        item {
                            val summary = remember(rows) { summarise(rows) }
                            Text(
                                stringResource(
                                    R.string.folder_summary,
                                    summary.folders,
                                    summary.files,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            )
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
            confirmLabel = R.string.action_change,
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
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.filter_clear))
            }
        },
        // Headroom for the floating label, which rides the top border.
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 4.dp),
    )
}

/**
 * Whether a row carries a button of its own beside the menu.
 *
 * It carried one on both sides, and on both sides it called exactly what
 * tapping the row calls -- a second control for the same act, inside the
 * control that already did it. The user asked what it was for and whether
 * it marked the files that had an app to open them; it never meant that,
 * and on the phone it meant nothing at all.
 *
 * It stays on a server, where it is the one thing that says a file row can
 * be fetched: "tap a file to download it" is not something a listing
 * teaches by itself, and a file's own menu offers no download either. On
 * the phone, tapping a file to open it is what tapping a file means
 * everywhere, so the button bought nothing and spent about fifty points of
 * the width the file names were being truncated for.
 */
fun showsFetchButton(isLocal: Boolean, isDirectory: Boolean): Boolean =
    !isLocal && !isDirectory

@Composable
private fun EntryRow(
    entry: DirectoryEntry,
    selected: Boolean,
    selecting: Boolean,
    isLocal: Boolean,
    /** The folder this row is in, so a folder row can count what it holds. */
    folder: String,
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
                    // A tap is "let me see this", and the download button
                    // beside it is "keep a copy". They used to be the same
                    // call, so reading a text file on a server began by
                    // choosing a folder to save it into.
                    else -> actions.onOpenFile(entry)
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
            // What a folder holds, which is the one thing a folder row is
            // asked and the one thing it did not say. Off the main thread
            // and only for rows on screen: LazyColumn asks for a row when it
            // draws it, so a folder of a thousand subfolders counts the
            // handful that are visible rather than all of them.
            //
            // The phone only. On a server this is a round trip per row --
            // see FolderCount.
            val counted = if (isLocal && entry.isDirectory && !entry.isLink) {
                produceState<Int?>(initialValue = null, entry.name, folder) {
                    value = withContext(Dispatchers.IO) {
                        FolderCount.of(FilePath.child(folder, entry.name))
                    }
                }.value
            } else {
                null
            }
            val held = counted?.let {
                if (it == 0) {
                    stringResource(R.string.folder_empty)
                } else {
                    pluralStringResource(R.plurals.folder_items, it, it)
                }
            }
            val when_ = remember(entry) { formatEntryTime(entry).ifBlank { null } }
            val size = remember(entry) {
                if (entry.isDirectory) null else formatSize(entry.size).ifBlank { null }
            }
            val detail = listOfNotNull(size, held, when_).joinToString("  ·  ")
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
            if (showsFetchButton(isLocal = isLocal, isDirectory = entry.isDirectory)) {
                IconButton(onClick = { actions.onDownload(entry) }) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = stringResource(R.string.browse_download, entry.name),
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
                    // Only on the phone, and only for a file: a row on a
                    // server is not a file this device can hand to anything.
                    if (isLocal && !entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browse_open_with)) },
                            onClick = {
                                menuOpen = false
                                actions.onOpenWith(entry)
                            },
                        )
                    }
                    // A file on a server has the download button beside it;
                    // a folder has nowhere else to be asked for on its own.
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
                    // Next to properties on purpose: the permissions are
                    // what properties is usually opened to read, and being
                    // able to read them and not change them is how somebody
                    // ends up at a computer to fix a transfer that failed.
                    if (!isLocal) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_change_mode)) },
                            onClick = {
                                menuOpen = false
                                actions.onChangeMode(entry)
                            },
                        )
                    }
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
                    else -> actions.onOpenFile(entry)
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


