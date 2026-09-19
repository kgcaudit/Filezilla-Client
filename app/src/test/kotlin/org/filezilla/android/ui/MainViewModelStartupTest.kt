package org.filezilla.android.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.filezilla.android.data.AppPreferences
import org.filezilla.android.files.FilePath
import org.junit.Before
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

    /**
     * The object graph is a process-wide singleton, so the preferences it
     * holds outlive a test. Without this, a test that points a pane at the
     * phone decides where the next test's panes start -- which is how the
     * first-run test came to fail for reasons that had nothing to do with a
     * first run.
     *
     * Written rather than cleared, and through the same class the app uses.
     * Clearing the file underneath went in before the previous test's own
     * write had landed, and that write then put its pane back; queued writes
     * arrive in order, so the last one to ask wins.
     */
    @Before
    fun resetRememberedPanes() {
        // Through the graph's own preferences, not a new AppPreferences over
        // this test's Application. The graph is a static singleton pinned to
        // whichever Application built it first, and Robolectric hands each
        // test a fresh one -- so resetting "the app's" preferences from here
        // was resetting a different app's.
        val prefs = org.filezilla.android.AppGraph.of(application).preferences
        for (id in PaneId.entries) {
            prefs.setPaneIsLocal(id.name, false)
            prefs.setPaneSiteId(id.name, null)
            // Both slots: a pane remembers the phone and each server apart,
            // so clearing one would leave the other steering the next test.
            prefs.setPanePath(id.name, "local", null)
            prefs.setPanePath(id.name, "site:s1", null)
        }
    }

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

    /**
     * A pane opens the folder it was left in, not the filesystem root.
     *
     * The bug: a pane asks for its remembered folder when its path is empty,
     * and the path started at "/" -- which is not empty. So every restored
     * pane opened the root of the filesystem, which cannot be listed, and the
     * user got "this folder could not be read" where their files should have
     * been. "/" was a fair default when the only pane was an FTP one; it was
     * never a usable marker for "nowhere yet".
     */
    @Test
    fun `a local pane does not start at the filesystem root`() {
        val model = MainViewModel(application)

        val path = model.pane(PaneId.LEFT).path

        assertNotEquals("/", path)
        // Somewhere real, rather than merely not the root. Checked by depth
        // rather than by prefix, since the storage root is the device's to
        // name and a test host puts it somewhere else entirely.
        assertTrue("started at '$path'", FilePath.segments(path).size > 1)
    }

    /**
     * And the pane behind it too, once it is swiped to -- which is the case
     * the screenshot came from.
     */
    @Test
    fun `the other pane does not start at the root either when it is local`() {
        val model = MainViewModel(application)

        model.showLocal(PaneId.RIGHT)
        model.showPane(PaneId.RIGHT)

        assertNotEquals("/", model.pane(PaneId.RIGHT).path)
    }

    // ------------------------------- a path belongs to one kind of place

    /**
     * Switching a pane from a server to the phone must not send it to the
     * server's folder.
     *
     * The bug: one remembered path per pane, shared by both kinds of place. A
     * server sitting at "/" therefore sent the pane to the root of the
     * filesystem when it next showed the phone -- which cannot be listed, so
     * the user got "this folder could not be read" instead of their files.
     */
    @Test
    fun `a remote folder is not reused as a local one`() {
        val prefs = org.filezilla.android.AppGraph.of(application).preferences
        // What a server sitting at its root leaves behind, and a pane since
        // pointed at the phone.
        prefs.setPanePath(PaneId.RIGHT.name, "site:s1", "/")
        prefs.setPaneIsLocal(PaneId.RIGHT.name, true)

        val model = MainViewModel(application)
        // Swiped to, rather than switched with showLocal: that one opens the
        // folder only once storage access has been granted, which a test host
        // never grants -- so asserting through it proved nothing at all.
        model.showPane(PaneId.RIGHT)

        assertNotEquals("/", model.pane(PaneId.RIGHT).path)
    }

    /**
     * A folder that has gone -- deleted, or on a card that was taken out --
     * sends the pane to its default rather than to an error.
     */
    @Test
    fun `a remembered folder that is gone falls back to the default`() {
        val prefs = org.filezilla.android.AppGraph.of(application).preferences
        prefs.setPanePath(PaneId.LEFT.name, "local", "/storage/emulated/0/GoneForever")

        val model = MainViewModel(application)

        assertNotEquals("/storage/emulated/0/GoneForever", model.pane(PaneId.LEFT).path)
        assertTrue(FilePath.segments(model.pane(PaneId.LEFT).path).size > 1)
    }
}
