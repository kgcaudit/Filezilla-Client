package org.filezilla.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.filezilla.android.R
import org.filezilla.android.archive.ArchiveBrowsing
import org.filezilla.android.archive.ArchiveEntry
import org.filezilla.android.archive.ArchiveRow

/** What the screen is looking at and what has been chosen in it. */
data class ArchiveView(
    val name: String,
    val entries: List<ArchiveEntry>,
    /** The folder inside the archive, without a trailing slash. */
    val at: String = "",
    val picks: Set<String> = emptySet(),
)

/** What the screen can be asked to do. */
data class ArchiveActions(
    val onEnter: (String) -> Unit,
    val onUp: () -> Unit,
    val onToggle: (String) -> Unit,
    val onPickAll: () -> Unit,
    val onPickNone: () -> Unit,
    val onExtract: (Boolean) -> Unit,
    val onClose: () -> Unit,
)

/**
 * Looking inside an archive without unpacking it.
 *
 * A whole screen rather than a dialog with a list in it, because the
 * thing being shown is a file list and the app already knows how to draw
 * one -- the same tile, the same two lines, the same checkbox. An
 * archive is a folder that happens to be a file, and it should not need
 * learning twice.
 *
 * The folders it walks are mostly not in the archive; see
 * [ArchiveBrowsing]. Nothing is unpacked to show this list: the entries
 * come from the archive's own index, so opening a four-gigabyte archive
 * to look at one name inside it costs a read of the index and no more.
 */
@Composable
fun ArchiveScreen(
    view: ArchiveView,
    actions: ArchiveActions,
    /** Set while something is being unpacked, so the list cannot be used. */
    busy: ArchiveBusy? = null,
) {
    Dialog(
        onDismissRequest = actions.onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                ArchiveBar(view, actions)
                HorizontalDivider()

                val rows = remember(view.entries, view.at) {
                    ArchiveBrowsing.rowsIn(view.entries, view.at)
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (rows.isEmpty()) {
                        Text(
                            stringResource(R.string.archive_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(rows, key = { it.path }) { row ->
                                ArchiveRowView(
                                    row = row,
                                    chosen = row.path in view.picks,
                                    onTap = {
                                        if (row.isDirectory) actions.onEnter(row.path.trimEnd('/'))
                                        else actions.onToggle(row.path)
                                    },
                                    onToggle = { actions.onToggle(row.path) },
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()
                if (busy != null) ArchiveBusyBar(busy) else ArchiveButtons(view, actions)
            }
        }
    }
}

/** The name of the archive, where we are in it, and the way out. */
@Composable
private fun ArchiveBar(view: ArchiveView, actions: ArchiveActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (view.at.isEmpty()) {
            IconButton(onClick = actions.onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
            }
        } else {
            IconButton(onClick = actions.onUp) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.archive_up),
                )
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                view.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (view.at.isNotEmpty()) {
                Text(
                    view.at,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ArchiveRowView(
    row: ArchiveRow,
    chosen: Boolean,
    onTap: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (chosen) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = chosen, onCheckedChange = { onToggle() })
        val kind = remember(row.path, row.isDirectory) { kindOf(row.name, row.isDirectory) }
        FileTile(kind = kind, colour = colourFor(kind), contentDescription = null)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (row.isDirectory) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // One line under the name, and what it says depends on what is
            // wrong with the row. An entry this app cannot unpack says so
            // here rather than waiting for the unpack to skip it silently.
            val detail = when {
                row.isDirectory -> stringResource(R.string.archive_items, row.count)
                row.unreadable != null -> stringResource(R.string.archive_unreadable)
                row.encrypted -> stringResource(R.string.archive_locked)
                row.size >= 0 -> formatSize(row.size)
                else -> ""
            }
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.unreadable != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ArchiveButtons(view: ArchiveView, actions: ArchiveActions) {
    val picked = remember(view.entries, view.picks) {
        ArchiveBrowsing.picked(view.entries, view.picks)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (picked.files > 0) {
            Text(
                stringResource(R.string.archive_picked, picked.files, formatSize(picked.bytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (view.picks.isEmpty()) {
                TextButton(onClick = actions.onPickAll) {
                    Text(stringResource(R.string.archive_pick_all))
                }
            } else {
                TextButton(onClick = actions.onPickNone) {
                    Text(stringResource(R.string.archive_pick_none))
                }
            }
            // One button, and which one it is follows the selection: with
            // nothing chosen "unpack chosen" would be a button that does
            // nothing, and with something chosen "unpack all" is not what
            // the person in front of it just asked for.
            ConfirmButton(
                text = stringResource(
                    if (view.picks.isEmpty()) R.string.archive_extract_all
                    else R.string.archive_extract_picked,
                ),
                onClick = { actions.onExtract(view.picks.isEmpty()) },
                enabled = view.picks.isEmpty() || picked.files > 0,
            )
        }
    }
}

/** Something running, with what it is on and a way to stop it. */
data class ArchiveBusy(
    val title: String,
    val path: String,
    val done: Int,
    val total: Int,
    val onStop: () -> Unit,
)

@Composable
private fun ArchiveBusyBar(busy: ArchiveBusy) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(busy.title, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = busy.onStop) { Text(stringResource(R.string.action_stop)) }
        }
        // The name of the file being written, not a bare bar. A long
        // unpack with nothing but a moving line is the thing this was
        // written to stop.
        Text(
            busy.path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(R.string.work_counted, busy.done, busy.total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { if (busy.total > 0) busy.done.toFloat() / busy.total else 0f },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

/**
 * Asking for an archive's password, once, before anything is unpacked.
 *
 * At the front rather than part way through: a question that appears
 * behind a progress bar somebody has stopped watching is a question that
 * goes unanswered, and the unpack then quietly leaves out every locked
 * file.
 */
@Composable
fun ArchivePasswordDialog(
    wrong: Boolean,
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    OloDialog(
        title = stringResource(R.string.archive_password_title),
        detail = stringResource(R.string.archive_password_detail),
        onDismiss = onDismiss,
        content = {
            OloTextField(
                value = typed,
                onValueChange = { typed = it },
                label = stringResource(R.string.archive_password_title),
                isError = wrong,
                supportingText = if (wrong) stringResource(R.string.archive_password_wrong) else null,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                ),
            )
        },
        action = {
            ConfirmButton(
                text = stringResource(R.string.archive_open),
                onClick = { onSubmit(typed.toCharArray()) },
                enabled = typed.isNotEmpty(),
            )
        },
    )
}

/**
 * What a compress shows, since it has no archive screen behind it.
 *
 * The same words and the same stop button as the bar inside the archive
 * screen: it is the same work, started from a different place.
 */
@Composable
fun ArchiveWorkDialog(busy: ArchiveBusy) {
    OloDialog(
        title = busy.title,
        onDismiss = busy.onStop,
        dismissLabel = null,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (busy.path.isNotEmpty()) {
                    Text(
                        busy.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(R.string.work_counted, busy.done, busy.total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { if (busy.total > 0) busy.done.toFloat() / busy.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        action = {
            TextButton(onClick = busy.onStop) { Text(stringResource(R.string.action_stop)) }
        },
    )
}


/**
 * The wait while an archive's index is read.
 *
 * Not dismissable and with no button: it is gone the instant the list is
 * ready or the failure is shown, and a cancel here would race the open
 * for no gain -- reading an index is quick even when it is not instant.
 */
@Composable
fun ArchiveOpeningDialog(name: String) {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 3.dp,
                )
                Text(
                    stringResource(R.string.archive_opening, name),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}
