package org.filezilla.android.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * That a tool opened from the storage sheet returns to the sheet, and that
 * going forward into content does not drag the sheet back over it.
 *
 * The bug this guards: opening a tool (recents, the queue, the log) from the
 * sheet and pressing back landed on a bare files screen -- or, when the
 * activity had been recreated and the screen lost, out of the app entirely --
 * rather than on the menu it was opened from. The sheet's open state now lives
 * on the view model so it survives a recreation and can be reopened on the way
 * back; these cover the reopen/cancel logic that drives it.
 */
@RunWith(RobolectricTestRunner::class)
class StorageSheetNavTest {

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    private lateinit var model: MainViewModel

    @Before
    fun setUp() {
        val prefs = org.filezilla.android.AppGraph.of(application).preferences
        for (id in PaneId.entries) {
            prefs.setPaneIsLocal(id.name, true)
            prefs.setPaneSiteId(id.name, null)
            prefs.setPanePath(id.name, "local", null)
        }
        model = MainViewModel(application)
    }

    @Test
    fun `opening and closing the sheet tracks which pane it is on`() {
        assertNull(model.storageSheetPane)
        model.openStorageSheet(PaneId.LEFT)
        assertEquals(PaneId.LEFT, model.storageSheetPane)
        model.closeStorageSheet()
        assertNull(model.storageSheetPane)
    }

    @Test
    fun `a tool opened from the sheet reopens it on the way back`() {
        model.openStorageSheet(PaneId.LEFT)

        // Opening a tool puts the sheet away...
        model.toolOpenedFromSheet(PaneId.LEFT)
        assertNull("the sheet is dismissed while the tool is up", model.storageSheetPane)

        // ...and closing the tool brings it back where it was.
        model.onReturnedHome()
        assertEquals(PaneId.LEFT, model.storageSheetPane)
    }

    @Test
    fun `the correct pane's sheet is the one that reopens`() {
        model.openStorageSheet(PaneId.RIGHT)
        model.toolOpenedFromSheet(PaneId.RIGHT)
        model.onReturnedHome()
        assertEquals(PaneId.RIGHT, model.storageSheetPane)
    }

    @Test
    fun `returning home without a tool launched from the sheet opens nothing`() {
        // A tool reached some other way (a transfer notification, say) leaves
        // the sheet closed when it is backed out of.
        model.onReturnedHome()
        assertNull(model.storageSheetPane)
    }

    @Test
    fun `going forward into content cancels the reopen`() {
        model.openStorageSheet(PaneId.LEFT)
        model.toolOpenedFromSheet(PaneId.LEFT)

        // The user chose a server or a recent file: forward, not back.
        model.cancelSheetReopen()
        model.onReturnedHome()

        assertNull("the menu must not spring back over the chosen content", model.storageSheetPane)
    }
}
