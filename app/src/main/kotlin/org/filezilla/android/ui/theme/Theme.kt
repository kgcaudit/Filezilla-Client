package org.filezilla.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FileZillaBlue = Color(0xFF1B4D8F)
private val FileZillaBlueLight = Color(0xFF9DC2F0)

private val LightColors = lightColorScheme(
    primary = FileZillaBlue,
    secondary = Color(0xFF4A6382),
)

private val DarkColors = darkColorScheme(
    primary = FileZillaBlueLight,
    secondary = Color(0xFFB1C9E8),
)

@Composable
fun FileZillaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        // The user's wallpaper colours, where the platform offers them: this
        // is a utility that should look like it belongs on the device.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
