package org.filezilla.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
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
 * Every dialog offers a way out. [dismissLabel] may be null only when the
 * dialog already provides one: a dialog whose single action is to close it,
 * or one whose choices are stacked and carry their own last line. Material
 * lays the two button slots out side by side, so a tall stack in one of them
 * leaves the other floating alongside, and the way out stops looking like
 * part of the question.
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
            {
                // Quiet, on purpose. Every TextButton draws itself in the
                // brand colour, so the way out was exactly as loud as the
                // thing the dialog is for -- two clay words side by side
                // and nothing saying which one the dialog is asking for.
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text(it) }
            }
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
 * The button a dialog is asking for.
 *
 * Bold, because the way out beside it is quiet: between them the pair says
 * which one answers the question. Not a filled button -- a dialog with one
 * filled button and one word beside it reads as a form, and most of these
 * are questions.
 */
@Composable
fun ConfirmButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    /**
     * For the rare confirm that is not the safe answer.
     *
     * The confirming button is normally the one to reach for, and looks it.
     * Where it is the one to think twice about -- accepting a certificate
     * that has changed underneath a server that was already trusted -- it
     * should not be wearing the colour that means "this is fine".
     */
    destructive: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = if (destructive) {
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.textButtonColors()
        },
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The name of a group of settings or of facts.
 *
 * One treatment, stated once. There were three: the view options and the
 * sheets used the brand colour, the properties dialog used muted grey at a
 * smaller size, and the site editor had no headings at all -- so nine
 * fields ran together in one stack with nothing saying that three of them
 * were about where the server is and two about who you are on it.
 */
@Composable
fun SectionLabel(@StringRes text: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/**
 * The file a dialog is about, drawn as the row it was opened from.
 *
 * A dialog that names a file in plain text asks the reader to match a
 * string against the row they just tapped. The same tile and the same name
 * as the listing answers that before it is asked -- and it is the only
 * thing on these dialogs that says *which* file at a glance.
 */
@Composable
fun FileHeading(name: String, isDirectory: Boolean, modifier: Modifier = Modifier) {
    val kind = remember(name, isDirectory) { kindOf(name, isDirectory) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        FileTile(kind = kind, colour = colourFor(kind), contentDescription = null)
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
    }
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
    // Keyed on what it started from. Unkeyed, a dialog reopened on a
    // different entry keeps the first one's name in the box, because the
    // same composable is reused and remember has nothing to tell it that
    // the question changed. Opened with the whole name selected, so a long
    // one can be replaced in a keystroke rather than fought with a cursor
    // that lands at the end and a field that scrolls the rest out of reach.
    var value by remember(initial) {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    val focusRequester = remember { FocusRequester() }
    // The selection only shows once the box has focus, and the prompt is here
    // to be typed into, so it takes focus itself rather than waiting for a tap.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val name = value.text.trim()
    OloDialog(
        title = if (titleArg == null) stringResource(title) else stringResource(title, titleArg),
        detail = detail?.let { stringResource(it) },
        onDismiss = onDismiss,
        content = {
            OloTextField(
                value = value,
                onValueChange = { value = it },
                label = stringResource(label),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                // The keyboard's own key finishes the job. Without this it
                // says "next" and moves to nothing, so the only way to
                // confirm a name is to put the keyboard away first.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (name.isNotBlank()) onConfirm(name) },
                ),
            )
        },
        action = {
            ConfirmButton(
                text = stringResource(confirmLabel),
                onClick = { onConfirm(name) },
                // A blank name is not a name, and the operation would refuse
                // it anyway -- better to not offer than to offer and fail.
                enabled = name.isNotBlank(),
            )
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
            ConfirmButton(stringResource(R.string.action_close), onDismiss)
        },
    )
}
