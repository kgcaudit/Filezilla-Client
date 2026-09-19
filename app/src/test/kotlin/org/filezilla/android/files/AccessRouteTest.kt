package org.filezilla.android.files

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which way in applies on each Android version.
 *
 * All boundary, no body: the way an app reaches shared storage changed twice,
 * and on exactly one version in between there is no way at all. Guess any of
 * these and the app either asks for a permission that grants nothing or never
 * asks for the one that was needed -- and both look the same from outside,
 * like a file manager that cannot see the user's files.
 */
class AccessRouteTest {

    @Test
    fun `Android 8 and 9 ask for a runtime permission`() {
        // Scoped storage does not exist yet, so the old permission still
        // means what it used to.
        assertEquals(AccessRoute.RUNTIME_PERMISSION, accessRouteFor(26))
        assertEquals(AccessRoute.RUNTIME_PERMISSION, accessRouteFor(28))
    }

    /**
     * Android 10 is the gap, and it is a real one rather than an oversight.
     * Scoped storage applies to us here because targeting decides it and we
     * target 35; the permission that would lift it does not exist until the
     * next version.
     */
    @Test
    fun `Android 10 has no way in at all`() {
        assertEquals(AccessRoute.UNAVAILABLE, accessRouteFor(29))
    }

    @Test
    fun `Android 11 and later send the user to settings`() {
        assertEquals(AccessRoute.ALL_FILES_SETTING, accessRouteFor(30))
        assertEquals(AccessRoute.ALL_FILES_SETTING, accessRouteFor(35))
        // A version this app has never seen is likelier to keep the current
        // rule than to bring back one retired two versions ago.
        assertEquals(AccessRoute.ALL_FILES_SETTING, accessRouteFor(40))
    }
}
