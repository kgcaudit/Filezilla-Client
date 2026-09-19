package org.filezilla.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The pale chip an artwork icon sits on.
 *
 * Fixed rather than taken from the theme, and that is the point. The artwork
 * is multi-coloured, so it cannot be tinted to suit the background it lands
 * on: against a dark surface its greys and deep blues simply go. Giving it a
 * pale chip in both themes keeps every icon on the background it was drawn
 * for, and costs nothing -- the rows already drew a chip.
 *
 * Darker than it first was, because the file icon is a pale page with three
 * dark lines on it and the first chip was within a shade of the page: the
 * page vanished into the chip, and every file in every listing was drawn as
 * three floating bars that read as a list icon.
 */
val FlatIconChip = Color(0xFFC3D4E6)

/**
 * One of the app's own icons, on the chip that keeps it legible.
 *
 * Use this wherever an icon says what a thing *is* -- a server, a folder, a
 * file. Controls take Material glyphs instead, because a control has to tint
 * with its state and multi-coloured artwork cannot.
 */
@Composable
fun FlatIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    chipSize: Dp = 40.dp,
    cornerRadius: Dp = 11.dp,
) {
    Box(
        modifier = modifier
            .size(chipSize)
            .clip(RoundedCornerShape(cornerRadius))
            .background(FlatIconChip),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(icon),
            contentDescription = contentDescription,
            // Unspecified, or Material would flatten the artwork to one colour.
            tint = Color.Unspecified,
            modifier = Modifier.size(chipSize * 0.6f),
        )
    }
}

/**
 * The same chip, carrying a Material glyph instead of artwork.
 *
 * For the things the app has no drawing of -- a phone's own storage, a memory
 * card. Using the file artwork for those was worse than a plain glyph: it
 * says "file" beside something that is not one, and at chip size its three
 * strokes read as a list.
 *
 * Tinted, unlike [FlatIcon], because a single-colour glyph can be and the
 * chip is pale enough to want it.
 */
@Composable
fun ChipIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    chipSize: Dp = 40.dp,
    cornerRadius: Dp = 11.dp,
    tint: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .size(chipSize)
            .clip(RoundedCornerShape(cornerRadius))
            .background(FlatIconChip),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(chipSize * 0.55f),
        )
    }
}
