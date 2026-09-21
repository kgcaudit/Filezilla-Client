package org.filezilla.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.filezilla.android.R

/**
 * What is happening while a server file is on its way to being opened.
 *
 * A dialog rather than a line at the foot of the screen, because it is the
 * answer to a tap and the tap has nothing else to show for itself yet. It
 * takes the screen for as long as a small file takes, which is not long,
 * and a large one can be stopped from here -- the one thing somebody needs
 * when they have just tapped a film by accident.
 */
@Composable
fun ViewingDialog(viewing: MainViewModel.Viewing, onCancel: () -> Unit) {
    OloDialog(
        title = stringResource(R.string.viewing_title),
        onDismiss = onCancel,
        // No confirming button. There is nothing to confirm: the only
        // thing to decide is whether to carry on, and the dismissing
        // button already says that.
        dismissLabel = null,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FileHeading(viewing.name, isDirectory = false)

                val total = viewing.total
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (viewing.bytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(
                            R.string.viewing_progress,
                            formatSize(viewing.bytes),
                            formatSize(total),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // The server would not say how big it is. A made-up
                    // percentage would be worse than no bar.
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.viewing_progress_unknown, formatSize(viewing.bytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        action = {
            ConfirmButton(text = stringResource(R.string.action_stop), onClick = onCancel)
        },
    )
}

/**
 * Said once, before the first server file is ever opened.
 *
 * Somebody will open a text file, change it, and save. What they save is a
 * copy in this app's cache, and the server will not have it. That is a
 * surprise worth one dialog -- and worth only one, because a warning shown
 * every time is a warning nobody reads.
 */
@Composable
fun ReadOnlyNotice(onAcknowledge: () -> Unit) {
    OloDialog(
        title = stringResource(R.string.viewing_read_only_title),
        detail = stringResource(R.string.viewing_read_only_detail),
        onDismiss = onAcknowledge,
        dismissLabel = null,
        action = {
            ConfirmButton(text = stringResource(R.string.action_got_it), onClick = onAcknowledge)
        },
    )
}

/** Why a file could not be fetched, in the same words the panes use. */
@Composable
fun ViewingFailureDialog(failure: ConnectionFailure, onDismiss: () -> Unit) {
    OloInfoDialog(
        title = stringResource(failure.title),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(failure.advice), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(failure.detailFormat, failure.detailArg),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
