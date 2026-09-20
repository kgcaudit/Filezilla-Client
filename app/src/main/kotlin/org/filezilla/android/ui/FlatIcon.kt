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
import org.filezilla.android.ui.theme.tiles

/**
 * The pale chip an artwork icon sits on.
 *
 * Fixed rather than taken from the theme, and that is the point. The artwork
 * is multi-coloured, so it cannot be tinted to suit the background it lands
 * on: against a dark surface its greys and deep blues simply go. Giving it a
 * pale chip in both themes keeps every icon on the background it was drawn
 * for, and costs nothing -- the rows already drew a chip.
 *
 * White, because that is the background the artwork was drawn against. The
 * first chip was a pale blue-grey within a shade of the file icon's own page:
 * the page vanished into it, and every file in every listing was drawn as
 * three floating bars that read as a list icon. Darkening the chip fixed that
 * and spoiled the rest -- the copy icon's second sheet went the same way --
 * because the problem was never the chip's darkness. It was that the artwork
 * assumes white behind it, and anything else is a fight with it.
 */
val FlatIconChip = Color(0xFFFFFFFF)

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
 * A row's icon: a filled tile with the shape cut out of it in white.
 *
 * What it replaces put a 24dp drawing on a 40dp pale chip -- a third of the
 * tile, legible if you looked at it and not if you scanned past it, and the
 * same drawing for every file whatever the file was. The tile is the icon
 * now: the colour says what kind of thing this is before the name is read,
 * and the glyph fills it.
 */
@Composable
fun FileTile(
    kind: FileKind,
    colour: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    cornerRadius: Dp = 12.dp,
) = TileIcon(kind.glyph, colour, contentDescription, modifier, size, cornerRadius)

/**
 * The same tile for the things that are not files -- a volume, a memory card,
 * a server, a padlock.
 *
 * One shape for every icon that names a thing, so a row of storage and a row
 * of files look like the same app rather than two.
 */
@Composable
fun TileIcon(
    @DrawableRes glyph: Int,
    colour: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    cornerRadius: Dp = 12.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(colour),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(glyph),
            contentDescription = contentDescription,
            // Unspecified, because the glyph is already white -- and part of
            // it is white at reduced alpha, which is what keeps a document's
            // page distinct from the lines on it. A tint would flatten the
            // two back together.
            tint = Color.Unspecified,
            modifier = Modifier.size(size * 0.58f),
        )
    }
}

/** The tile colour this kind is always drawn in. */
@Composable
fun colourFor(kind: FileKind): Color = with(androidx.compose.material3.MaterialTheme.tiles) {
    when (kind) {
        FileKind.FOLDER -> folder
        FileKind.ARCHIVE -> archive
        FileKind.IMAGE -> image
        FileKind.VIDEO -> video
        FileKind.AUDIO -> audio
        FileKind.DOCUMENT -> document
        FileKind.CODE -> code
        FileKind.APP -> app
        FileKind.OTHER -> other
    }
}
