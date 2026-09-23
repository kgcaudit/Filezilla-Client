package org.filezilla.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.filezilla.android.R

/**
 * A stretch of work on a server that takes long enough to need saying.
 *
 * FTP has no bulk anything. Deleting a folder is one `DELE` per file and
 * one `RMD` per folder, each a round trip; working out what is in it first
 * is three more per folder. A few hundred files is a few hundred round
 * trips, which on a phone away from home is a minute of a screen that
 * shows nothing but a bar going back and forth -- and a minute of that
 * reads as a broken app, not a busy one.
 *
 * So the work says what it is doing and how far along it is, and it can be
 * called off. The second matters as much as the first: a wrong selection
 * committed by accident was, until now, something to sit and watch.
 */
data class ServerWork(
    val kind: Kind,
    /**
     * Folders opened so far. The only figure there is while walking,
     * because until the walk finishes nobody knows how many there are.
     */
    val foldersRead: Int = 0,
    val done: Int = 0,
    /** Known once the walk has finished, and null until then. */
    val total: Int? = null,
    /** What is being worked on right now, so the count has something to be about. */
    val current: String = "",
    private val stopper: () -> Unit = {},
) {
    enum class Kind { SCANNING, DELETING }

    fun stop() = stopper()
}

/**
 * Says what the server is being asked to do, and offers to stop.
 *
 * Held back for a moment before it appears. Most of these finish in well
 * under a second on a server in the same room, and a dialog that flashes
 * up and vanishes is worse than no dialog: it reads as something having
 * gone wrong. The wait is in real time rather than in a count of files, so
 * a slow link shows it for three files and a fast one does not show it for
 * three hundred.
 */
@Composable
fun ServerWorkDialog(work: ServerWork, onStop: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(SHOW_AFTER_MILLIS)
        shown = true
    }
    if (!shown) return

    OloDialog(
        title = stringResource(
            when (work.kind) {
                ServerWork.Kind.SCANNING -> R.string.work_scanning
                ServerWork.Kind.DELETING -> R.string.work_deleting
            },
        ),
        // A stray touch outside, or a back gesture, must not stop the work: the
        // stop button below is the only way to stop, so nothing else closes it.
        onDismiss = {},
        dismissLabel = null,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val total = work.total
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (work.done.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.work_counted, work.done, total),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    // Still finding out how much there is. A bar with a
                    // position would have to invent one.
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.work_folders_read, work.foldersRead),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (work.current.isNotEmpty()) {
                    Text(
                        work.current,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        action = {
            ConfirmButton(text = stringResource(R.string.action_stop), onClick = onStop)
        },
    )
}

/**
 * How long work has to last before it is worth a dialog.
 *
 * Long enough that deleting a handful of files on a server in the same
 * room never shows one, short enough that nobody on a slow link waits
 * wondering.
 */
const val SHOW_AFTER_MILLIS = 400L
