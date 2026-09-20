package org.filezilla.android.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.filezilla.android.AppGraph
import org.filezilla.android.R
import org.filezilla.android.data.FakePasswordCipher
import org.filezilla.android.data.KeystorePasswordCipher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bug: a notification said "tap to see the transfer list", and tapping it
 * brought the app forward on whatever screen it happened to be on.
 *
 * Both halves are covered here, because either one alone would have passed
 * while the app was broken. The intent has to *carry* the screen, and the
 * activity has to *read* it -- and what shipped was an intent that carried
 * nothing and an activity that read nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class OpenAtTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun useATestableCipher() {
        // The activity builds the graph, and the graph reaches for the
        // Android keystore, which does not exist off a device.
        AppGraph.sealPasswordsWith = { FakePasswordCipher() }
        AppGraph.forget()
    }

    @After
    fun putTheCipherBack() {
        AppGraph.forget()
        AppGraph.sealPasswordsWith = { KeystorePasswordCipher() }
    }

    @Test
    fun `an intent for a screen names that screen`() {
        val asked = OpenAt.screenFor(OpenAt.intentTo(application, Screen.QUEUE))

        assertEquals(Screen.QUEUE, asked)
    }

    /** Launching the app from the launcher asks for nothing in particular. */
    @Test
    fun `a plain intent asks for no screen`() {
        assertNull(OpenAt.screenFor(Intent(application, MainActivity::class.java)))
        assertNull(OpenAt.screenFor(null))
    }

    /**
     * A pending intent outlives the build that made it, so the name in an old
     * one may be a screen this build no longer has. That is a reason to open
     * the app where it always opens, not a reason to crash on a tap.
     */
    @Test
    fun `a screen this build does not have is ignored rather than thrown`() {
        val stale = Intent(application, MainActivity::class.java)
            .putExtra("org.filezilla.android.OPEN_AT", "BOOKMARKS")

        assertNull(OpenAt.screenFor(stale))
    }

    /** The half the user actually saw fail: the app has to act on it. */
    @Test
    fun `opening the app with that intent lands on the transfer list`() {
        ActivityScenario.launch<MainActivity>(OpenAt.intentTo(application, Screen.QUEUE)).use {
            compose.waitForIdle()
            compose.onNodeWithText(application.getString(R.string.title_queue)).assertExists()
        }
    }

    /** And only then: an ordinary launch still opens on the files screen. */
    @Test
    fun `opening the app without one stays where it always opens`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            compose.onNodeWithText(application.getString(R.string.title_queue)).assertDoesNotExist()
        }
    }
}
