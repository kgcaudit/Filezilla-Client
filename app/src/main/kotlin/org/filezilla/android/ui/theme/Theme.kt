package org.filezilla.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

private val LightColors = lightColorScheme(
    primary = Ocean,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E7F9),
    onPrimaryContainer = Color(0xFF042C4F),
    secondary = Slate,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6F1),
    onSecondaryContainer = Color(0xFF16283A),
    // A touch off pure white, so cards can be white and still read as raised.
    background = Color(0xFFF4F6F9),
    onBackground = Color(0xFF161C22),
    surface = Color(0xFFF4F6F9),
    onSurface = Color(0xFF161C22),
    surfaceVariant = Color(0xFFE2E8EF),
    onSurfaceVariant = Color(0xFF44505C),
    outline = Color(0xFF75828F),
    outlineVariant = Color(0xFFC6CFD8),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = OceanLight,
    onPrimary = Color(0xFF002F52),
    primaryContainer = Color(0xFF004574),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = SlateLight,
    onSecondary = Color(0xFF213546),
    secondaryContainer = Color(0xFF374C5E),
    onSecondaryContainer = Color(0xFFD5E3F3),
    background = Color(0xFF11161B),
    onBackground = Color(0xFFE2E6EA),
    surface = Color(0xFF11161B),
    onSurface = Color(0xFFE2E6EA),
    surfaceVariant = Color(0xFF3F4850),
    onSurfaceVariant = Color(0xFFBFC8D1),
    outline = Color(0xFF89929B),
    outlineVariant = Color(0xFF3F4850),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
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

private val LightStatus = StatusColors(
    running = Color(0xFF0B5FA5),
    waiting = Color(0xFF6B7885),
    paused = Color(0xFFB26A00),
    done = Color(0xFF1B7F4B),
    failed = Color(0xFFB3261E),
    progressTrack = Color(0xFFC9D6E3),
)

private val DarkStatus = StatusColors(
    running = Color(0xFF8FC2F0),
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
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
