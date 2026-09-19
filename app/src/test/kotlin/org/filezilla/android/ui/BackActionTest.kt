package org.filezilla.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order the back button works through.
 *
 * Every case here is one the user met on a device: back closed the app from
 * six folders deep, and it closed it without asking.
 */
class BackActionTest {

    @Test
    fun `back climbs one folder rather than leaving`() {
        assertEquals(
            BackAction.GO_UP,
            backActionFor(Tab.BROWSE, selecting = false, canGoUp = true, exitArmed = false),
        )
    }

    @Test
    fun `a selection is dropped before anything moves`() {
        assertEquals(
            BackAction.CLEAR_SELECTION,
            backActionFor(Tab.BROWSE, selecting = true, canGoUp = true, exitArmed = false),
        )
    }

    /** A selection is held across tabs, so back on another tab must not eat it. */
    @Test
    fun `a selection held from another tab is left alone`() {
        assertEquals(
            BackAction.SHOW_FIRST_TAB,
            backActionFor(Tab.QUEUE, selecting = true, canGoUp = true, exitArmed = false),
        )
    }

    @Test
    fun `back returns to the first tab from any other`() {
        for (tab in Tab.entries.filter { it != FIRST_TAB }) {
            val expected = if (tab == Tab.BROWSE) BackAction.GO_UP else BackAction.SHOW_FIRST_TAB
            assertEquals(
                "back from $tab",
                expected,
                backActionFor(tab, selecting = false, canGoUp = true, exitArmed = false),
            )
        }
    }

    /** The whole point: the last press asks first. */
    @Test
    fun `leaving is asked about before it happens`() {
        assertEquals(
            BackAction.CONFIRM_EXIT,
            backActionFor(FIRST_TAB, selecting = false, canGoUp = false, exitArmed = false),
        )
    }

    @Test
    fun `a second press while the question stands leaves`() {
        assertEquals(
            BackAction.EXIT,
            backActionFor(FIRST_TAB, selecting = false, canGoUp = false, exitArmed = true),
        )
    }

    /**
     * Being armed is not a shortcut out of the app from anywhere: pressing
     * back twice quickly while deep in a folder climbs twice.
     */
    @Test
    fun `an armed exit does not override navigation`() {
        assertEquals(
            BackAction.GO_UP,
            backActionFor(Tab.BROWSE, selecting = false, canGoUp = true, exitArmed = true),
        )
    }

    /** At the top of a volume there is nowhere to climb, so back moves on. */
    @Test
    fun `the top of a volume falls through to the first tab`() {
        assertEquals(
            BackAction.SHOW_FIRST_TAB,
            backActionFor(Tab.BROWSE, selecting = false, canGoUp = false, exitArmed = false),
        )
    }
}
