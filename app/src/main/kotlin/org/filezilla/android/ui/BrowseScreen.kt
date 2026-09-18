package org.filezilla.android.ui

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
import androidx.compose.ui.unit.dp
import org.filezilla.ftp.listing.DirectoryEntry

@Composable
fun BrowseScreen(
    state: BrowseState,
    downloadFolderName: String?,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (DirectoryEntry) -> Unit,
    onDownload: (DirectoryEntry) -> Unit,
    onDelete: (DirectoryEntry) -> Unit,
    onRename: (DirectoryEntry, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.site == null) {
        EmptyState(
            title = "Not connected",
            detail = "Pick a server on the Sites tab to browse it.",
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up one directory")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(state.path, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    downloadFolderName?.let { "Downloads go to $it" }
                        ?: "No download folder chosen yet",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh listing")
            }
        }

        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        state.error?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
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
            title = "Rename ${entry.name}",
            label = "New name",
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (entry.isDirectory) onOpen() else onDownload() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            val detail = listOfNotNull(
                if (entry.isDirectory) null else formatSize(entry.size).ifBlank { null },
                formatEntryTime(entry).ifBlank { null },
                entry.permissions,
            ).joinToString("  ·  ")
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
        if (!entry.isDirectory) {
            IconButton(onClick = onDownload) {
                Icon(Icons.Filled.Download, contentDescription = "Download ${entry.name}")
            }
        }
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More actions for ${entry.name}")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    menuOpen = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
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
                Text("OK")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
