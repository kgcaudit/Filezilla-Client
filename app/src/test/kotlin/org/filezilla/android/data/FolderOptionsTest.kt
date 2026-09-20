package org.filezilla.android.data

import androidx.test.core.app.ApplicationProvider
import org.filezilla.android.ui.BrowseOptions
import org.filezilla.android.ui.SortKey
import org.filezilla.android.ui.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A folder keeping its own arrangement.
 *
 * One setting for the whole app meant changing it on the way into a folder
 * of photos and changing it back on the way out, which is what the user
 * asked to be rid of. What matters here is that the shared setting survives
 * intact: a folder taking its own copy must not be a way of quietly
 * rewriting the one every other folder uses.
 */
@RunWith(RobolectricTestRunner::class)
class FolderOptionsTest {

    private lateinit var preferences: AppPreferences

    @Before
    fun setUp() {
        preferences = AppPreferences(ApplicationProvider.getApplicationContext())
    }

    private val byDate = BrowseOptions(
        sortKey = SortKey.DATE,
        ascending = false,
        foldersFirst = false,
        showHidden = true,
        viewMode = ViewMode.GRID,
    )

    @Test
    fun `a folder with nothing of its own says so`() {
        assertNull(preferences.optionsForFolder("local:/Download"))
    }

    @Test
    fun `every part of a folder's arrangement comes back`() {
        preferences.setOptionsForFolder("local:/Pictures", byDate)

        assertEquals(byDate, preferences.optionsForFolder("local:/Pictures"))
    }

    /** The point of the feature: one folder, not all of them. */
    @Test
    fun `one folder's arrangement is not another's`() {
        preferences.setOptionsForFolder("local:/Pictures", byDate)

        assertNull(preferences.optionsForFolder("local:/Download"))
    }

    /** And not the shared one, which is what everything else goes on using. */
    @Test
    fun `a folder's own arrangement leaves the shared one alone`() {
        val shared = preferences.browseOptions
        preferences.setOptionsForFolder("local:/Pictures", byDate)

        assertEquals(shared, preferences.browseOptions)
    }

    /**
     * Turning "이 폴더만" off has to take the settings away, not merely stop
     * writing them: left behind, the folder would go on being arranged by
     * settings it is no longer supposed to have.
     */
    @Test
    fun `forgetting a folder's arrangement really forgets it`() {
        preferences.setOptionsForFolder("local:/Pictures", byDate)
        preferences.setOptionsForFolder("local:/Pictures", null)

        assertNull(preferences.optionsForFolder("local:/Pictures"))
    }

    /** The same path on two servers is two folders. */
    @Test
    fun `the same path on different sides is not the same folder`() {
        preferences.setOptionsForFolder("local:/Download", byDate)

        assertNull(preferences.optionsForFolder("site-abc:/Download"))
    }

    /**
     * A file manager visits thousands of folders. Remembering every one of
     * them for ever is a leak with a slow fuse, so the oldest goes.
     */
    @Test
    fun `it remembers a bounded number of folders`() {
        repeat(AppPreferences.MAX_REMEMBERED_FOLDERS + 5) { n ->
            preferences.setOptionsForFolder("local:/folder-$n", byDate)
        }

        assertNull("the oldest was kept", preferences.optionsForFolder("local:/folder-0"))
        assertEquals(
            "the newest was dropped",
            byDate,
            preferences.optionsForFolder(
                "local:/folder-${AppPreferences.MAX_REMEMBERED_FOLDERS + 4}",
            ),
        )
    }

    /** Re-saving a folder is not a new folder; it must not spend a slot. */
    @Test
    fun `saving the same folder twice does not fill the budget`() {
        repeat(AppPreferences.MAX_REMEMBERED_FOLDERS + 5) {
            preferences.setOptionsForFolder("local:/one", byDate)
        }
        preferences.setOptionsForFolder("local:/two", byDate)

        assertEquals(byDate, preferences.optionsForFolder("local:/one"))
        assertEquals(byDate, preferences.optionsForFolder("local:/two"))
    }

    /** Nonsense in the store is not a crash on the next listing. */
    @Test
    fun `a stored value that makes no sense is treated as none`() {
        preferences.setOptionsForFolder("local:/one", byDate)
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("filezilla", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("folder_options_local:/one", "rubbish")
            .commit()

        assertNull(preferences.optionsForFolder("local:/one"))
    }
}
