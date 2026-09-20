package org.filezilla.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.journal.TransferState

/**
 * What the queue is doing, in the two numbers a strip has room for.
 *
 * [fraction] is null when it cannot be known -- a transfer whose total size
 * the server never gave -- and the strip then shows an indeterminate bar
 * rather than a figure it made up.
 */
data class TransferSummary(val count: Int, val fraction: Float?)

/**
 * The queue's state, or null when there is nothing to say.
 *
 * Null rather than a zero, because the strip that shows this exists only
 * while it has something to show. Transfers had a tab along the bottom of
 * every screen, which cost 80dp whether anything was transferring or not and
 * still only said "transfers"; a strip costs nothing when the queue is empty
 * and says how far along it is when it is not.
 *
 * Paused and failed transfers are deliberately not counted. They are
 * outstanding, but nothing is happening to them, and a strip pinned to the
 * screen by a transfer the user paused on purpose is the bar that would not
 * go away -- which this app has already shipped once.
 */
fun summariseTransfers(records: List<TransferRecord>): TransferSummary? {
    val busy = records.filter { it.state in MOVING }
    if (busy.isEmpty()) return null
    val total = busy.sumOf { it.totalBytes ?: return TransferSummary(busy.size, null) }
    if (total <= 0) return TransferSummary(busy.size, null)
    val done = busy.sumOf { it.bytesTransferred }
    return TransferSummary(busy.size, (done.toFloat() / total).coerceIn(0f, 1f))
}

/**
 * The states that mean work is in hand.
 *
 * `INTERRUPTED` counts: it retries by itself, so from the user's side it is
 * still going. `WAITING_FOR_NETWORK` counts for the same reason -- it starts
 * again on its own once an allowed network is back.
 */
private val MOVING = setOf(
    TransferState.RUNNING,
    TransferState.PENDING,
    TransferState.INTERRUPTED,
    TransferState.WAITING_FOR_NETWORK,
)

/**
 * A line along the foot of the files screen while the queue is working.
 *
 * What it replaces is a navigation tab that said "transfers" and nothing
 * else, from a bar that cost 80dp of every screen whether anything was
 * transferring or not. This costs nothing when the queue is empty -- there is
 * no strip -- and when it is not empty it says how many and how far, which
 * the tab never did.
 */
@androidx.compose.runtime.Composable
fun TransferStrip(summary: TransferSummary, onOpen: () -> Unit) {
    androidx.compose.material3.Surface(
        color = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Column {
            // Along the very top edge, so the strip reads as the queue's own
            // progress rather than as a bar with a decoration in it.
            if (summary.fraction != null) {
                LinearProgressIndicator(
                    progress = { summary.fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_flat_transfers),
                    contentDescription = null,
                    // Unbounded, or Material flattens the artwork's two
                    // arrows to one colour and they stop reading as two.
                    tint = Color.Unspecified,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(R.string.transfers_running, summary.count),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                    maxLines = 1,
                )
                summary.fraction?.let {
                    Text(
                        stringResource(R.string.transfers_percent, (it * 100).toInt()),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}
