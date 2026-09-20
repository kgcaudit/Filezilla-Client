package org.filezilla.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The app's colours, defined rather than taken from the wallpaper.
 *
 * Material You's dynamic colour was tried first and produced a grey card on a
 * grey background with a thin blue line through it: a transfer list where the
 * most important thing on screen -- how far along a transfer is -- was the
 * lowest-contrast element. A file transfer client is a utility people read at
 * a glance, so the palette is fixed and the contrast is chosen.
 *
 * The status colours below carry meaning, so they are defined once here rather
 * than picked per screen: the same amber always means paused, wherever it
 * appears.
 */

/**
 * Claude's clay, which is what the user asked the app to be built around.
 *
 * It was Ocean, a deep blue, and the whole palette hung off it: the surfaces
 * were cool greys, the icons were recoloured to it, the launcher was a blue
 * field. Moving the brand means moving all of that, because a warm accent on
 * cool grey looks like a mistake rather than a choice -- so the neutrals are
 * warm now too, an ivory rather than a blue-grey.
 */
private val Clay = Color(0xFFC5613F)
private val ClayLight = Color(0xFFE8A183)
private val Stone = Color(0xFF6E5C50)
private val StoneLight = Color(0xFFD6C3B4)

/*
 * Every role, set.
 *
 * Not thoroughness for its own sake: Material fills a role this theme leaves
 * alone from its own baseline palette, which is purple. The app never names
 * surfaceContainerHigh anywhere -- but every dialog, menu and sheet does, so
 * a confirmation asking whether to delete a file came up lavender on a screen
 * that is otherwise blue-grey. The same hole once made the paste bar pink.
 * An unset role is not an omission; it is somebody else's colour.
 */
internal val LightColors = lightColorScheme(
    primary = Clay,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E0D6),
    onPrimaryContainer = Color(0xFF4A1E0C),
    inversePrimary = ClayLight,

    secondary = Stone,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFE6DE),
    onSecondaryContainer = Color(0xFF2A211B),

    // A teal, because the third accent has to differ from the first two in
    // hue and not merely in lightness, and green and amber already carry
    // meanings here -- done and paused.
    tertiary = Color(0xFF3E7F80),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDE5E4),
    onTertiaryContainer = Color(0xFF0A2C2C),

    // A touch off pure white, so cards can be white and still read as raised.
    background = Color(0xFFF7F4EF),
    onBackground = Color(0xFF1D1A16),
    surface = Color(0xFFF7F4EF),
    onSurface = Color(0xFF1D1A16),
    surfaceVariant = Color(0xFFECE5DC),
    onSurfaceVariant = Color(0xFF574D45),
    surfaceTint = Clay,

    // The ladder Material lifts a surface up by. A dialog sits on High, a
    // menu on it too, a bottom sheet on Low -- so these are the colours the
    // user sees most often without any screen here naming them.
    surfaceBright = Color(0xFFFBF9F5),
    surfaceDim = Color(0xFFDFD8CC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCFAF6),
    surfaceContainer = Color(0xFFF3EFE8),
    surfaceContainerHigh = Color(0xFFEDE8DF),
    surfaceContainerHighest = Color(0xFFE7E1D6),

    inverseSurface = Color(0xFF35302A),
    inverseOnSurface = Color(0xFFF5F1EA),

    outline = Color(0xFF8B7F74),
    outlineVariant = Color(0xFFD6CCC1),
    scrim = Color(0xFF000000),

    // A crimson, not the orange-red Material ships.
    //
    // Material's error is hue 3 and the brand is hue 15, which was no trouble
    // at all while the brand was a blue: an error stood out because it was the
    // only warm thing on screen. Against clay the two are neighbours, so a
    // delete button drawn in error and a cancel button drawn in primary came
    // up the same shade. Pulled round past red into crimson, and deepened,
    // so danger is told from brand by hue and by weight rather than by neither.
    error = Color(0xFFA50E2E),
    onError = Color.White,
    errorContainer = Color(0xFFFBDCE1),
    onErrorContainer = Color(0xFF3F0313),
)

internal val DarkColors = darkColorScheme(
    primary = ClayLight,
    onPrimary = Color(0xFF4A1E0C),
    primaryContainer = Color(0xFF8A3E22),
    onPrimaryContainer = Color(0xFFFBE0D4),
    inversePrimary = Clay,

    secondary = StoneLight,
    onSecondary = Color(0xFF2A211B),
    secondaryContainer = Color(0xFF52443A),
    onSecondaryContainer = Color(0xFFEFE0D4),

    tertiary = Color(0xFF86CFCF),
    onTertiary = Color(0xFF08292A),
    tertiaryContainer = Color(0xFF2A5455),
    onTertiaryContainer = Color(0xFFCDE5E4),

    background = Color(0xFF181613),
    onBackground = Color(0xFFE8E3DA),
    surface = Color(0xFF181613),
    onSurface = Color(0xFFE8E3DA),
    surfaceVariant = Color(0xFF49423A),
    onSurfaceVariant = Color(0xFFCFC5B9),
    surfaceTint = ClayLight,

    surfaceBright = Color(0xFF3D362E),
    surfaceDim = Color(0xFF141210),
    surfaceContainerLowest = Color(0xFF0F0E0B),
    surfaceContainerLow = Color(0xFF1C1A16),
    surfaceContainer = Color(0xFF211E1A),
    surfaceContainerHigh = Color(0xFF2B2723),
    surfaceContainerHighest = Color(0xFF363029),

    inverseSurface = Color(0xFFE8E3DA),
    inverseOnSurface = Color(0xFF181613),

    outline = Color(0xFF978C80),
    outlineVariant = Color(0xFF49423A),
    scrim = Color(0xFF000000),

    error = Color(0xFFFFB0BE),
    onError = Color(0xFF5E001C),
    errorContainer = Color(0xFF8C0F30),
    onErrorContainer = Color(0xFFFFD9E0),
)

