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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.android.transfer.ActiveProgress
import org.filezilla.android.ui.theme.status
import org.filezilla.ftp.journal.TransferDirection
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.journal.TransferState

@Composable
fun QueueScreen(
    transfers: List<TransferRecord>,
    active: ActiveProgress?,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (transfers.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.queue_empty_title),
            detail = stringResource(R.string.queue_empty_detail),
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(transfers, key = { it.id }) { record ->
            // The live figure for whichever transfer is running, so the bar
            // moves; the journalled figure for the rest, which is what would
            // actually be resumed from.
            val live = active?.id == record.id
            TransferCard(
                record = record,
                bytes = if (live) active.bytes else record.bytesTransferred,
                total = if (live) active.totalBytes else record.totalBytes,
                onPause = { onPause(record.id) },
                onResume = { onResume(record.id) },
                onCancel = { onCancel(record.id) },
            )
        }
    }
}

@Composable
private fun TransferCard(
    record: TransferRecord,
    bytes: Long,
    total: Long?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val accent = accentFor(record.state)
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

                when (record.state) {
                    TransferState.RUNNING, TransferState.PENDING ->
                        FilledTonalIconButton(onClick = onPause) {
                            Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.queue_pause))
                        }

                    TransferState.PAUSED, TransferState.INTERRUPTED, TransferState.FAILED ->
                        FilledTonalIconButton(onClick = onResume) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.queue_resume))
                        }

                    TransferState.COMPLETED -> Unit
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

            if (record.state != TransferState.COMPLETED) {
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
                } else if (record.state == TransferState.RUNNING) {
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
                    stateLabel(record.state),
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

            if (record.attempts > 1 && record.state != TransferState.COMPLETED) {
                Text(
                    stringResource(R.string.queue_attempt, record.attempts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            record.lastError?.takeIf { record.state != TransferState.COMPLETED }?.let { error ->
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
private fun accentFor(state: TransferState): Color = when (state) {
    TransferState.RUNNING -> MaterialTheme.status.running
    TransferState.PENDING -> MaterialTheme.status.waiting
    TransferState.PAUSED -> MaterialTheme.status.paused
    TransferState.INTERRUPTED -> MaterialTheme.status.paused
    TransferState.COMPLETED -> MaterialTheme.status.done
    TransferState.FAILED -> MaterialTheme.status.failed
}

@Composable
private fun stateLabel(state: TransferState): String = stringResource(
    when (state) {
        TransferState.PENDING -> R.string.state_pending
        TransferState.RUNNING -> R.string.state_running
        TransferState.PAUSED -> R.string.state_paused
        TransferState.INTERRUPTED -> R.string.state_interrupted
        TransferState.COMPLETED -> R.string.state_completed
        TransferState.FAILED -> R.string.state_failed
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
