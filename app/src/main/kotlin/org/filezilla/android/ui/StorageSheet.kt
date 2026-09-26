package org.filezilla.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.filezilla.android.R
import org.filezilla.android.ui.theme.status
import org.filezilla.android.ui.theme.tiles
import org.filezilla.android.files.StorageRoot

/** Samsung's "My Files" app, where a volume is ejected on a Galaxy device. */
private const val SAMSUNG_MY_FILES = "com.sec.android.app.myfiles"

/**
 * Everywhere one pane could be pointed, in one list.
 *
 * Reached from the pane's own menu button, which is where a file manager puts
 * it. What it replaces was a row of chips above every listing: it grew by one
 * chip per volume, it could not say how full any of them were, and it spent a
 * line of every pane on a choice made once a session.
 *
 * A sheet rather than a drawer slid in from the edge, because on a phone the
 * panes are a pager: an edge drawer and a pager both want the same sideways
 * drag, and whichever wins, the other one stops working.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSheet(
    id: PaneId,
    model: MainViewModel,
    onGrant: () -> Unit,
    onOpenScreen: (Screen) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        StoragePlaces(
            id = id,
            model = model,
            onGrant = onGrant,
            onOpenScreen = onOpenScreen,
            onDismiss = onDismiss,
        )
    }
}

/**
 * The list itself, apart from the sheet that carries it.
 *
 * Separate because a sheet draws in a window of its own, which is invisible
 * to anything that renders the screen -- including the shot harness that is
 * the only way this layout gets looked at before it reaches a phone.
 */
