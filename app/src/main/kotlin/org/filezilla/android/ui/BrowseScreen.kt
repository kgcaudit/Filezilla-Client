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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    downloadFolderName: String?,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onOpenLog: () -> Unit,
    onFilterChange: (String) -> Unit,
    onCloseFilter: () -> Unit,
    actions: EntryActions,
    modifier: Modifier = Modifier,
) {
    if (state.site == null) {
        EmptyState(
            title = stringResource(R.string.browse_not_connected_title),
            detail = stringResource(R.string.browse_not_connected_detail),
            modifier = modifier,
        )
        return
    }

    var renaming by remember { mutableStateOf<DirectoryEntry?>(null) }

    Column(modifier = modifier) {
        PathBar(state, rows, downloadFolderName, onUp)

        if (state.filterOpen) {
            FilterBar(state.filter, onFilterChange, onCloseFilter)
        }

        state.error?.let { failure ->
            ErrorPanel(failure = failure, onRetry = onRefresh, onOpenLog = onOpenLog)
        }

        HorizontalDivider()

        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                // A filter that matches nothing must say so. An empty list
                // otherwise looks like an empty directory, and the user has
                // no way to tell which it is.
                rows.isEmpty() && state.filter.isNotBlank() -> EmptyState(
                    title = stringResource(R.string.filter_none, state.filter),
                    detail = stringResource(R.string.filter_hint),
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

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(rows, key = { it.name }) { entry ->
                        EntryRow(
                            entry = entry,
                            selected = entry.name in state.selection,
                            selecting = state.selecting,
                            actions = actions,
                            onRename = { renaming = entry },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    renaming?.let { entry ->
        TextPromptDialog(
            title = stringResource(R.string.prompt_rename_title, entry.name),
            label = stringResource(R.string.prompt_new_name),
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
private fun PathBar(
    state: BrowseState,
    rows: List<DirectoryEntry>,
    downloadFolderName: String?,
    onUp: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onUp, enabled = state.path != "/") {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.browse_up))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                state.path,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // How many of each, and where a download would land: the two
            // things worth knowing about a directory before touching it.
            val folders = rows.count { it.isDirectory }
            Text(
                stringResource(R.string.listing_summary, folders, rows.size - folders) +
                    "  ·  " + (
                    downloadFolderName?.let { stringResource(R.string.browse_destination, it) }
                        ?: stringResource(R.string.browse_no_destination)
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FilterBar(filter: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    OutlinedTextField(
        value = filter,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.menu_filter)) },
        placeholder = { Text(stringResource(R.string.filter_hint)) },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.filter_clear))
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun EntryRow(
    entry: DirectoryEntry,
    selected: Boolean,
    selecting: Boolean,
    actions: EntryActions,
    onRename: () -> Unit,
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
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(chip.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (entry.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = chip,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (entry.isDirectory) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOfNotNull(
                if (entry.isDirectory) null else formatSize(entry.size).ifBlank { null },
                formatEntryTime(entry).ifBlank { null },
            ).joinToString("  ·  ")
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
                IconButton(onClick = { actions.onDownload(entry) }) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = stringResource(R.string.browse_download, entry.name),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.browse_more, entry.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
                        actions.onDelete(entry)
                    },
                )
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
    val chip = if (entry.isDirectory) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondary
    }
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
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(chip.copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (entry.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = chip,
            )
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

@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
