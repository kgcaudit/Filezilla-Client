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
            backActionFor(Screen.FILES, selecting = false, canGoUp = true, exitArmed = false),
        )
    }

    @Test
    fun `a selection is dropped before anything moves`() {
        assertEquals(
            BackAction.CLEAR_SELECTION,
            backActionFor(Screen.FILES, selecting = true, canGoUp = true, exitArmed = false),
        )
    }

    /** A selection is held across tabs, so back on another tab must not eat it. */
    @Test
    fun `a selection held from another screen is left alone`() {
        assertEquals(
            BackAction.CLOSE_SCREEN,
            backActionFor(Screen.QUEUE, selecting = true, canGoUp = true, exitArmed = false),
        )
    }

    /** Every screen that opens over the files screen closes on back. */
    @Test
    fun `back closes whatever opened over the files screen`() {
        for (screen in Screen.entries.filter { it != HOME }) {
            assertEquals(
                "back from $screen",
                BackAction.CLOSE_SCREEN,
                backActionFor(screen, selecting = false, canGoUp = true, exitArmed = false),
            )
        }
    }

    /** The whole point: the last press asks first. */
    @Test
    fun `leaving is asked about before it happens`() {
        assertEquals(
            BackAction.CONFIRM_EXIT,
            backActionFor(HOME, selecting = false, canGoUp = false, exitArmed = false),
        )
    }

    @Test
    fun `a second press while the question stands leaves`() {
        assertEquals(
            BackAction.EXIT,
            backActionFor(HOME, selecting = false, canGoUp = false, exitArmed = true),
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
            backActionFor(Screen.FILES, selecting = false, canGoUp = true, exitArmed = true),
        )
    }

    /** At the top of a volume there is nowhere to climb, so back moves on. */
    @Test
    fun `the top of a volume asks about leaving`() {
        assertEquals(
            BackAction.CONFIRM_EXIT,
            backActionFor(Screen.FILES, selecting = false, canGoUp = false, exitArmed = false),
        )
    }
}
