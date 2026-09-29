package org.filezilla.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.filezilla.android.R
import org.filezilla.android.files.SyncAction

/** How many rows of the plan to list before "and N more" takes over. */
private const val MAX_LISTED = 8

/**
 * The mirror, laid out for the user to look at before it runs.
 *
 * Everything destructive about folder sync is decided on this screen: which way
 * it goes, what it will copy, and -- only if the switch is turned on -- what it
 * will delete. So the dialog shows the whole plan and its cost up front, marks
 * the deletions in the error colour the moment they are switched on, and does
 * nothing until the user presses the one button that starts it. While it runs it
 * turns into a stop: a mirror already moving is called off here, not by a tap
 * outside.
 */
@Composable
fun SyncPreviewDialog(
    state: SyncState,
    onToggleDelete: (Boolean) -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state.running) {
        OloDialog(
            title = stringResource(R.string.sync_running),
            onDismiss = {},
            dismissLabel = null,
            // A stray touch or the back gesture must not be read as "stop": the
            // stop button below is the only way to call a running mirror off.
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    Text(
                        stringResource(R.string.sync_direction, state.source.label, state.target.label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            },
            action = { ConfirmButton(text = stringResource(R.string.action_stop), onClick = onStop) },
        )
        return
    }

    val plan = state.plan
    OloDialog(
        title = stringResource(R.string.sync_title),
        onDismiss = onDismiss,
        detail = stringResource(R.string.sync_direction, state.source.label, state.target.label),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sync_copy_count, plan.copyCount), style = MaterialTheme.typography.bodyMedium)
                if (plan.makeDirCount > 0) {
                    Text(stringResource(R.string.sync_make_count, plan.makeDirCount), style = MaterialTheme.typography.bodyMedium)
                }
                if (plan.skipCount > 0) {
                    Text(
                        stringResource(R.string.sync_skip_count, plan.skipCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.deleteExtras && plan.deleteCount > 0) {
                    Text(
                        stringResource(R.string.sync_delete_count, plan.deleteCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                PlanList(plan.actions, showDeletes = state.deleteExtras)

                // The one destructive switch, off to begin with. Its own line
                // says what it does and that it cannot be undone.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = state.deleteExtras, onClick = { onToggleDelete(!state.deleteExtras) })
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = state.deleteExtras, onCheckedChange = onToggleDelete)
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text(stringResource(R.string.sync_delete_extras))
                        Text(
                            stringResource(R.string.sync_delete_extras_detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // What the mirror could not do cleanly, said rather than hidden.
                if (plan.conflicts.isNotEmpty()) {
                    Note(stringResource(R.string.sync_conflicts_note, plan.conflicts.size))
                }
                if (state.skippedLinks > 0) {
                    Note(stringResource(R.string.sync_links_note, state.skippedLinks))
                }
                if (state.truncated) {
                    Note(stringResource(R.string.sync_truncated_note))
                }
                if (plan.isNoop) {
                    Text(
                        stringResource(R.string.sync_nothing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        action = {
            ConfirmButton(
                text = stringResource(R.string.sync_run),
                onClick = onRun,
                enabled = !plan.isNoop,
                // Reaches for the error colour once it will delete, so the
                // button matches what turning the switch on made it do.
                destructive = state.deleteExtras && plan.deleteCount > 0,
            )
        },
    )
}

/** A capped, scrollable look at what the plan will actually touch. */
@Composable
private fun PlanList(actions: List<SyncAction>, showDeletes: Boolean) {
    val shown = actions.filter { it !is SyncAction.Delete || showDeletes }
    if (shown.isEmpty()) return
    Column(
        modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (action in shown.take(MAX_LISTED)) {
            val (text, colour) = when (action) {
                is SyncAction.MakeDir -> "${action.rel}/" to MaterialTheme.colorScheme.onSurfaceVariant
                is SyncAction.Copy -> action.rel to MaterialTheme.colorScheme.onSurface
                is SyncAction.Delete -> action.rel to MaterialTheme.colorScheme.error
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = colour,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (shown.size > MAX_LISTED) {
            Text(
                stringResource(R.string.sync_more, shown.size - MAX_LISTED),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
