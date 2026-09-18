package org.filezilla.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.filezilla.android.R
import androidx.compose.ui.unit.dp
import org.filezilla.ftp.listing.DirectoryEntry

@Composable
fun BrowseScreen(
    state: BrowseState,
    downloadFolderName: String?,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onOpenLog: () -> Unit,
    onOpen: (DirectoryEntry) -> Unit,
    onDownload: (DirectoryEntry) -> Unit,
    onDelete: (DirectoryEntry) -> Unit,
    onRename: (DirectoryEntry, String) -> Unit,
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onUp, enabled = state.path != "/") {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.browse_up))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(state.path, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    downloadFolderName?.let { stringResource(R.string.browse_destination, it) }
                        ?: stringResource(R.string.browse_no_destination),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.browse_refresh))
            }
        }

        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        state.error?.let { failure ->
            ErrorPanel(failure = failure, onRetry = onRefresh, onOpenLog = onOpenLog)
        }

        HorizontalDivider()

        LazyColumn {
            items(state.entries, key = { it.name }) { entry ->
                EntryRow(
                    entry = entry,
                    onOpen = { onOpen(entry) },
                    onDownload = { onDownload(entry) },
                    onDelete = { onDelete(entry) },
                    onRename = { renaming = entry },
                )
                HorizontalDivider()
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
                onRename(entry, newName)
                renaming = null
            },
        )
    }
}

@Composable
private fun EntryRow(
    entry: DirectoryEntry,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Folders and files get different colours, which is the single thing that
    // makes a long listing scannable: the eye sorts by colour before it reads.
    val chip = if (entry.isDirectory) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (entry.isDirectory) onOpen() else onDownload() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        if (!entry.isDirectory) {
            IconButton(onClick = onDownload) {
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

@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
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
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
