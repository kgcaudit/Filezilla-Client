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

private val Ocean = Color(0xFF0B5FA5)
private val OceanLight = Color(0xFF8FC2F0)
private val Slate = Color(0xFF3F5A73)
private val SlateLight = Color(0xFFB4CAE0)

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
    primary = Ocean,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E7F9),
    onPrimaryContainer = Color(0xFF042C4F),
    inversePrimary = OceanLight,

    secondary = Slate,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6F1),
    onSecondaryContainer = Color(0xFF16283A),

    // A teal, because the third accent has to differ from the first two in
    // hue and not merely in lightness, and green and amber already carry
    // meanings here -- done and paused.
    tertiary = Color(0xFF3E8E98),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFE7EA),
    onTertiaryContainer = Color(0xFF0B2C30),

    // A touch off pure white, so cards can be white and still read as raised.
    background = Color(0xFFF4F6F9),
    onBackground = Color(0xFF161C22),
    surface = Color(0xFFF4F6F9),
    onSurface = Color(0xFF161C22),
    surfaceVariant = Color(0xFFE2E8EF),
    onSurfaceVariant = Color(0xFF44505C),
    surfaceTint = Ocean,

    // The ladder Material lifts a surface up by. A dialog sits on High, a
    // menu on it too, a bottom sheet on Low -- so these are the colours the
    // user sees most often without any screen here naming them.
    surfaceBright = Color(0xFFF9FBFD),
    surfaceDim = Color(0xFFD8DFE8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FC),
    surfaceContainer = Color(0xFFEFF3F8),
    surfaceContainerHigh = Color(0xFFE9EEF5),
    surfaceContainerHighest = Color(0xFFE1E8F1),

    inverseSurface = Color(0xFF2B323A),
    inverseOnSurface = Color(0xFFF1F4F8),

    outline = Color(0xFF75828F),
    outlineVariant = Color(0xFFC6CFD8),
    scrim = Color(0xFF000000),

    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

internal val DarkColors = darkColorScheme(
    primary = OceanLight,
    onPrimary = Color(0xFF002F52),
    primaryContainer = Color(0xFF004574),
    onPrimaryContainer = Color(0xFFD1E4FF),
    inversePrimary = Ocean,

    secondary = SlateLight,
    onSecondary = Color(0xFF213546),
    secondaryContainer = Color(0xFF374C5E),
    onSecondaryContainer = Color(0xFFD5E3F3),

    tertiary = Color(0xFF8FD2DA),
    onTertiary = Color(0xFF0B2C30),
    tertiaryContainer = Color(0xFF2B5A60),
    onTertiaryContainer = Color(0xFFCFE7EA),

    background = Color(0xFF11161B),
    onBackground = Color(0xFFE2E6EA),
    surface = Color(0xFF11161B),
    onSurface = Color(0xFFE2E6EA),
    surfaceVariant = Color(0xFF3F4850),
    onSurfaceVariant = Color(0xFFBFC8D1),
    surfaceTint = OceanLight,

    surfaceBright = Color(0xFF363E46),
    surfaceDim = Color(0xFF0E1318),
    surfaceContainerLowest = Color(0xFF0B0F13),
    surfaceContainerLow = Color(0xFF161C22),
    surfaceContainer = Color(0xFF1A2127),
    surfaceContainerHigh = Color(0xFF242C33),
    surfaceContainerHighest = Color(0xFF2E373F),

    inverseSurface = Color(0xFFE2E6EA),
    inverseOnSurface = Color(0xFF11161B),

    outline = Color(0xFF89929B),
    outlineVariant = Color(0xFF3F4850),
    scrim = Color(0xFF000000),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
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
    folder = Ocean,
    archive = Color(0xFF8A6A46),
    image = Color(0xFF2E8B6B),
    video = Color(0xFF5B57A8),
    audio = Color(0xFFB0602A),
    document = Color(0xFF4A6273),
    code = Color(0xFF3E8E98),
    app = Color(0xFF2E7D4F),
    other = Color(0xFF6B7885),
)

/**
 * Lighter in the dark theme, not darker.
 *
 * A tile is a filled shape carrying white, so on a dark background it has to
 * stay light enough for the white to read -- the usual instinct of darkening
 * every colour for a dark theme would leave white on near-black on black.
 */
internal val DarkTiles = TileColors(
    folder = Color(0xFF2E7FC2),
    archive = Color(0xFFA5825C),
    image = Color(0xFF3FA383),
    video = Color(0xFF7671C4),
    audio = Color(0xFFC97B44),
    document = Color(0xFF61798B),
    code = Color(0xFF52A5AF),
    app = Color(0xFF429568),
    other = Color(0xFF828F9C),
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
    // Deliberately not the primary colour, which it used to be exactly. With
    // the same value, a blue progress bar under a blue app bar left blue
    // meaning both "this app" and "this is moving", and neither clearly. They
    // are now near relations with separate jobs: primary marks what you can
    // press, this marks what is happening.
    running = Color(0xFF1273BE),
    waiting = Color(0xFF6B7885),
    paused = Color(0xFFB26A00),
    done = Color(0xFF1B7F4B),
    failed = Color(0xFFB3261E),
    progressTrack = Color(0xFFC9D6E3),
)

internal val DarkStatus = StatusColors(
    running = Color(0xFF7FC0F5),
    waiting = Color(0xFF98A4B0),
    paused = Color(0xFFFFB95C),
    done = Color(0xFF6FD79B),
    failed = Color(0xFFF2B8B5),
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
