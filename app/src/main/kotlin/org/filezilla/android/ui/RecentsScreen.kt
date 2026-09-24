package org.filezilla.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.android.data.RecentEntry

/**
 * The files opened in a viewer, newest first -- one place that gathers what has
 * been read, watched, listened to or browsed, across all four viewers.
 *
 * Grouped by when they were opened and filtered by kind, so a long list can be
 * skimmed to the one thing being looked for. A tap reopens the file in the
 * viewer its kind calls for, resuming where the player or the reader left off;
 * a long press offers to forget it. A file that has since gone is shown faded
 * and, tapped, drops itself from the list rather than opening onto nothing.
 */
@Composable
fun RecentsScreen(
    model: MainViewModel,
    onOpen: (RecentEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { model.refreshRecents() }

    var filter by remember { mutableStateOf(RecentFilter.ALL) }
    val entries = model.recents
    val shown = entries.filter { filter.accepts(kindOf(java.io.File(it.path).name, false)) }

    val dayStart = remember { RecentDays.today() }
    val yesterdayStart = remember { RecentDays.yesterday() }

    Column(modifier.fillMaxSize()) {
        FilterRow(filter = filter, onFilter = { filter = it })

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.recents_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.recents_empty_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            return@Column
        }

        // The group each row belongs to is decided from its time, and a header
        // is drawn the moment the group changes going down the list -- so the
        // three headings need no separate bookkeeping.
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(shown) { index, entry ->
                val group = RecentDays.groupOf(entry.time, dayStart, yesterdayStart)
                val prev = shown.getOrNull(index - 1)
                    ?.let { RecentDays.groupOf(it.time, dayStart, yesterdayStart) }
                if (group != prev) {
                    GroupHeader(group)
                }
                RecentRow(
                    entry = entry,
                    source = model.recentSource(entry.path),
                    onOpen = { onOpen(entry) },
                    onRemove = { model.removeRecent(entry.path) },
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(group: RecentGroup) {
    Text(
        stringResource(group.label),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun RecentRow(
    entry: RecentEntry,
    source: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val file = remember(entry.path) { java.io.File(entry.path) }
    val gone = remember(entry.path, entry.time) { !file.exists() }
    val kind = remember(entry.path) { kindOf(file.name, false) }
    var menuOpen by remember { mutableStateOf(false) }
    // Where the long press landed, so the menu opens under the finger rather
    // than at the row's left edge, half a screen from where it was asked for.
    var pressAt by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    // The row and its menu share one Box, so the menu anchors to the row.
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onOpen() },
                        onLongPress = {
                            pressAt = it
                            menuOpen = true
                        },
                    )
                }
                .padding(horizontal = 20.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecentRowBody(file = file, kind = kind, source = source, time = entry.time, gone = gone)
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = with(density) { DpOffset(pressAt.x.toDp(), pressAt.y.toDp()) },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.recents_remove)) },
                onClick = {
                    menuOpen = false
                    onRemove()
                },
            )
        }
    }
}

@Composable
private fun RowScope.RecentRowBody(
    file: java.io.File,
    kind: FileKind,
    source: String,
    time: Long,
    gone: Boolean,
) {
    val fade = if (gone) 0.4f else 1f
    Box(Modifier.alpha(fade)) {
        FileTile(kind = kind, colour = colourFor(kind), contentDescription = null, size = 40.dp)
    }
    Column(
        Modifier
            .weight(1f)
            .padding(start = 14.dp)
            .alpha(fade),
    ) {
        Text(
            file.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val detail = listOfNotNull(source.ifEmpty { null }, stringResource(kindLabel(kind)))
            .joinToString(" · ")
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Text(
        relativeTime(time),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 8.dp),
    )
}

@Composable
private fun FilterRow(filter: RecentFilter, onFilter: (RecentFilter) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (option in RecentFilter.entries) {
            val chosen = option == filter
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onFilter(option) }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            ) {
                Text(
                    stringResource(option.label),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (chosen) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** A right-hand time read-out -- "just now", "5 min ago", "yesterday" -- localised. */
@Composable
private fun relativeTime(time: Long): String = remember(time) {
    android.text.format.DateUtils.getRelativeTimeSpanString(
        time,
        System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}

/** The kind's own word, for the row's quieter second line. */
private fun kindLabel(kind: FileKind): Int = when (kind) {
    FileKind.VIDEO -> R.string.recents_filter_video
    FileKind.AUDIO -> R.string.recents_filter_audio
    FileKind.IMAGE -> R.string.recents_filter_image
    FileKind.COMIC -> R.string.recents_kind_comic
    FileKind.ARCHIVE -> R.string.recents_filter_archive
    else -> R.string.recents_filter_text
}

/** Which day a time falls in, for the list's three headings. */
enum class RecentGroup(val label: Int) {
    TODAY(R.string.recents_group_today),
    YESTERDAY(R.string.recents_group_yesterday),
    EARLIER(R.string.recents_group_earlier),
}

private object RecentDays {
    fun today(): Long = startOfDay(0)
    fun yesterday(): Long = startOfDay(-1)

    fun groupOf(time: Long, todayStart: Long, yesterdayStart: Long): RecentGroup = when {
        time >= todayStart -> RecentGroup.TODAY
        time >= yesterdayStart -> RecentGroup.YESTERDAY
        else -> RecentGroup.EARLIER
    }

    private fun startOfDay(dayOffset: Int): Long {
        val c = java.util.Calendar.getInstance()
        c.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}

/** The kinds the filter chips sort the list into; ALL keeps everything. */
enum class RecentFilter(val label: Int, private val kinds: Set<FileKind>) {
    ALL(R.string.recents_filter_all, emptySet()),
    VIDEO(R.string.recents_filter_video, setOf(FileKind.VIDEO)),
    AUDIO(R.string.recents_filter_audio, setOf(FileKind.AUDIO)),
    IMAGE(R.string.recents_filter_image, setOf(FileKind.IMAGE, FileKind.COMIC)),
    TEXT(R.string.recents_filter_text, setOf(FileKind.DOCUMENT, FileKind.CODE)),
    ARCHIVE(R.string.recents_filter_archive, setOf(FileKind.ARCHIVE));

    fun accepts(kind: FileKind): Boolean = this == ALL || kind in kinds
}
