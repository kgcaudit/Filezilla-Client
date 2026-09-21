package org.filezilla.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
                // pushing the buttons off the screen -- but only when there
                // is a list. One file is four short lines, and the cap was
                // cutting the last of them in half: the line that says
                // whether the two are the same file, which is the one the
                // choice below actually turns on.
                modifier = Modifier
                    .then(
                        if (conflicts.size == 1) {
                            Modifier
                        } else {
                            Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                        },
                    ),
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
        // Its own, at the foot of the stack: see [OloDialog]. In the slot
        // Material puts it in, it sat halfway up the side of the choices,
        // reading as a fourth option that had drifted out of line.
        dismissLabel = null,
        action = {
            // Three choices do not fit across a dialog in Korean, so they
            // stack. They were four identical clay words flush right, and
            // one of them replaces a file that cannot be got back -- which
            // looked exactly like the one that does nothing.
            //
            // Full width, each with a line under it saying what it does to
            // the file, and the destructive one in the danger colour. Read
            // top to bottom the order is safest first.
            Column(modifier = Modifier.fillMaxWidth()) {
                Choice(
                    label = R.string.conflict_keep_both,
                    detail = R.string.conflict_keep_both_detail,
                    onClick = { onChoose(ConflictChoice.KEEP_BOTH) },
                )
                Choice(
                    label = R.string.conflict_skip,
                    detail = R.string.conflict_skip_detail,
                    onClick = { onChoose(ConflictChoice.SKIP) },
                )
                Choice(
                    label = R.string.conflict_overwrite,
                    detail = R.string.conflict_overwrite_detail,
                    destructive = true,
                    onClick = { onChoose(ConflictChoice.OVERWRITE) },
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

/**
 * One answer, with the sentence that says what it costs.
 *
 * The word alone is not enough here. "Overwrite" and "Skip" are both four
 * letters of clay on the same line, and the difference between them is a
 * file the user may never get back -- so each one says, underneath, what
 * happens to the file.
 */
@Composable
private fun Choice(
    label: Int,
    detail: Int,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val ink = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = ink,
        )
        Text(
            stringResource(detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConflictDetail(conflict: DownloadConflict) {
    // The file it is about, as the listing draws it.
    FileHeading(conflict.displayName, isDirectory = false)
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
