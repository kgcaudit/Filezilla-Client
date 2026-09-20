package org.filezilla.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.ftp.listing.DirectoryEntry

/**
 * The chrome above one pane: what it is, where it is, and the way out of both.
 *
 * Per pane rather than in the app's own top bar, and that is the whole point
 * of the rearrangement. With two panes on screen a single bar has to act on
 * one of them, and which one is a fact the user cannot see -- so sorting,
 * selecting and refreshing all landed on "whichever was touched last". A bar
 * belonging to a pane acts on that pane, visibly.
 */
@Composable
fun PaneHeader(
    id: PaneId,
    model: MainViewModel,
    options: BrowseOptions,
    rows: List<DirectoryEntry>,
    downloadFolderName: String?,
    onNewDirectory: () -> Unit,
    onUpload: () -> Unit,
    onChooseFolder: () -> Unit,
    onGrant: () -> Unit,
    onOpenScreen: (Screen) -> Unit,
) {
    val state = model.pane(id)
    var storageOpen by remember { mutableStateOf(false) }
    var viewOptionsOpen by remember { mutableStateOf(false) }

    /**
     * Every control here acts on the active pane, so pressing one has to make
     * this the active pane first. Pressing a button in a pane's own header is
     * touching that pane, and on a phone it already is the one in front.
     */
    fun here(action: () -> Unit): () -> Unit = {
        model.focusPane(id)
        action()
    }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = here { storageOpen = true }) {
                    Icon(
                        Icons.Filled.Menu,
                        contentDescription = stringResource(R.string.pane_storage),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        paneTitle(state.source),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        paneSummary(rows, downloadFolderName, state.isLocal),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.source !is PaneSource.Empty) {
                    IconButton(onClick = here(model::toggleFilter)) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.menu_filter),
                            tint = if (state.filterOpen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    BrowseOverflow(
                        options = options,
                        filterOpen = state.filterOpen,
                        onSelectMode = here(model::toggleSelectionMode),
                        onSelectAll = here(model::selectAll),
                        onToggleFilter = here(model::toggleFilter),
                        onViewOptions = here { viewOptionsOpen = true },
                        onChooseFolder = here(onChooseFolder),
                        onRefresh = here { model.open(id) },
                        onOptions = model::applyOptions,
                        // Making a folder and sending a file up belong to the
                        // server pane alone: on the phone the round button
                        // makes folders, and "up" is what the other pane is.
                        onNewDirectory = if (state.site != null) here(onNewDirectory) else null,
                        onUpload = if (state.site != null) here(onUpload) else null,
                    )
                }
            }

            if (state.path.isNotEmpty()) {
                BreadcrumbBar(
                    crumbs = model.breadcrumbsFor(id),
                    onOpen = { path -> model.focusPane(id); model.openPath(id, path) },
                )
            }
        }
    }

    if (storageOpen) {
        StorageSheet(
            id = id,
            model = model,
            onGrant = onGrant,
            onOpenScreen = onOpenScreen,
            onDismiss = { storageOpen = false },
        )
    }

    if (viewOptionsOpen) {
        ViewOptionsDialog(
            options = options,
            onDismiss = { viewOptionsOpen = false },
            onApply = model::applyOptions,
        )
    }
}

/** What this pane is looking at, in the words the user would use for it. */
@Composable
private fun paneTitle(source: PaneSource): String = when (source) {
    PaneSource.Local -> stringResource(R.string.side_local)
    PaneSource.Empty -> stringResource(R.string.pane_no_source)
    is PaneSource.Remote -> source.site.name.ifBlank { source.site.host }
}

/**
 * The line under the title: how much is in this folder, and for a local pane
 * where a download would land -- the two things worth knowing before touching
 * anything. The destination belongs to the phone's side only; saying it under
 * a server's name suggested the server was where downloads went.
 */
@Composable
private fun paneSummary(
    rows: List<DirectoryEntry>,
    downloadFolderName: String?,
    isLocal: Boolean,
): String {
    val folders = rows.count { it.isDirectory }
    val counts = stringResource(R.string.listing_summary, folders, rows.size - folders)
    if (!isLocal) return counts
    val destination = downloadFolderName?.let { stringResource(R.string.browse_destination, it) }
        ?: stringResource(R.string.browse_no_destination)
    return "$counts  ·  $destination"
}

/**
 * The path, as places rather than as a line of text.
 *
 * Scrolled to the end whenever the path changes: the folder you are in is the
 * one you need to see, and a trail that opens at its left-hand end shows the
 * volume and hides everything after it.
 */
@Composable
private fun BreadcrumbBar(crumbs: List<Crumb>, onOpen: (String) -> Unit) {
    val scroll = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Reversed, so a trail too long to fit rests at its end rather
            // than its beginning: the folder you are in is the one you need
            // to see, and the volume it is on is the one you can guess.
            // Done by layout rather than by scrolling to the end after the
            // fact, which depends on the row having been measured first and
            // so shows the wrong end of the first path drawn.
            .horizontalScroll(scroll, reverseScrolling = true)
            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((index, crumb) in crumbs.withIndex()) {
            if (index > 0) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            val last = index == crumbs.lastIndex
            Text(
                crumb.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    // The last crumb is where the pane already is, so tapping
                    // it would re-list for nothing.
                    .clickable(enabled = !last) { onOpen(crumb.path) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/** How full a volume is, as a bar. The figures are the row's subtitle. */
@Composable
fun CapacityBar(usedFraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp)),
    ) {
        LinearProgressIndicator(
            progress = { usedFraction },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            // Neither belongs on a storage bar: the gap and the stop mark
            // read as divisions of the volume rather than decoration of the
            // control.
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}
