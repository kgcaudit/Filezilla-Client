package org.filezilla.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.filezilla.android.transfer.ActiveProgress
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
            title = "Nothing queued",
            detail = "Tap a file on the Browse tab to download it. Transfers keep going when you leave the app.",
            modifier = modifier,
        )
        return
    }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(12.dp)) {
        items(transfers, key = { it.id }) { record ->
            // The live figure for whichever transfer is running, so the bar
            // moves; the journalled figure for the rest, which is what would
            // actually be resumed from.
            val bytes = if (active?.id == record.id) active.bytes else record.bytesTransferred
            val total = if (active?.id == record.id) active.totalBytes else record.totalBytes

            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                record.remotePath.substringAfterLast('/'),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                            )
                            Text(
                                "${directionLabel(record.direction)} · ${record.user}@${record.host}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                        when (record.state) {
                            TransferState.RUNNING, TransferState.PENDING ->
                                IconButton(onClick = { onPause(record.id) }) {
                                    Icon(Icons.Filled.Pause, contentDescription = "Pause")
                                }

                            TransferState.PAUSED, TransferState.INTERRUPTED, TransferState.FAILED ->
                                IconButton(onClick = { onResume(record.id) }) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = "Resume")
                                }

                            TransferState.COMPLETED -> Unit
                        }
                        IconButton(onClick = { onCancel(record.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove from the queue")
                        }
                    }

                    if (record.state != TransferState.COMPLETED) {
                        if (total != null && total > 0) {
                            LinearProgressIndicator(
                                progress = { (bytes.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        } else if (record.state == TransferState.RUNNING) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                    }

                    Text(
                        statusLine(record, bytes, total),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )

                    record.lastError?.takeIf { record.state != TransferState.COMPLETED }?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun directionLabel(direction: TransferDirection) =
    if (direction == TransferDirection.DOWNLOAD) "Download" else "Upload"

private fun statusLine(record: TransferRecord, bytes: Long, total: Long?): String {
    val size = if (total != null && total > 0) {
        "${formatSize(bytes)} of ${formatSize(total)}"
    } else {
        formatSize(bytes)
    }
    val state = when (record.state) {
        TransferState.PENDING -> "Queued"
        TransferState.RUNNING -> "Transferring"
        TransferState.PAUSED -> "Paused"
        // Named for what it means to the user: the bytes already fetched are
        // still good, and picking it up again does not re-fetch them.
        TransferState.INTERRUPTED -> "Interrupted — will resume"
        TransferState.COMPLETED -> "Done"
        TransferState.FAILED -> "Failed"
    }
    val attempts = if (record.attempts > 1) " · attempt ${record.attempts}" else ""
    return "$state · $size$attempts"
}
