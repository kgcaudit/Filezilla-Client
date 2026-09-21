package org.filezilla.android.files

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Handing a downloaded apk to the system installer.
 *
 * The bug the user found: tapping a downloaded apk offered the package
 * installer, launched it, and nothing happened. No install, no error, no
 * dialog. Since Android 8 the app that starts an install has to declare
 * REQUEST_INSTALL_PACKAGES and the user has to have allowed this app as a
 * source; with neither, the installer turns the app away without a word,
 * which is the worst of the possible failures because there is nothing on
 * screen to read and nothing to act on.
 *
 * The declaration is checked here too. It is one line in a manifest, it
 * cannot be seen from any screen, and losing it puts the app straight back
 * to a tap that does nothing.
 */
@RunWith(RobolectricTestRunner::class)
class InstallApkTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `an apk is a package`() {
        assertTrue(InstallApk.isPackage("olo-explorer.apk"))
        assertTrue("extensions are written both ways", InstallApk.isPackage("OLO.APK"))
    }

    @Test
    fun `other files are not`() {
        assertFalse(InstallApk.isPackage("film.mkv"))
        assertFalse(InstallApk.isPackage("archive.apk.zip"))
        assertFalse(InstallApk.isPackage("README"))
    }

    /** The one route out of the silent refusal, addressed to this app. */
    @Test
    fun `the settings route names this app rather than the whole list`() {
        val intent = InstallApk.settingsIntent(context)

        assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }

    /**
     * The declaration itself.
     *
     * Read from the manifest because nothing else can see it: no screen
     * shows it, no other test touches it, and without it the installer is
     * never reached however well the rest of this works.
     */
    @Test
    fun `the app declares that it may ask to install packages`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(
            "REQUEST_INSTALL_PACKAGES is gone; a downloaded apk will do nothing when tapped",
            "android.permission.REQUEST_INSTALL_PACKAGES" in manifest,
        )
    }

    /** And the type Android knows an installable package by. */
    @Test
    fun `the package type is the one Android uses`() {
        assertEquals("application/vnd.android.package-archive", InstallApk.TYPE)
    }
}
