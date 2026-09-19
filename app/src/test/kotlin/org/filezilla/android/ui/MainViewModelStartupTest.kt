package org.filezilla.android.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * That the view model can be built at all.
 *
 * It could not, and the app closed on launch before drawing a frame. Its
 * start-up block was written near the top of the class and assigned to state
 * declared near the bottom; an init block runs where it is written, so it
 * wrote to a delegate that did not exist yet.
 *
 * Nothing in a unit test that exercised the pieces would have caught that,
 * because every piece was fine. What was wrong was the order they were built
 * in, and the only way to catch that is to build it.
 */
@RunWith(RobolectricTestRunner::class)
class MainViewModelStartupTest {

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the view model starts without throwing`() {
        val model = MainViewModel(application)

        assertNotNull(model)
    }

    /**
     * And starts up pointed somewhere, rather than at a blank pane the user
     * has to prod before it does anything.
     */
    @Test
    fun `both panes have a source on a first run`() {
        val model = MainViewModel(application)

        assertEquals(PaneSource.Local, model.pane(PaneId.LEFT).source)
        assertEquals(PaneSource.Empty, model.pane(PaneId.RIGHT).source)
        assertEquals(PaneId.LEFT, model.activePane)
    }

    /**
     * Building it twice is what a configuration change does, and it shares one
     * object graph with the first, so anything it takes exclusively -- the
     * database above all -- would fail here.
     */
    @Test
    fun `a second view model over the same graph also starts`() {
        MainViewModel(application)

        val second = MainViewModel(application)

        assertNotNull(second.pane(PaneId.LEFT))
    }
}
