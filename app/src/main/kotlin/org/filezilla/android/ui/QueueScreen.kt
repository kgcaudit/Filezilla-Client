package org.filezilla.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.filezilla.android.R
import org.filezilla.android.transfer.ActiveProgress
import org.filezilla.android.transfer.isStalled
import org.filezilla.android.transfer.secondsRemaining
import org.filezilla.android.ui.theme.status
import org.filezilla.ftp.journal.TransferDirection
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.journal.TransferState

@Composable
fun QueueScreen(
    transfers: List<TransferRecord>,
    active: Map<String, ActiveProgress>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (transfers.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.queue_empty_title),
            detail = stringResource(R.string.queue_empty_detail),
            icon = R.drawable.ic_flat_transfers,
            modifier = modifier,
        )
        return
    }

    // This screen is driven by bytes arriving, so a transfer whose connection
    // has died stops updating it -- and the card stays exactly as it was, a
    // frozen byte count above a speed that is no longer true. The clock is
    // what lets the screen notice the silence.
    val now = tickWhile(active.isNotEmpty())

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(transfers, key = { it.id }) { record ->
            // The live figure for a transfer that is actually running, so the
            // bar moves; the journalled figure for the rest, which is what
            // would actually be resumed from. Two run at once, so this is a
            // lookup rather than a comparison against one current transfer.
            val live = active[record.id]
            // Only a transfer the queue still considers running: the live
            // figures outlive the record's state by an instant as a transfer
            // unwinds, and "reconnecting" on something the user has just
            // paused would be the wrong answer at the worst moment.
            val stalled = record.state == TransferState.RUNNING &&
                live != null && isStalled(live.updatedAtMillis, now)
            TransferCard(
                record = record,
                // One word for the whole retry loop; see [moodOf]. The queue
                // walks through four states a second when a connection dies,
                // and the card used to show every one of them.
                mood = moodOf(record, stalled),
                bytes = live?.bytes ?: record.bytesTransferred,
                total = live?.totalBytes ?: record.totalBytes,
                // Only a running transfer has a speed; a paused or waiting one
                // showing a leftover figure would be a lie, and so would the
                // last speed of one that has stopped receiving.
                bytesPerSecond = live?.bytesPerSecond.takeUnless { stalled },
                onPause = { onPause(record.id) },
                onResume = { onResume(record.id) },
                onCancel = { onCancel(record.id) },
            )
        }
    }
}

/**
 * A clock that ticks once a second while [running], and stands still
 * otherwise.
 *
 * Conditional because a screen that recomposes every second for nothing costs
 * battery on the one screen a user is most likely to leave open.
 */
@Composable
private fun tickWhile(running: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return now
}

@Composable
private fun TransferCard(
    record: TransferRecord,
    /** What the card says, which is steadier than what the queue is doing. */
    mood: TransferMood,
    bytes: Long,
    total: Long?,
    bytesPerSecond: Long?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val accent = accentFor(mood)
    val percent = if (total != null && total > 0) {
        ((bytes.toDouble() / total) * 100).toInt().coerceIn(0, 100)
    } else {
        null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A dot in the status colour, so the state is readable before
                // any text is: the list is scanned, not read.
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(accent, CircleShape),
                )
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        record.remotePath.substringAfterLast('/'),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${directionLabel(record.direction)} · ${record.user}@${record.host}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Keyed on the mood, not the state underneath it: a button
                // that swapped between pause and play four times a second was
                // half of what made the card unreadable, and the other half
                // was that it could not be pressed while it was doing it.
                when (mood) {
                    TransferMood.RUNNING, TransferMood.QUEUED, TransferMood.RECONNECTING ->
                        FilledTonalIconButton(onClick = onPause) {
                            Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.queue_pause))
                        }

                    TransferMood.PAUSED, TransferMood.FAILED ->
                        FilledTonalIconButton(onClick = onResume) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.queue_resume))
                        }

                    // It does start again by itself, but "by itself" is no
                    // comfort to someone watching it not happen. The button is
                    // the way out when the automatic path has not fired.
                    TransferMood.WAITING_FOR_NETWORK ->
                        FilledTonalIconButton(onClick = onResume) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.queue_retry_now),
                            )
                        }

                    TransferMood.DONE -> Unit
                }
                IconButton(
                    onClick = onCancel,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.queue_remove))
                }
            }

            if (mood != TransferMood.DONE) {
                Spacer8()
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        color = accent,
                        // An explicitly visible track: the default is nearly
                        // the card colour, which made the bar look like a
                        // floating line with no scale behind it.
                        trackColor = MaterialTheme.status.progressTrack,
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                } else if (mood == TransferMood.RUNNING) {
                    LinearProgressIndicator(
                        color = accent,
                        trackColor = MaterialTheme.status.progressTrack,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
            }

            Spacer8()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    moodLabel(mood),
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    sizeLine(bytes, total, percent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }

            // Hidden while the connection is not there. A speed and an
            // estimate computed from bytes that stopped arriving are two more
            // numbers changing on a card that is already changing too much,
            // and both of them are wrong.
            speedLine(bytes, total, bytesPerSecond).takeUnless { mood.isUnsettled }?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }

            if (record.attempts > 1 && mood != TransferMood.DONE) {
                Text(
                    stringResource(R.string.queue_attempt, record.attempts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Only once it has stopped trying. While it is retrying this is
            // the same sentence every few hundred milliseconds, appearing and
            // vanishing with each pass -- and it says nothing the word
            // "reconnecting" above it has not already said.
            record.lastError?.takeIf { mood == TransferMood.FAILED }?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun Spacer8() = Box(modifier = Modifier.size(8.dp))

@Composable
private fun accentFor(mood: TransferMood): Color = when (mood) {
    TransferMood.RUNNING -> MaterialTheme.status.running
    TransferMood.QUEUED -> MaterialTheme.status.waiting
    TransferMood.RECONNECTING -> MaterialTheme.status.paused
    TransferMood.WAITING_FOR_NETWORK -> MaterialTheme.status.waiting
    TransferMood.PAUSED -> MaterialTheme.status.paused
    TransferMood.DONE -> MaterialTheme.status.done
    TransferMood.FAILED -> MaterialTheme.status.failed
}

@Composable
private fun moodLabel(mood: TransferMood): String = stringResource(
    when (mood) {
        TransferMood.QUEUED -> R.string.state_pending
        TransferMood.RUNNING -> R.string.state_running
        TransferMood.RECONNECTING -> R.string.state_reconnecting
        TransferMood.WAITING_FOR_NETWORK -> R.string.state_waiting_for_network
        TransferMood.PAUSED -> R.string.state_paused
        TransferMood.FAILED -> R.string.state_failed
        TransferMood.DONE -> R.string.state_completed
    },
)

@Composable
private fun directionLabel(direction: TransferDirection): String = stringResource(
    if (direction == TransferDirection.DOWNLOAD) R.string.direction_download else R.string.direction_upload,
)

private fun sizeLine(bytes: Long, total: Long?, percent: Int?): String = when {
    total != null && total > 0 && percent != null ->
        "${formatSize(bytes)} / ${formatSize(total)} · $percent%"

    else -> formatSize(bytes)
}

/** Speed, and time left when the total makes that an honest thing to say. */
@Composable
private fun speedLine(bytes: Long, total: Long?, bytesPerSecond: Long?): String? {
    if (bytesPerSecond == null || bytesPerSecond <= 0) return null
    val speed = formatSpeed(bytesPerSecond)
    val left = secondsRemaining(bytes, total, bytesPerSecond)
        ?: return speed
    return stringResource(R.string.queue_speed_remaining, speed, formatDuration(left))
}
