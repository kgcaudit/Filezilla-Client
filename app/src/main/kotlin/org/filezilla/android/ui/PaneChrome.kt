package org.filezilla.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.android.files.FileAssociations

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
    onNewDirectory: () -> Unit,
    onUpload: () -> Unit,
    onGrant: () -> Unit,
    onOpenScreen: (Screen) -> Unit,
) {
    val state = model.pane(id)
    var storageOpen by remember { mutableStateOf(false) }
    var viewOptionsOpen by remember { mutableStateOf(false) }
    var associationsOpen by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

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
        // One row, not two. It was a title line above a path line, and the
        // title was the third copy of a word already on the tab above it and
        // in the first crumb of the path below it -- so the pane spent a
        // whole line of the screen repeating itself.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // What this pane is pointed at, not a hamburger. A hamburger
            // means the app's own navigation drawer, and this opens neither
            // the app nor a drawer: it chooses whether the pane shows the
            // phone or a server. Drawn as whichever it is showing, so the
            // button says the current state and what pressing it changes at
            // the same time -- and the little chevron says it is a choice
            // rather than a label.
            IconButton(onClick = here { storageOpen = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(
                            when (state.source) {
                                is PaneSource.Remote -> R.drawable.ic_tile_server
                                is PaneSource.Local -> R.drawable.ic_tile_phone
                                PaneSource.Empty -> R.drawable.ic_tile_folder
                            },
                        ),
                        contentDescription = stringResource(R.string.pane_storage),
                        // Unspecified, or Material flattens the artwork to
                        // one colour and the two-tone tiles stop reading.
                        tint = Color.Unspecified,
                        modifier = Modifier.size(22.dp),
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            if (state.path.isEmpty()) {
                // Nowhere yet, so there is no path to show and the pane has
                // to say what it is some other way.
                Text(
                    paneTitle(state.source),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            } else {
                BreadcrumbBar(
                    crumbs = model.breadcrumbsFor(id),
                    onOpen = { path -> model.focusPane(id); model.openPath(id, path) },
                    modifier = Modifier.weight(1f),
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
                    filterOpen = state.filterOpen,
                    onSelectMode = here(model::toggleSelectionMode),
                    onSelectAll = here(model::selectAll),
                    onToggleFilter = here(model::toggleFilter),
                    onViewOptions = here { viewOptionsOpen = true },
                    onAssociations = here { associationsOpen = true },
                    onRefresh = here { model.open(id) },
                    // Making a folder and sending a file up belong to the
                    // server pane alone: on the phone the round button
                    // makes folders, and "up" is what the other pane is.
                    onNewDirectory = if (state.site != null) here(onNewDirectory) else null,
                    onUpload = if (state.site != null) here(onUpload) else null,
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

    if (associationsOpen) {
        val associations = remember { FileAssociations(context) }
        // Read when the dialog opens and again after each removal, so a row
        // taken away actually leaves the list.
        var shown by remember { mutableStateOf(associations.all()) }
        FileAssociationsDialog(
            associations = shown.map { (extension, component) ->
                extension to labelFor(context, component)
            },
            onForget = { extension ->
                associations.forget(extension)
                shown = associations.all()
            },
            onDismiss = { associationsOpen = false },
        )
    }

    if (viewOptionsOpen) {
        // The pane's own settings, which are the shared ones unless this
        // folder has been given some of its own.
        val onlyHere = model.hasOwnOptions(id)
        ViewOptionsDialog(
            options = model.optionsFor(id),
            onlyHere = onlyHere,
            // Hidden where there is no folder to pin to: a pane that has
            // not been anywhere yet has no path to remember settings
            // against, and a box that cannot be honoured is worse than none.
            onOnlyHere = if (state.path.isEmpty()) {
                null
            } else {
                { only -> model.applyOptions(id, model.optionsFor(id), only) }
            },
            onDismiss = { viewOptionsOpen = false },
            onApply = { next -> model.applyOptions(id, next, onlyHere) },
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
 * The path, as places rather than as a line of text.
 *
 * Scrolled to the end whenever the path changes: the folder you are in is the
 * one you need to see, and a trail that opens at its left-hand end shows the
 * volume and hides everything after it.
 */
@Composable
private fun BreadcrumbBar(
    crumbs: List<Crumb>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()

    Row(
        modifier = modifier
            // Reversed, so a trail too long to fit rests at its end rather
            // than its beginning: the folder you are in is the one you need
            // to see, and the volume it is on is the one you can guess.
            // Done by layout rather than by scrolling to the end after the
            // fact, which depends on the row having been measured first and
            // so shows the wrong end of the first path drawn.
            .horizontalScroll(scroll, reverseScrolling = true)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((index, crumb) in crumbs.withIndex()) {
            if (index > 0) {
                // Punctuation, not a control. The same chevron is the
                // transfer strip's "open this", and a glyph that is a
                // button in one place should not be furniture in another.
                // Lighter and smaller, so the crumbs read as the path and
                // this reads as the gap between them.
                Text(
                    "/",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outlineVariant,
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


/**
 * The name a component's app goes by on the home screen.
 *
 * Read from the package manager rather than shown as a package name: the
 * user chose "MX Player", and telling them afterwards that .mkv is set to
 * `com.mxtech.videoplayer.ad` is answering a different question. An app
 * that has been uninstalled since has no label, and its package name is
 * then the most honest thing left to show.
 */
private fun labelFor(context: android.content.Context, component: android.content.ComponentName): String =
    runCatching {
        val manager = context.packageManager
        manager.getApplicationLabel(
            manager.getApplicationInfo(component.packageName, 0),
        ).toString()
    }.getOrDefault(component.packageName)
