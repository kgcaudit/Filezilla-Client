package org.filezilla.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * That deleting never looks like cancelling.
 *
 * The bug this exists for arrived with the palette. While the brand was a
 * deep blue, a confirmation sorted itself out: both its buttons drew in
 * primary, but the one that mattered could be tinted error and a red button
 * beside a blue one is not a thing anybody misreads. The brand is a warm clay
 * now -- and neither button was tinted at all, so "Delete" and "Cancel" came
 * up in the same orange-red, the irreversible one wearing what looks like a
 * warning and the safe one wearing it too.
 *
 * Two things had to hold and neither compiles: the destructive button has to
 * ask for the error colour, and the error colour has to be far enough from
 * the brand to be worth asking for. So one test reads the palette and the
 * other reads the screens.
 */
class DangerColourTest {

    private val uiDir = File("src/main/kotlin/org/filezilla/android/ui")

    /** Hue in degrees, which is the axis the eye sorts two reds on. */
    private fun hueOf(colour: Color): Float {
        val r = colour.red
        val g = colour.green
        val b = colour.blue
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        val span = high - low
        if (span == 0f) return 0f
        val hue = when (high) {
            r -> 60f * (((g - b) / span) % 6f)
            g -> 60f * (((b - r) / span) + 2f)
            else -> 60f * (((r - g) / span) + 4f)
        }
        return (hue + 360f) % 360f
    }

    /** The shorter way round the wheel, so 350 and 10 are 20 apart, not 340. */
    private fun apart(a: Float, b: Float): Float {
        val gap = abs(a - b)
        return min(gap, 360f - gap)
    }

    @Test
    fun `the error colour is not a shade of the brand`() {
        // Twenty-five degrees is about where two reds stop reading as the
        // same red at the size of a dialog button. Material's own error sat
        // twelve degrees from this brand, which is why it needed moving.
        for ((name, scheme) in listOf("light" to LightColors, "dark" to DarkColors)) {
            val gap = apart(hueOf(scheme.primary), hueOf(scheme.error))
            assertTrue(
                "$name: error is only ${gap.toInt()} degrees from primary",
                gap >= 25f,
            )
        }
    }

    /** The colour that means a transfer failed is held apart from the brand too. */
    @Test
    fun `the failed colour is not a shade of the brand either`() {
        for ((name, pair) in listOf(
            "light" to (LightColors.primary to LightStatus.failed),
            "dark" to (DarkColors.primary to DarkStatus.failed),
        )) {
            val gap = apart(hueOf(pair.first), hueOf(pair.second))
            assertTrue("$name: failed is only ${gap.toInt()} degrees from primary", gap >= 25f)
        }
    }

    /**
     * And every confirmation that deletes something asks for it.
     *
     * Read from the source rather than a screenshot because the failure is
     * not that the button is the wrong colour -- it is that nobody said what
     * colour it should be, and the default happened to be the brand's.
     */
    @Test
    fun `every delete confirmation goes through the danger button`() {
        val asking = uiDir.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { "R.string.confirm_delete" in it.readText() }
            .map { it.name }
            .sorted()
            .toList()

        // Not "at most": zero would mean the app had stopped asking before it
        // deletes, or that this stopped looking.
        assertEquals(listOf("BrowseScreen.kt", "FilePanes.kt"), asking)

        // They reach the colour through OloConfirmDialog now rather than
        // naming the button themselves -- which is the point of there being
        // one confirmation instead of three. So the route is what is checked:
        // a screen that builds its own is a screen that can forget.
        val offRoute = uiDir.walkTopDown()
            .filter { it.name in asking }
            .filterNot { "OloConfirmDialog(" in it.readText() }
            .map { it.name }
            .sorted()
            .toList()

        assertEquals(emptyList<String>(), offRoute)

        // And that route really does wear it.
        val shell = File(uiDir, "Dialogs.kt").readText()
        val confirm = shell.substring(shell.indexOf("fun OloConfirmDialog("))
        assertTrue("OloConfirmDialog stopped using DangerButton", "DangerButton(" in confirm)
    }
}
