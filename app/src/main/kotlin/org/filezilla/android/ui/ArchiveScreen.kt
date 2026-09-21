package org.filezilla.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.filezilla.android.R

/** What the screen is looking at and what has been chosen in it. */
data class ArchiveBusy(
    val title: String,
    val path: String,
    /** Bytes done, or a file count when [bytes] is false. */
    val done: Long,
    val total: Long,
    val onStop: () -> Unit,
    /** True when [done]/[total] are bytes, so the line reads as a size. */
    val bytes: Boolean = false,
)

/**
 * Asking for an archive's password, once, before anything is unpacked.
 *
 * At the front rather than part way through: a question that appears
 * behind a progress bar somebody has stopped watching is a question that
 * goes unanswered, and the unpack then quietly leaves out every locked
 * file.
 */
@Composable
fun ArchivePasswordDialog(
    wrong: Boolean,
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    OloDialog(
        title = stringResource(R.string.archive_password_title),
        detail = stringResource(R.string.archive_password_detail),
        onDismiss = onDismiss,
        content = {
            OloTextField(
                value = typed,
                onValueChange = { typed = it },
                label = stringResource(R.string.archive_password_title),
                isError = wrong,
                supportingText = if (wrong) stringResource(R.string.archive_password_wrong) else null,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                ),
            )
        },
        action = {
            ConfirmButton(
                text = stringResource(R.string.archive_open),
                onClick = { onSubmit(typed.toCharArray()) },
                enabled = typed.isNotEmpty(),
            )
        },
    )
}

/**
 * What a compress shows, since it has no archive screen behind it.
 *
 * The same words and the same stop button wherever the work is
 * started -- extract, compress, or opening one file from inside.
 */
@Composable
fun ArchiveWorkDialog(busy: ArchiveBusy) {
    OloDialog(
        title = busy.title,
        onDismiss = busy.onStop,
        dismissLabel = null,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (busy.path.isNotEmpty()) {
                    Text(
                        busy.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (busy.total > 0) {
                    Text(
                        if (busy.bytes) {
                            "${formatSize(busy.done)} / ${formatSize(busy.total)}"
                        } else {
                            stringResource(R.string.work_counted, busy.done.toInt(), busy.total.toInt())
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    // Determinate when a total is known -- so a single huge
                    // file's bar creeps rather than sitting at zero -- and
                    // indeterminate when the archive did not say its sizes.
                    progress = { if (busy.total > 0) busy.done.toFloat() / busy.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        action = {
            TextButton(onClick = busy.onStop) { Text(stringResource(R.string.action_stop)) }
        },
    )
}


/**
 * The wait while an archive's index is read.
 *
 * Not dismissable and with no button: it is gone the instant the list is
 * ready or the failure is shown, and a cancel here would race the open
 * for no gain -- reading an index is quick even when it is not instant.
 */
@Composable
fun ArchiveOpeningDialog(name: String) {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 3.dp,
                )
                Text(
                    stringResource(R.string.archive_opening, name),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}
