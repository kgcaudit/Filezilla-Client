package org.filezilla.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.android.storage.ConflictChoice
import org.filezilla.android.storage.DownloadConflict

/**
 * Asks what to do about files already in the destination folder.
 *
 * Nothing has been fetched when this appears. That is deliberate: a user who
 * answers "skip" has then spent no data at all, which is the whole reason to
 * ask before the transfer rather than at the end of it.
 */
@Composable
fun ConflictDialog(
    conflicts: List<DownloadConflict>,
    onChoose: (ConflictChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    OloDialog(
        title = pluralStringResource(R.plurals.conflict_title, conflicts.size, conflicts.size),
        onDismiss = onDismiss,
        content = {
            Column(
                // Capped so a long list scrolls inside the dialog instead of
                // pushing the buttons off the screen.
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // One file gets the full comparison, which is what makes the
                // choice an informed one. A hundred files cannot, so they get
                // the names and a count instead of a wall of sizes.
                if (conflicts.size == 1) {
                    ConflictDetail(conflicts.single())
                } else {
                    Text(
                        stringResource(R.string.conflict_many_detail),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    for (conflict in conflicts.take(MAX_LISTED)) {
                        Text(
                            conflict.summaryLine(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (conflicts.size > MAX_LISTED) {
                        Text(
                            stringResource(R.string.conflict_and_more, conflicts.size - MAX_LISTED),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        action = {
            // Three choices do not fit across a dialog in Korean, so they
            // stack. Keep-both is first because it is the only one that
            // cannot lose a file.
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onChoose(ConflictChoice.KEEP_BOTH) }) {
                    Text(stringResource(R.string.conflict_keep_both))
                }
                TextButton(onClick = { onChoose(ConflictChoice.OVERWRITE) }) {
                    Text(stringResource(R.string.conflict_overwrite))
                }
                TextButton(onClick = { onChoose(ConflictChoice.SKIP) }) {
                    Text(stringResource(R.string.conflict_skip))
                }
            }
        },
    )
}

@Composable
private fun ConflictDetail(conflict: DownloadConflict) {
    Text(conflict.displayName, style = MaterialTheme.typography.bodyLarge)
    Text(
        stringResource(
            R.string.conflict_remote,
            formatSize(conflict.remoteSize ?: -1).ifBlank { stringResource(R.string.props_unknown) },
            conflict.remoteModifiedMillis?.let(::formatTimestamp)
                ?: stringResource(R.string.props_unknown),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        stringResource(
            R.string.conflict_local,
            formatSize(conflict.localSize),
            formatTimestamp(conflict.localModifiedMillis),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        stringResource(
            if (conflict.sameSize) R.string.conflict_same_size else R.string.conflict_different_size,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DownloadConflict.summaryLine(): String = stringResource(
    if (sameSize) R.string.conflict_line_same else R.string.conflict_line_different,
    displayName,
)

private const val MAX_LISTED = 8
