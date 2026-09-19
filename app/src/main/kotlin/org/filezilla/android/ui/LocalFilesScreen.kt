package org.filezilla.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.android.files.AccessRoute
import org.filezilla.android.files.FilePath
import org.filezilla.android.files.StorageRoot
import org.filezilla.ftp.listing.DirectoryEntry

/**
 * The device's own storage, read only.
 *
 * The first half of the left pane. It deliberately shares [FileRow] and
 * [BrowseListing] with the remote side rather than growing its own: two
 * listings that merely look alike are two listings that drift, which is how
 * one download path came to skip a check the others made.
 */
@Composable
fun LocalFilesScreen(
    path: String,
    rows: List<DirectoryEntry>,
    roots: List<StorageRoot>,
    granted: Boolean,
    route: AccessRoute,
    loading: Boolean,
    error: String?,
    canGoUp: Boolean,
    onOpen: (DirectoryEntry) -> Unit,
    onUp: () -> Unit,
    onOpenPath: (String) -> Unit,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!granted) {
        StorageGate(route = route, onGrant = onGrant, modifier = modifier)
        return
    }

    Column(modifier = modifier) {
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        RootChips(roots = roots, current = path, onOpenPath = onOpenPath)

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onUp, enabled = canGoUp) {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = stringResource(R.string.storage_up),
                    tint = if (canGoUp) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                )
            }
            Text(
                // The folder, not the whole path: the path is long and the
                // part that matters is the end of it.
                FilePath.name(path),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (rows.isEmpty() && !loading) {
            EmptyState(
                title = stringResource(R.string.storage_empty_title),
                detail = stringResource(R.string.storage_empty_detail),
                icon = R.drawable.ic_flat_folder,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(rows, key = { it.name }) { entry ->
                LocalRow(entry = entry, onOpen = { onOpen(entry) })
            }
        }
    }
}

/** The volumes and shortcuts this pane can jump to. */
@Composable
private fun RootChips(
    roots: List<StorageRoot>,
    current: String,
    onOpenPath: (String) -> Unit,
) {
    if (roots.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (root in roots) {
            AssistChip(
                onClick = { onOpenPath(root.path) },
                label = { Text(root.label) },
                leadingIcon = {
                    FlatIcon(
                        icon = iconForRoot(root.kind),
                        contentDescription = null,
                        chipSize = 22.dp,
                        cornerRadius = 6.dp,
                    )
                },
            )
        }
    }
}

private fun iconForRoot(kind: StorageRoot.Kind): Int = when (kind) {
    StorageRoot.Kind.INTERNAL -> R.drawable.ic_flat_server
    StorageRoot.Kind.SD_CARD -> R.drawable.ic_flat_server
    StorageRoot.Kind.SHORTCUT -> R.drawable.ic_flat_folder
}

@Composable
private fun LocalRow(entry: DirectoryEntry, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Only a folder opens for now; this pane is read only until the
            // file operations land.
            .clickable(enabled = entry.isDirectory) { onOpen() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlatIcon(
            icon = if (entry.isDirectory) R.drawable.ic_flat_folder else R.drawable.ic_flat_file,
            contentDescription = null,
            chipSize = 40.dp,
            cornerRadius = 10.dp,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                localSubtitle(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun localSubtitle(entry: DirectoryEntry): String {
    val date = entry.time?.epochMillis?.let { formatTimestamp(it) }.orEmpty()
    if (entry.isDirectory) return date
    return listOf(formatSize(entry.size), date).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * What stands in for the pane while the app cannot read storage.
 *
 * Says what it wants and why before sending anyone to Settings, because on
 * Android 11 and up this is a toggle the user has to find themselves -- there
 * is no dialog to fall back on, and no second chance to explain.
 */
@Composable
private fun StorageGate(route: AccessRoute, onGrant: () -> Unit, modifier: Modifier = Modifier) {
    val unavailable = route == AccessRoute.UNAVAILABLE
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FlatIcon(
            icon = R.drawable.ic_flat_folder,
            contentDescription = null,
            chipSize = 64.dp,
            cornerRadius = 18.dp,
            modifier = Modifier.size(64.dp),
        )
        Text(
            stringResource(
                if (unavailable) R.string.storage_unavailable_title else R.string.storage_permission_title,
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(
                if (unavailable) R.string.storage_unavailable_detail else R.string.storage_permission_detail,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // No button on Android 10: there is nowhere to send them, and a
        // button that leads nowhere is worse than none.
        if (!unavailable) {
            Button(onClick = onGrant) {
                Text(stringResource(R.string.storage_permission_action))
            }
        }
    }
}

/**
 * Which side the file tab is showing.
 *
 * A switch now and a pair of panes later. It is here rather than in the app
 * bar because it belongs to the content: when the two panes arrive this
 * becomes each pane's header, naming what that pane is looking at.
 */
@Composable
fun SideSwitch(
    side: org.filezilla.android.ui.FileSide,
    remoteLabel: String?,
    onSelect: (org.filezilla.android.ui.FileSide) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.FilterChip(
            selected = side == org.filezilla.android.ui.FileSide.LOCAL,
            onClick = { onSelect(org.filezilla.android.ui.FileSide.LOCAL) },
            label = { Text(stringResource(R.string.side_local)) },
        )
        androidx.compose.material3.FilterChip(
            selected = side == org.filezilla.android.ui.FileSide.REMOTE,
            onClick = { onSelect(org.filezilla.android.ui.FileSide.REMOTE) },
            // The server's own name once there is one, so the chip says which
            // server rather than just "server".
            label = { Text(remoteLabel?.takeIf { it.isNotBlank() } ?: stringResource(R.string.side_remote)) },
        )
    }
}
