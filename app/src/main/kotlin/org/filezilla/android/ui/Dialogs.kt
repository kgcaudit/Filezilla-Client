package org.filezilla.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.filezilla.android.R

/**
 * The shape every dialog in this app takes.
 *
 * There were nine of them and no two agreed: two carried an icon and seven
 * did not, two offered a way out and seven did not, and the same question was
 * asked by three separate implementations that had drifted apart. That is not
 * only untidy -- it is where the bugs live. Asking for a new file showed the
 * *site editor's* hint in its name box, because one of the two text prompts
 * had been written with a hard-coded label and the other had not; the fix
 * landed in the one nobody used.
 *
 * So the shell is stated once. A dialog names itself, may explain itself, may
 * carry a form, and offers one action to take and one way out. The variants
 * below are the two questions this app actually asks; anything richer passes
 * its own [content].
 *
 * Every dialog offers a way out. The exception is one whose only action is
 * to close it, which is its own way out -- and that is the only case where
 * [dismissLabel] may be null.
 */
@Composable
fun OloDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** The sentence under the title. Present tense, and about consequences. */
    detail: String? = null,
    dismissLabel: String? = stringResource(R.string.action_cancel),
    content: (@Composable () -> Unit)? = null,
    /** What the dialog is for. One button, usually; three, for a conflict. */
    action: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title) },
        text = if (detail == null && content == null) {
            null
        } else {
            {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    detail?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    content?.invoke()
                }
            }
        },
        confirmButton = action,
        dismissButton = dismissLabel?.let {
            { TextButton(onClick = onDismiss) { Text(it) } }
        },
    )
}

/**
 * The button that does the thing there is no undo for.
 *
 * A confirmation puts two buttons side by side and exactly one of them can be
 * taken back, so the two cannot look alike -- and they did. Every TextButton
 * draws itself in the primary colour, which was a blue while the error colour
 * was a red, so the destructive one stood out for free. The brand is a warm
 * clay now, near enough to red that "Delete" and "Cancel" came up the same
 * shade: the irreversible action wearing what reads as a warning, and the
 * safe one wearing it too.
 */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text(text) }
}

/**
 * Asking for a name.
 *
 * The one text prompt in the app. There were two, identical but for the one
 * thing that mattered: this one takes the label of the box it is drawing,
 * where the other hard-coded it.
 */
@Composable
fun OloPromptDialog(
    @StringRes title: Int,
    @StringRes label: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initial: String = "",
    /** What the title names, for the titles that name something. */
    titleArg: String? = null,
    @StringRes detail: Int? = null,
    @StringRes confirmLabel: Int = R.string.action_ok,
) {
    var text by remember { mutableStateOf(initial) }
    OloDialog(
        title = if (titleArg == null) stringResource(title) else stringResource(title, titleArg),
        detail = detail?.let { stringResource(it) },
        onDismiss = onDismiss,
        content = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(label)) },
                singleLine = true,
            )
        },
        action = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                // A blank name is not a name, and the operation would refuse
                // it anyway -- better to not offer than to offer and fail.
                enabled = text.isNotBlank(),
            ) { Text(stringResource(confirmLabel)) }
        },
    )
}

/** Asking before something that cannot be undone. */
@Composable
fun OloConfirmDialog(
    title: String,
    detail: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OloDialog(
        title = title,
        detail = detail,
        onDismiss = onDismiss,
        action = { DangerButton(confirmLabel, onConfirm) },
    )
}

/**
 * Showing something, with nothing to decide.
 *
 * Its single button closes it, so it carries no second one: "Close" beside
 * "Cancel" asks the reader to work out which of two words means the same
 * thing.
 */
@Composable
fun OloInfoDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    OloDialog(
        title = title,
        onDismiss = onDismiss,
        dismissLabel = null,
        content = content,
        action = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
