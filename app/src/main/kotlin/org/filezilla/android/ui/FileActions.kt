package org.filezilla.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.filezilla.android.R

/** What the speed dial offers. */
enum class NewThing { FOLDER, FILE, SERVER }

/**
 * The round button and what unfolds from it.
 *
 * Collapsed it is one button; open it is a short list with words beside the
 * icons, because an icon alone cannot distinguish "new folder" from "new
 * file" at a glance and this is not a menu anyone uses often enough to learn.
 */
@Composable
fun NewThingFab(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPick: (NewThing) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(horizontalAlignment = Alignment.End, modifier = modifier) {
        AnimatedVisibility(visible = expanded) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                DialItem(
                    R.drawable.ic_menu_new_folder,
                    R.string.fab_new_folder,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    onExpandedChange(false)
                    onPick(NewThing.FOLDER)
                }
                DialItem(
                    R.drawable.ic_action_new_file,
                    R.string.fab_new_file,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    onExpandedChange(false)
                    onPick(NewThing.FILE)
                }
                // The app's own server artwork, which is what a server
                // looks like everywhere else here -- the empty state, the
                // pane header, the storage sheet. Icons.Storage is a
                // database cylinder, and it was the only place in the app
                // where a server was drawn as one.
                DialItem(R.drawable.ic_tile_server, R.string.fab_new_server) {
                    onExpandedChange(false)
                    onPick(NewThing.SERVER)
                }
            }
        }
        FloatingActionButton(onClick = { onExpandedChange(!expanded) }) {
            Icon(
                if (expanded) Icons.Filled.Close else Icons.Filled.Add,
                contentDescription = stringResource(
                    if (expanded) R.string.fab_close else R.string.fab_actions,
                ),
            )
        }
    }
}

/**
 * A speed-dial entry drawn from a drawable.
 *
 * The OLO control glyphs (new folder, new file) are single-ink and tint to
 * the button's content colour; the app's own two-tone artwork (the server
 * tile) must not be tinted, or it flattens to one colour -- so that one
 * passes [Color.Unspecified].
 */
@Composable
private fun DialItem(
    @androidx.annotation.DrawableRes icon: Int,
    label: Int,
    tint: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        icon = {
            Icon(
                painter = androidx.compose.ui.res.painterResource(icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        },
        text = { Text(stringResource(label)) },
    )
}

/**
 * The bar that appears along the bottom once rows are picked.
 *
 * Buttons rather than a menu, because these are what selection is *for*: a
 * menu would put every one of them one tap further away than the thing the
 * user already decided to do.
 */
@Composable
fun SelectionBar(
    count: Int,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onClear: () -> Unit,
    /** Rename takes exactly one row; with several it means nothing. */
    canRename: Boolean,
    /** On a server pane, fetching the picked rows straight to the phone. */
    onDownload: (() -> Unit)? = null,
    /**
     * On a phone pane, handing the picked files to another app.
     *
     * Null on a server pane: those files are not on this phone, and sharing
     * one would mean downloading it first -- a transfer the user should
     * start knowingly, not discover because a share sheet hung.
     */
    onShare: (() -> Unit)? = null,
    /** Folders cannot be handed over, so a selection of only folders cannot. */
    canShare: Boolean = false,
    /**
     * On a phone pane, packing the picked rows into a zip beside them.
     *
     * Null on a server pane. Compressing there would mean fetching every
     * picked file, zipping it here and sending it back -- which is a
     * transfer, several of them, and not what a button on a selection
     * bar should quietly start.
     */
    onCompress: (() -> Unit)? = null,
    /** On a phone pane, when every picked row is an archive: unpack them. */
    onExtract: (() -> Unit)? = null,
    /** On a phone pane, when one `.001` part is picked: join the split back. */
    onJoin: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) {
                Icon(painterResource(R.drawable.ic_action_close), contentDescription = stringResource(R.string.menu_select_none))
            }
            // One line, and never squeezed to a column of single characters:
            // the count keeps its own width and the actions take the rest.
            Text(
                stringResource(R.string.selection_count, count),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            // The actions scroll sideways within whatever is left, so a narrow
            // phone with a bar full of them can still reach the last one rather
            // than having it pushed off the edge or wrapped onto another line.
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // One tap for the thing a server pane is mostly used for. Copy,
                // swipe and paste does the same and takes three.
                onDownload?.let { download ->
                    IconButton(onClick = download) {
                        Icon(
                            painterResource(R.drawable.ic_action_download),
                            contentDescription = stringResource(R.string.action_download_selected),
                        )
                    }
                }
                onShare?.let { share ->
                    IconButton(onClick = share, enabled = canShare) {
                        Icon(
                            painterResource(R.drawable.ic_action_share),
                            contentDescription = stringResource(R.string.action_share_selected),
                        )
                    }
                }
                onCompress?.let { compress ->
                    IconButton(onClick = compress) {
                        // Still Material: the OLO control set has no compress
                        // glyph yet (extract and join have theirs). Swapped to
                        // ic_action_compress the moment it lands, so the bar
                        // reads as one hand.
                        Icon(
                            Icons.Filled.FolderZip,
                            contentDescription = stringResource(R.string.archive_compress),
                        )
                    }
                }
                onExtract?.let { extract ->
                    IconButton(onClick = extract) {
                        Icon(
                            painterResource(R.drawable.ic_menu_unarchive),
                            contentDescription = stringResource(R.string.archive_extract_all),
                        )
                    }
                }
                onJoin?.let { join ->
                    IconButton(onClick = join) {
                        Icon(
                            painterResource(R.drawable.ic_action_merge),
                            contentDescription = stringResource(R.string.archive_join),
                        )
                    }
                }
                IconButton(onClick = onCut) {
                    Icon(painterResource(R.drawable.ic_action_cut), contentDescription = stringResource(R.string.action_cut))
                }
                IconButton(onClick = onCopy) {
                    Icon(painterResource(R.drawable.ic_action_copy), contentDescription = stringResource(R.string.action_copy))
                }
                IconButton(onClick = onRename, enabled = canRename) {
                    Icon(
                        painterResource(R.drawable.ic_action_rename),
                        contentDescription = stringResource(R.string.action_rename_selected),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        painterResource(R.drawable.ic_action_delete),
                        contentDescription = stringResource(R.string.action_delete_selected),
                    )
                }
            }
        }
    }
}

