package org.filezilla.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.filezilla.android.R
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.journal.TransferState

/**
 * What the whole queue can be asked to do, given what is in it.
 *
 * The transfer list could pause, resume and remove one transfer at a time
 * and nothing at all in bulk: thirty finished downloads had to be dismissed
 * thirty times. The actions themselves are easy; what was missing was any
 * account of *which* of them apply at a given moment, so this is that
 * account, in one place and testable without a screen.
 *
 * Two families, and the split is what the top bar is laid out around:
 *
 *  - **Running** -- make the queue go, or make it stop. One thing at a
 *    time is true of it, so it gets one button whose face says which.
 *  - **Tidying** -- take rows out of the list. Several of these, none
 *    urgent, all needing a word rather than a glyph, so they get a menu.
 *
 * Settings are neither and sit at the foot of that menu.
 */
data class QueueActions(
    /** Something is actually moving, so there is something to stop. */
    val canPause: Boolean,
    /** Something is outstanding and nothing is moving, so it can be set going. */
    val canStart: Boolean,
    val canClearFinished: Boolean,
    val canClearFailed: Boolean,
    /** Anything at all in the list, finished or not. */
    val canClearAll: Boolean,
) {
    /** True when the menu would open on nothing. */
    val hasTidying: Boolean get() = canClearFinished || canClearFailed || canClearAll
}

/**
 * States that mean the queue still has work in hand.
 *
 * `FAILED` counts: "start" is the one button that can put a failed
 * transfer back on its feet, and a list of nothing but failures with no way
 * to retry them all is the case the user would most want the button for.
 * `COMPLETED` does not -- there is nothing left to do to it.
 */
private val OUTSTANDING = setOf(
    TransferState.PENDING,
    TransferState.PAUSED,
    TransferState.INTERRUPTED,
    TransferState.WAITING_FOR_NETWORK,
    TransferState.FAILED,
)

fun queueActionsFor(records: List<TransferRecord>): QueueActions {
    // On RUNNING alone, not on "outstanding". A queue of thirty pending
    // transfers with the service stopped is not running, and offering to
    // pause it would be offering to stop something that is not going.
    val moving = records.any { it.state == TransferState.RUNNING }
    return QueueActions(
        canPause = moving,
        canStart = !moving && records.any { it.state in OUTSTANDING },
        canClearFinished = records.any { it.state == TransferState.COMPLETED },
        canClearFailed = records.any { it.state == TransferState.FAILED },
        canClearAll = records.isNotEmpty(),
    )
}

/**
 * The tidying actions, and the settings, behind the transfer list's menu.
 *
 * Words rather than glyphs, because "remove the finished ones", "remove the
 * failed ones" and "empty the list" are three different things that no
 * three pictures would tell apart -- which is how the old bar came to spend
 * a slot on a broom nobody could read.
 *
 * An action that would do nothing is left out rather than greyed: a menu
 * short enough to read at a glance says more than a long one with half of
 * it dimmed.
 *
 * It owns its own button, because a DropdownMenu hangs off its own parent
 * layout node -- left beside the button in the app bar's row, it would open
 * at the far left of the screen.
 */
@Composable
fun QueueOverflow(
    actions: QueueActions,
    onClearFinished: () -> Unit,
    onClearFailed: () -> Unit,
    onClearAll: () -> Unit,
    onSettings: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (actions.canClearFinished) {
                QueueItem(R.string.queue_clear_finished) {
                    open = false
                    onClearFinished()
                }
            }
            if (actions.canClearFailed) {
                QueueItem(R.string.queue_clear_failed) {
                    open = false
                    onClearFailed()
                }
            }
            if (actions.canClearAll) {
                QueueItem(R.string.queue_clear_all, destructive = true) {
                    open = false
                    onClearAll()
                }
            }
            if (actions.hasTidying) HorizontalDivider()
            QueueItem(R.string.queue_settings) {
                open = false
                onSettings()
            }
        }
    }
}

@Composable
private fun QueueItem(text: Int, destructive: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                stringResource(text),
                // Emptying the list reaches transfers that are still
                // running and the bytes they have already paid for, which
                // is not something the other two entries do.
                color = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        onClick = onClick,
    )
}
