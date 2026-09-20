package org.filezilla.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * That nothing large is drawn in a colour from the other side of the wheel.
 *
 * The bug: the error card was filled with errorContainer, and that role was
 * the crimson's own tint -- blue ahead of green, which is the magenta side.
 * Every surface in this app is an ivory, green ahead of blue. So a pink card
 * sat on a cream page, and the whole screen read as two apps. It is the one
 * thing about the palette the user picked out without being asked.
 *
 * Nothing in the type system objects to that, and neither does a screenshot
 * taken by somebody who has stopped noticing. The rule that catches it is
 * small: a colour that fills a region has to lean the way the page leans.
 * Whether green leads blue is a crude test of that, and crude is the point
 * -- it is the difference between a blush and a pink, and it is exactly the
 * line the broken colour crossed.
 *
 * Only for colours that cover ground. An accent spends its hue on a few
 * hundred pixels of text or one small mark, where a colour from anywhere on
 * the wheel is a signal rather than a clash -- error is a crimson on purpose,
 * and tertiary is a teal.
 */
class SurfaceWarmthTest {

    /** The roles that fill a region rather than mark one. */
    private fun areasOf(scheme: ColorScheme): Map<String, Color> = mapOf(
        "background" to scheme.background,
        "surface" to scheme.surface,
        "surfaceVariant" to scheme.surfaceVariant,
        "surfaceBright" to scheme.surfaceBright,
        "surfaceDim" to scheme.surfaceDim,
        "surfaceContainerLowest" to scheme.surfaceContainerLowest,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
        "primaryContainer" to scheme.primaryContainer,
        "secondaryContainer" to scheme.secondaryContainer,
        "errorContainer" to scheme.errorContainer,
        "inverseSurface" to scheme.inverseSurface,
    )

    private fun hex(c: Color): String =
        "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

    @Test
    fun `every colour that fills a region leans the way the page leans`() {
        for ((name, scheme) in listOf("light" to LightColors, "dark" to DarkColors)) {
            val cool = areasOf(scheme)
                .filter { (_, c) -> c.blue > c.green }
                .map { (role, c) -> "$name.$role (${hex(c)})" }
                .sorted()

            assertEquals(emptyList<String>(), cool)
        }
    }
}
