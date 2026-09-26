package org.filezilla.android.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.android.data.TrashEntry

/**
 * The files deleted from the phone, newest first -- a holding place they wait
 * in until the trash is emptied, so a delete made in error is one tap from
 * undone. Grouped by the day they were deleted; a long press offers to put a
 * file back where it came from or to erase it for good.
 *
 * Only the phone's own files land here: a delete on a server has no undo to
 * lean on and stays permanent, as it always was.
 */
@Composable
fun TrashScreen(
    model: MainViewModel,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { model.refreshTrash() }

    val entries = model.trash
    val dayStart = remember { RecentDays.today() }
    val yesterdayStart = remember { RecentDays.yesterday() }

    Column(modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.trash_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.trash_empty_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(entries) { index, entry ->
                val group = RecentDays.groupOf(entry.time, dayStart, yesterdayStart)
                val prev = entries.getOrNull(index - 1)
                    ?.let { RecentDays.groupOf(it.time, dayStart, yesterdayStart) }
                if (group != prev) {
                    TrashGroupHeader(group)
                }
                TrashRow(
                    entry = entry,
                    file = model.trashFile(entry),
                    source = model.trashSource(entry.originalPath),
                    onRestore = { model.restoreFromTrash(entry) },
                    onDeleteForever = { model.deleteFromTrashForever(entry) },
                )
            }
        }
    }
}

@Composable
private fun TrashGroupHeader(group: RecentGroup) {
    Text(
        stringResource(group.label),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun TrashRow(
    entry: TrashEntry,
    file: java.io.File,
    source: String,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    // The name shown is the one the file had before it was deleted, not the
    // possibly-suffixed name it wears inside the trash folder.
    val original = remember(entry.originalPath) { java.io.File(entry.originalPath) }
    val name = original.name
    val folder = original.parent
    val kind = remember(entry.originalPath) { kindOf(name, false) }
    var menuOpen by remember { mutableStateOf(false) }
    var pressAt by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            pressAt = it
                            menuOpen = true
                        },
                    )
                }
                .padding(horizontal = 20.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrashRowBody(file = file, name = name, folder = folder, kind = kind, source = source, time = entry.time)
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = with(density) { DpOffset(pressAt.x.toDp(), pressAt.y.toDp()) },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.trash_restore)) },
                onClick = {
                    menuOpen = false
                    onRestore()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.trash_delete_forever)) },
                onClick = {
                    menuOpen = false
                    onDeleteForever()
                },
            )
        }
    }
}

@Composable
private fun RowScope.TrashRowBody(
    file: java.io.File,
    name: String,
    folder: String?,
    kind: FileKind,
    source: String,
    time: Long,
) {
    // The deleted file still sits on disk in the trash, so a picture, film or
    // song can show its own thumbnail here as it did in the list it left.
    if (Thumbnails.handles(kind)) {
        EntryThumb(file = file, kind = kind, contentDescription = null, size = 40.dp)
    } else {
        FileTile(kind = kind, colour = colourFor(kind), contentDescription = null, size = 40.dp)
    }
    Column(
        Modifier
            .weight(1f)
            .padding(start = 14.dp),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Where it came from, which is where restoring puts it back: the
        // volume it sat on and the folder within it, so two files of the same
        // name are told apart and the user knows what "restore" will do.
        val where = remember(source, folder) {
            listOfNotNull(source.ifEmpty { null }, folder).joinToString(" · ")
        }
        if (where.isNotEmpty()) {
            Text(
                where,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Text(
        relativeDeletedTime(time),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 8.dp),
    )
}

/** When the file was deleted, read out the same relative way recents shows time. */
@Composable
private fun relativeDeletedTime(time: Long): String = remember(time) {
    android.text.format.DateUtils.getRelativeTimeSpanString(
        time,
        System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