/**
 * The paste bar, shown only while something is held.
 *
 * It says why it cannot be used rather than being greyed out in silence: a
 * dead button explains nothing, and the two reasons it goes dead -- a folder
 * into itself, a place a transfer would be needed for -- are both things the
 * user can act on once they are told.
 */
@Composable
fun PasteBar(
    count: Int,
    refusal: PasteRefusal?,
    /** What the paste would do, so the bar can say "send" rather than "paste". */
    kind: PasteKind?,
    /** Cut rather than copied, which changes what the waiting message says. */
    cut: Boolean,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
    /**
     * Makes a folder here, without putting the clipboard down first.
     *
     * The bar's own words are "open the folder to move them into", and
     * until now there was no way to make one: the round button that makes
     * folders is hidden while a bar is up, because it floats over exactly
     * where the paste button sits. So "copy, then make somewhere to put it"
     * meant cancelling the copy, making the folder, and starting again.
     */
    onNewFolder: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        // A colour the theme actually defines. This was tertiaryContainer,
        // which it does not: Material filled the gap from its own palette and
        // the bar came out pink, which on this screen reads as an error.
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(painterResource(R.drawable.ic_action_close), contentDescription = stringResource(R.string.action_cancel))
            }
            Text(
                refusalText(refusal, count, cut) ?: stringResource(labelFor(kind), count),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            onNewFolder?.let { make ->
                IconButton(onClick = make) {
                    Icon(
                        painterResource(R.drawable.ic_menu_new_folder),
                        contentDescription = stringResource(R.string.fab_new_folder),
                    )
                }
            }
            // Named rather than an icon alone: it is the one thing this bar
            // is for, and a clipboard glyph is not a word anybody reads.
            if (refusal == null) {
                androidx.compose.material3.Button(onClick = onPaste, shape = androidx.compose.material3.MaterialTheme.shapes.small) {
                    Icon(
                        painterResource(R.drawable.ic_action_paste),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text(stringResource(actionFor(kind)))
                }
            }
        }
    }
}

/** A paste between two places is a transfer, and saying so sets the expectation. */
private fun labelFor(kind: PasteKind?): Int = when (kind) {
    PasteKind.UPLOAD -> R.string.paste_upload_here
    PasteKind.DOWNLOAD -> R.string.paste_download_here
    PasteKind.REMOTE_MOVE -> R.string.paste_move_here
    else -> R.string.paste_into
}

private fun actionFor(kind: PasteKind?): Int = when (kind) {
    PasteKind.UPLOAD, PasteKind.DOWNLOAD -> R.string.action_send
    else -> R.string.action_paste
}

/**
 * Why the button is not there, when there is a reason worth giving.
 *
 * "Already there" is not one. It is what the bar says the instant after a cut,
 * because the folder the items came from is the folder still on screen -- and
 * "they are already in this folder" read as the cut having failed. It gets a
 * plain instruction instead, since nothing has gone wrong.
 */
@Composable
private fun refusalText(refusal: PasteRefusal?, count: Int, cut: Boolean): String? =
    when (refusal) {
        null, PasteRefusal.NOTHING_HELD -> null
        PasteRefusal.ALREADY_THERE -> stringResource(
            if (cut) R.string.paste_go_somewhere_cut else R.string.paste_go_somewhere,
            count,
        )

        PasteRefusal.INTO_ITSELF -> stringResource(R.string.paste_into_itself)
        PasteRefusal.BETWEEN_SERVERS -> stringResource(R.string.paste_between_servers)
        PasteRefusal.NO_SERVER_COPY -> stringResource(R.string.paste_no_server_copy)
    }

/**
 * The bar shown while picking rows inside an archive.
 *
 * An archive is read only, so none of the ordinary selection actions --
 * cut, copy, delete, rename, share -- apply. What is left is one thing:
 * unpack what is picked, into a new folder beside the archive.
 */
@Composable
fun ArchiveSelectionBar(
    count: Int,
    onExtract: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) {
                Icon(painterResource(R.drawable.ic_action_close), contentDescription = stringResource(R.string.menu_select_none))
            }
            Text(
                stringResource(R.string.selection_count, count),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(onClick = onExtract) {
                Icon(
                    painterResource(R.drawable.ic_menu_unarchive),
                    contentDescription = stringResource(R.string.archive_extract_picked),
                )
            }
        }
    }
}
