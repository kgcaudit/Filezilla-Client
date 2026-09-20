package org.filezilla.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the chosen option looks chosen.
 *
 * The bug this pins: Material's filter chip fills the chosen one with
 * `secondaryContainer` and outlines the rest. On this theme that is #EFE6DE
 * on a #F7F4EF dialog -- 1.06:1, which is not a difference anybody can see.
 * The view options dialog offered four sort keys and the user could not tell
 * which was in force, and said so.
 *
 * So the chosen state is now the brand colour, and this is the guard that
 * keeps it visible through the next palette change: a chip that cannot be
 * told apart from the dialog it sits in fails here rather than on a phone.
 *
 * 3:1 is the WCAG 2.1 threshold for something whose job is to be seen rather
 * than read, which is exactly what a filled chip is.
 */
class ChoiceContrastTest {

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

    private fun assertApart(what: String, one: Color, other: Color, least: Double) {
        val measured = ratio(one, other)
        assertTrue(
            "$what is %.2f:1, below the %.1f:1 it needs".format(measured, least),
            measured >= least,
        )
    }

    /** The mark of being chosen has to carry across the dialog behind it. */
    @Test
    fun `a chosen option stands off the dialog it sits in`() {
        assertApart("light chosen chip", LightColors.primary, LightColors.surface, SEEN)
        assertApart("dark chosen chip", DarkColors.primary, DarkColors.surface, SEEN)
    }

    /**
     * And off the ones beside it, which is the comparison the eye actually
     * makes: chips are read against each other, not against the background.
     * An unchosen chip is the surface colour, so this is the same measure
     * from the other side -- kept as its own test because a later design
     * could give the unchosen ones a fill of their own, and that fill is
     * exactly where this would go wrong again.
     */
    @Test
    fun `a chosen option stands off an unchosen one`() {
        assertApart("light chip against chip", LightColors.primary, LightColors.surface, SEEN)
        assertApart("dark chip against chip", DarkColors.primary, DarkColors.surface, SEEN)
    }

    /** Its label has to be readable on the fill, which is the other half. */
    @Test
    fun `the chosen label reads on the fill`() {
        assertApart("light chosen label", LightColors.onPrimary, LightColors.primary, TEXT)
        assertApart("dark chosen label", DarkColors.onPrimary, DarkColors.primary, TEXT)
    }

    /** An unchosen one is still an option, not disabled text. */
    @Test
    fun `an unchosen label is still readable`() {
        assertApart("light unchosen label", LightColors.onSurfaceVariant, LightColors.surface, TEXT)
        assertApart("dark unchosen label", DarkColors.onSurfaceVariant, DarkColors.surface, TEXT)
    }

    private companion object {
        const val SEEN = 3.0
        const val TEXT = 4.5
    }
}