/**
 * The colour of a row's tile, one per kind of file.
 *
 * Defined here beside the status colours and for the same reason: these carry
 * meaning, so the same blue always means a folder wherever it appears, and
 * none of them is picked at the point of use. Held apart from the Material
 * scheme because they are not Material roles -- putting them there would mean
 * bending roles that already have jobs.
 */
data class TileColors(
    val folder: Color,
    val archive: Color,
    val image: Color,
    val video: Color,
    val audio: Color,
    val document: Color,
    val code: Color,
    val app: Color,
    val other: Color,
)

val MaterialTheme.tiles: TileColors
    @Composable
    get() = if (colorScheme.background.luminanceIsDark()) DarkTiles else LightTiles

internal val LightTiles = TileColors(
    folder = Clay,
    // Pulled apart from each other by hue, not by lightness: nine tiles in a
    // list are told apart at a glance or not at all, and a warm brand means
    // the warm half of the wheel is busier than it was. Audio moved to a rose
    // and archive to an ochre for exactly that -- both were browns sitting
    // next to a clay folder.
    archive = Color(0xFF8A6A3B),
    image = Color(0xFF2E8B6B),
    video = Color(0xFF6A5A9E),
    audio = Color(0xFFB04A6A),
    document = Color(0xFF55606B),
    code = Color(0xFF3E7F80),
    app = Color(0xFF4C7A3E),
    other = Color(0xFF7A7168),
)

/**
 * Lighter in the dark theme, not darker.
 *
 * A tile is a filled shape carrying white, so on a dark background it has to
 * stay light enough for the white to read -- the usual instinct of darkening
 * every colour for a dark theme would leave white on near-black on black.
 */
internal val DarkTiles = TileColors(
    folder = Color(0xFFD1734F),
    archive = Color(0xFFB08A54),
    image = Color(0xFF3FA383),
    video = Color(0xFF8A7AC0),
    audio = Color(0xFFC96B88),
    document = Color(0xFF6E7A86),
    code = Color(0xFF55A0A1),
    app = Color(0xFF69985A),
    other = Color(0xFF938A80),
)

/** Colours that mean something, rather than colours that merely differ. */
data class StatusColors(
    val running: Color,
    val waiting: Color,
    val paused: Color,
    val done: Color,
    val failed: Color,
    /** The unfilled part of a progress bar; visible, not invisible. */
    val progressTrack: Color,
)

val MaterialTheme.status: StatusColors
    @Composable
    get() = if (colorScheme.background.luminanceIsDark()) DarkStatus else LightStatus

internal val LightStatus = StatusColors(
    // Cool, and deliberately so now that the brand is warm: primary marks
    // what you can press and this marks what is happening, and the surest way
    // to keep those apart is to put them on opposite sides of the wheel. It
    // was a near relation of a blue brand, which asked the eye to tell two
    // blues apart.
    running = Color(0xFF1273BE),
    waiting = Color(0xFF7A6F65),
    // Yellower than it was. Against a clay primary the old amber was a near
    // miss -- close enough to read as the brand rather than as a state.
    paused = Color(0xFF9C7A0C),
    done = Color(0xFF1B7F4B),
    failed = Color(0xFF9E0C2B),
    progressTrack = Color(0xFFDCD3C6),
)

internal val DarkStatus = StatusColors(
    running = Color(0xFF7FC0F5),
    waiting = Color(0xFFA79C90),
    paused = Color(0xFFE0BE52),
    done = Color(0xFF6FD79B),
    failed = Color(0xFFFFB0BE),
    progressTrack = Color(0xFF39434D),
)

private fun Color.luminanceIsDark(): Boolean = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f

@Composable
fun OloTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // The app bar is the surface colour now, not the brand's. Light
            // system icons were right when it was deep blue and would be
            // invisible on it today, so they follow the theme instead.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = OloShapes,
        typography = OloTypography,
        content = content,
    )
}

/**
 * Corners, a shade tighter than Material's.
 *
 * Material rounds a dialog by 28dp, which next to this app's 10-to-12dp rows,
 * chips and icon tiles reads as a different piece of software dropped on top
 * of it. The ladder below keeps the same steps and pulls the large end in
 * until a dialog looks like it came from here.
 */
internal val OloShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/**
 * The type scale, with the top of it brought down.
 *
 * A dialog titles itself in headlineSmall, 24sp, while the screen behind it
 * titles a pane in titleSmall at 14 -- so a question about deleting one file
 * arrived in letters nearly twice the size of anything around it. The body
 * sizes are Material's, because those are the ones that were already right.
 */
internal val OloTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontSize = 28.sp, lineHeight = 34.sp),
        headlineMedium = base.headlineMedium.copy(fontSize = 24.sp, lineHeight = 30.sp),
        headlineSmall = base.headlineSmall.copy(fontSize = 20.sp, lineHeight = 26.sp),
        titleLarge = base.titleLarge.copy(fontSize = 19.sp, lineHeight = 25.sp),
    )
}
