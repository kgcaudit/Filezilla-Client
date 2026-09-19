package org.filezilla.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the theme's text can actually be read on the colour behind it.
 *
 * Checked rather than trusted, because this is the property that breaks
 * quietly. The bar used to be a deep blue with white on it; when it became a
 * pale surface the white would have stayed white -- invisible -- unless
 * something said so. A colour change that breaks this now fails here instead
 * of on a phone.
 *
 * Thresholds are WCAG 2.1 AA: 4.5:1 for text, and 3:1 for something whose job
 * is to be seen rather than read, which is what a status dot or a progress bar
 * is.
 */
class ThemeContrastTest {

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private fun ratio(a: Color, b: Color): Double {
        val high = maxOf(luminance(a), luminance(b))
        val low = minOf(luminance(a), luminance(b))
        return (high + 0.05) / (low + 0.05)
    }

    private fun assertReadable(what: String, ink: Color, on: Color, least: Double = TEXT) {
        val measured = ratio(ink, on)
        assertTrue(
            "$what is %.2f:1, below the %.1f:1 it needs".format(measured, least),
            measured >= least,
        )
    }

    @Test
    fun `app bar text reads on the app bar`() {
        // The bar is the surface colour now, so this is the check that would
        // have caught leaving the old white title behind.
        assertReadable("light app bar title", LightColors.onSurface, LightColors.surface)
        assertReadable("light app bar icons", LightColors.onSurfaceVariant, LightColors.surface)
        assertReadable("dark app bar title", DarkColors.onSurface, DarkColors.surface)
        assertReadable("dark app bar icons", DarkColors.onSurfaceVariant, DarkColors.surface)
    }

    @Test
    fun `the selection bar reads on its tint`() {
        assertReadable(
            "light selection bar", LightColors.onPrimaryContainer, LightColors.primaryContainer,
        )
        assertReadable(
            "dark selection bar", DarkColors.onPrimaryContainer, DarkColors.primaryContainer,
        )
    }

    @Test
    fun `body text reads on every surface it lands on`() {
        assertReadable("light body", LightColors.onSurface, LightColors.surface)
        assertReadable("light body on background", LightColors.onBackground, LightColors.background)
        assertReadable("light secondary text", LightColors.onSurfaceVariant, LightColors.surface)
        assertReadable("dark body", DarkColors.onSurface, DarkColors.surface)
        assertReadable("dark body on background", DarkColors.onBackground, DarkColors.background)
        assertReadable("dark secondary text", DarkColors.onSurfaceVariant, DarkColors.surface)
    }

    @Test
    fun `an error card can be read`() {
        assertReadable("light error card", LightColors.onErrorContainer, LightColors.errorContainer)
        assertReadable("dark error card", DarkColors.onErrorContainer, DarkColors.errorContainer)
    }

    /**
     * Status colours are seen, not read -- a dot, a progress bar -- so they
     * are held to the 3:1 a graphical object needs rather than to text's 4.5.
     */
    @Test
    fun `every status colour stands out from the surface behind it`() {
        for ((name, colour) in listOf(
            "running" to LightStatus.running,
            "waiting" to LightStatus.waiting,
            "paused" to LightStatus.paused,
            "done" to LightStatus.done,
            "failed" to LightStatus.failed,
        )) {
            assertReadable("light status $name", colour, LightColors.surface, GRAPHIC)
        }
        for ((name, colour) in listOf(
            "running" to DarkStatus.running,
            "waiting" to DarkStatus.waiting,
            "paused" to DarkStatus.paused,
            "done" to DarkStatus.done,
            "failed" to DarkStatus.failed,
        )) {
            assertReadable("dark status $name", colour, DarkColors.surface, GRAPHIC)
        }
    }

    /**
     * The colour that says what is moving must not be the colour that says
     * what you can press. They were the same value, and blue meant both.
     */
    @Test
    fun `running is not the primary colour`() {
        assertTrue(
            "running is still primary in the light theme",
            LightStatus.running != LightColors.primary,
        )
        assertTrue(
            "running is still primary in the dark theme",
            DarkStatus.running != DarkColors.primary,
        )
    }

    private companion object {
        const val TEXT = 4.5
        const val GRAPHIC = 3.0
    }
}
