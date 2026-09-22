package org.filezilla.android.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's text box, a good deal shorter than Material's.
 *
 * Material gives an outlined field a 56dp floor and sixteen points of air
 * above and below the text. On a phone that is a lot of box around one short
 * word: a dialog asking for a folder name spent more height on the space
 * around the name than on everything else it said. The site editor pays it
 * six times over and had to be made scrollable partly because of it.
 *
 * So the padding comes down and the floor with it, to the 48dp that is the
 * smallest thing a finger should be asked to hit. Nothing else changes --
 * the frame, the floating label and the focus behaviour are Material's own
 * decoration box, which is why this is built on that rather than by drawing
 * a border around a BasicTextField and reinventing the parts that already
 * work.
 *
 * There are two of these: one over a plain string, for the boxes that just
 * take a word, and one over a [TextFieldValue], for the rename prompt that
 * needs to open with the whole name selected. They share their frame; only
 * the state they carry differs.
 */
@Composable
fun OloTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /** For the boxes that only open a menu, which cannot be typed into. */
    readOnly: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null,
    /**
     * The touches that open a menu, when this box is a picker's face.
     *
     * A picker anchors its menu to the box and needs the box's own
     * interactions; letting this field make a private one leaves the menu
     * with nothing to listen to.
     */
    interactions: MutableInteractionSource? = null,
    minHeight: Dp = 48.dp,
) {
    val own = remember { MutableInteractionSource() }
    val interactionSource = interactions ?: own
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = minHeight),
        singleLine = true,
        readOnly = readOnly,
        textStyle = oloTextStyle(),
        cursorBrush = oloCursor(),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        decorationBox = { inner ->
            OloDecoration(
                text = value,
                inner = inner,
                label = label,
                placeholder = placeholder,
                supportingText = supportingText,
                isError = isError,
                visualTransformation = visualTransformation,
                interactionSource = interactionSource,
                trailingIcon = trailingIcon,
            )
        },
    )
}

/**
 * The same box over a [TextFieldValue], so the caller owns the selection.
 *
 * The rename prompt opens with the whole name selected -- a long name was
 * otherwise impossible to replace, because the cursor landed at the end and
 * the field scrolled the rest of it off the left where a finger could not
 * reach to move the cursor back. Holding the selection means the name comes up
 * highlighted and one keystroke replaces it, or a tap drops in to edit.
 */
@Composable
fun OloTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    supportingText: String? = null,
    minHeight: Dp = 48.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = minHeight),
        singleLine = true,
        textStyle = oloTextStyle(),
        cursorBrush = oloCursor(),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interactionSource,
        decorationBox = { inner ->
            OloDecoration(
                text = value.text,
                inner = inner,
                label = label,
                placeholder = null,
                supportingText = supportingText,
                isError = isError,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                trailingIcon = null,
            )
        },
    )
}

@Composable
private fun oloTextStyle() =
    LocalTextStyle.current.merge(MaterialTheme.typography.bodyLarge)
        .copy(color = MaterialTheme.colorScheme.onSurface)

@Composable
private fun oloCursor() =
    androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)

// The decoration box is what lets the padding be named at all; the
// high-level OutlinedTextField does not expose it. Shared by both boxes so
// they wear exactly the same frame.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OloDecoration(
    text: String,
    inner: @Composable () -> Unit,
    label: String,
    placeholder: String?,
    supportingText: String?,
    isError: Boolean,
    visualTransformation: VisualTransformation,
    interactionSource: MutableInteractionSource,
    trailingIcon: (@Composable () -> Unit)?,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = OutlinedTextFieldDefaults.colors()
    OutlinedTextFieldDefaults.DecorationBox(
        value = text,
        innerTextField = inner,
        enabled = true,
        singleLine = true,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        isError = isError,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        trailingIcon = trailingIcon,
        colors = colors,
        // Ten instead of sixteen. Below about eight the floating label starts
        // to sit on the frame it is supposed to break.
        contentPadding = OutlinedTextFieldDefaults.contentPadding(
            top = 10.dp,
            bottom = 10.dp,
        ),
        container = {
            OutlinedTextFieldDefaults.Container(
                enabled = true,
                isError = isError,
                interactionSource = interactionSource,
                colors = colors,
                focusedBorderThickness = if (focused) 2.dp else 1.dp,
            )
        },
    )
}