@Composable
fun StoragePlaces(
    id: PaneId,
    model: MainViewModel,
    onGrant: () -> Unit,
    onOpenScreen: (Screen) -> Unit,
    onDismiss: () -> Unit,
) {
    val sites by model.sites.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ejectHint = stringResource(R.string.storage_eject_hint)
    // Android gives an ordinary app no way to unmount a volume itself -- that
    // permission is the system's alone. On a Samsung phone or tablet the "My
    // Files" app is where a volume is ejected, so "eject" opens it. Where it is
    // not present -- another maker's device -- the system storage settings are
    // opened instead, the other place a volume can be taken out safely, and a
    // note says so.
    fun eject() {
        val myFiles = context.packageManager.getLaunchIntentForPackage(SAMSUNG_MY_FILES)
        if (myFiles != null &&
            runCatching {
                context.startActivity(myFiles.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        ) {
            return
        }
        val opened = runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (!opened) {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        android.widget.Toast.makeText(context, ejectHint, android.widget.Toast.LENGTH_LONG).show()
    }

    Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            SectionLabel(stringResource(R.string.storage_section))

            if (!model.storageGranted) {
                // A row rather than a sentence: saying the app cannot read
                // storage and leaving it there is a dead end, and this list
                // is exactly where someone has come looking for their files.
                PlaceRow(
                    glyph = R.drawable.ic_tile_locked,
                    colour = MaterialTheme.status.paused,
                    title = stringResource(R.string.storage_not_granted),
                    subtitle = stringResource(R.string.storage_grant_action),
                    onClick = {
                        onGrant()
                        onDismiss()
                    },
                )
            }

            for (root in model.storageRoots()) {
                // Remembered against the path: reading how full a volume is
                // is a filesystem call, and this list is rebuilt whenever the
                // sheet animates.
                val capacity = remember(root.path) { model.capacityOf(root.path) }
                PlaceRow(
                    glyph = when (root.kind) {
                        StorageRoot.Kind.INTERNAL -> R.drawable.ic_tile_phone
                        StorageRoot.Kind.SD_CARD -> R.drawable.ic_tile_sdcard
                        StorageRoot.Kind.SHORTCUT -> R.drawable.ic_tile_folder
                    },
                    colour = when (root.kind) {
                        StorageRoot.Kind.SHORTCUT -> MaterialTheme.tiles.folder
                        else -> MaterialTheme.colorScheme.primary
                    },
                    title = root.label,
                    // Not the path. It is long, it is the same for every row
                    // down to the last segment, and ellipsised from the right
                    // it hides the only part that differs. What is worth
                    // knowing about a volume is how much of it is left.
                    subtitle = capacity?.let {
                        stringResource(
                            R.string.storage_free_of,
                            formatSize(it.freeBytes),
                            formatSize(it.totalBytes),
                        )
                    },
                    onClick = {
                        model.showLocalAt(id, root.path)
                        onDismiss()
                    },
                    // Only a removable volume can be taken out; the phone's own
                    // storage and the Downloads shortcut have nothing to eject.
                    onEject = if (root.kind == StorageRoot.Kind.SD_CARD) {
                        { eject() }
                    } else {
                        null
                    },
                ) {
                    capacity?.let {
                        CapacityBar(
                            usedFraction = it.usedFraction,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionLabel(stringResource(R.string.storage_servers_section))

            if (sites.isEmpty()) {
                Text(
                    stringResource(R.string.sites_empty_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            for (site in sites) {
                PlaceRow(
                    glyph = R.drawable.ic_tile_server,
                    colour = MaterialTheme.colorScheme.secondary,
                    title = site.name.ifBlank { site.host },
                    subtitle = "${site.user}@${site.host}",
                    onClick = {
                        model.showSite(id, site)
                        onDismiss()
                    },
                )
            }

            PlaceRow(
                glyph = R.drawable.ic_tile_server,
                colour = MaterialTheme.colorScheme.secondary,
                title = stringResource(R.string.storage_manage_servers),
                subtitle = null,
                onClick = {
                    onOpenScreen(Screen.SITES)
                    onDismiss()
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // The two screens that used to be tabs along the bottom. They are
            // places you visit, not places you live, and a permanent seat for
            // each cost 80dp of every screen in the app.
            PlaceRow(
                // Its own mark, not a folder's: the folder tile is what the
                // row above this one means, and two rows carrying the same
                // picture is two rows nobody reads.
                glyph = R.drawable.ic_tile_transfers,
                colour = MaterialTheme.tiles.code,
                title = stringResource(R.string.title_queue),
                subtitle = null,
                onClick = {
                    onOpenScreen(Screen.QUEUE)
                    onDismiss()
                },
            )
            PlaceRow(
                glyph = R.drawable.ic_tile_document,
                colour = MaterialTheme.tiles.document,
                title = stringResource(R.string.title_log),
                subtitle = null,
                onClick = {
                    onOpenScreen(Screen.LOG)
                    onDismiss()
                },
            )
            PlaceRow(
                glyph = R.drawable.ic_tile_recents,
                colour = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.title_recents),
                subtitle = stringResource(R.string.recents_open),
                onClick = {
                    onOpenScreen(Screen.RECENTS)
                    onDismiss()
                },
            )
            PlaceRow(
                glyph = R.drawable.ic_tile_trash,
                colour = MaterialTheme.colorScheme.error,
                title = stringResource(R.string.title_trash),
                subtitle = stringResource(R.string.trash_open),
                onClick = {
                    onOpenScreen(Screen.TRASH)
                    onDismiss()
                },
            )

            TextButton(
                onClick = {
                    model.showEmpty(id)
                    onDismiss()
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(stringResource(R.string.storage_clear_pane)) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** One place a pane can be sent to, with room underneath for what it is. */
@Composable
private fun PlaceRow(
    @DrawableRes glyph: Int,
    colour: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    onEject: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TileIcon(glyph, colour, contentDescription = null, size = 38.dp, cornerRadius = 11.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            extra()
        }
        // The eject button sits at the row's end, its own tap target, so
        // reaching for it does not open the volume the row otherwise does.
        onEject?.let {
            IconButton(onClick = it) {
                Icon(
                    Icons.Filled.Eject,
                    contentDescription = stringResource(R.string.storage_eject),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
