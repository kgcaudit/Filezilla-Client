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
 */
// The decoration box is what lets the padding be named at all; the
// high-level OutlinedTextField does not expose it.
@OptIn(ExperimentalMaterial3Api::class)
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
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = OutlinedTextFieldDefaults.colors()
    val textStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodyLarge)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = minHeight),
        singleLine = true,
        readOnly = readOnly,
        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
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
                // Ten instead of sixteen. Below about eight the floating
                // label starts to sit on the frame it is supposed to break.
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
        },
    )
}
