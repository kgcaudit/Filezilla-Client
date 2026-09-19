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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
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
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState(initialPage = pageOf(model.activePane)) { PaneId.entries.size }

    // The pager is the one record of which pane is in front. Tracking it
    // separately as well would give two answers that drift apart, and the
    // toolbar would start acting on the pane the user cannot see.
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { model.showPane(paneAt(it)) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        PaneTabs(current = pager.currentPage, model = model)

        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
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
            )
        }
    }
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
) {
    val state = model.pane(id)

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
                rows = model.visibleEntries(id),
                options = options,
                downloadFolderName = downloadFolderName,
                onUp = { model.up(id) },
                onRefresh = { model.open(id) },
                onOpenLog = onOpenLog,
                onFilterChange = model::setFilter,
                onCloseFilter = model::toggleFilter,
                actions = EntryActions(
                    onOpen = { model.openChild(id, it.name) },
                    onDownload = onDownload,
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
