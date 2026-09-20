package org.filezilla.android.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * That a pull always gets its indicator back.
 *
 * The bug, seen twice by the user and never once here: the refresh arrow
 * parked over the rows and stayed there. `PullToRefreshBox` animates the
 * indicator away in an effect keyed on `isRefreshing`, so a refresh that
 * never reports itself as running never gets the key change that would put
 * the indicator back -- it sits at whatever height the finger released it.
 *
 * Listing a folder on the phone is a readdir: the loading flag goes up and
 * down between two frames and the screen only ever recomposes with it
 * already down. So the flag is the one thing the indicator must not be
 * driven by, and that is what these check.
 */
@RunWith(RobolectricTestRunner::class)
class RefreshIndicatorTest {

    /** Runs the rule against a script of inputs, collecting what it returns. */
    private fun run(
        minimum: Long = 120L,
        script: suspend Driver.() -> Unit,
    ): List<Boolean> {
        val pulls = mutableLongStateOf(0L)
        val loading = mutableStateOf(false)
        val seen = mutableListOf<Boolean>()

        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            val shown = refreshIndicatorShown(pulls.longValue, loading.value, minimum)
            seen += shown
        }
        idle()

        val driver = Driver(pulls, loading, seen)
        kotlinx.coroutines.runBlocking { driver.script() }
        return seen
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private inner class Driver(
        val pulls: androidx.compose.runtime.MutableLongState,
        val loading: androidx.compose.runtime.MutableState<Boolean>,
        val seen: MutableList<Boolean>,
    ) {
        fun pull() {
            pulls.longValue += 1
            idle()
        }

        fun loading(value: Boolean) {
            loading.value = value
            idle()
        }

        /** Lets real time pass, pumping the looper as it goes. */
        fun waitMillis(millis: Long) {
            val until = System.currentTimeMillis() + millis
            while (System.currentTimeMillis() < until) {
                idle()
                Thread.sleep(5)
            }
            idle()
        }

        /** Pumps until the rule says the indicator is down, or gives up. */
        fun settle(withinMillis: Long = 2_000): Boolean {
            val until = System.currentTimeMillis() + withinMillis
            while (System.currentTimeMillis() < until) {
                idle()
                if (seen.lastOrNull() == false) return true
                Thread.sleep(5)
            }
            return false
        }
    }

    /**
     * The one that matters: a listing so fast the screen never sees it.
     *
     * The loading flag is never set at all here, which is exactly what a
     * readdir looks like from the screen's side.
     */
    @Test
    fun `a pull shows the indicator even when the listing is never seen running`() {
        val seen = run {
            pull()
            assertTrue("the indicator was never raised", seen.contains(true))
            assertTrue("the indicator never came back down", settle())
        }
        assertEquals("it ends down", false, seen.last())
    }

    /** And it is up long enough to be an answer, not a flicker. */
    @Test
    fun `the indicator stays up for the minimum`() {
        run(minimum = 400L) {
            pull()
            waitMillis(150)
            assertEquals("it went down early", true, seen.last())
            assertTrue(settle())
        }
    }

    /** A listing that really does take a while keeps it up throughout. */
    @Test
    fun `a slow listing keeps the indicator up until it finishes`() {
        run {
            loading(true)
            pull()
            waitMillis(300)
            assertEquals("it went down while still loading", true, seen.last())
            loading(false)
            assertTrue("it never came down after loading ended", settle())
        }
    }

    /** A second pull raises it again; a count says two things happened. */
    @Test
    fun `pulling again raises it again`() {
        run {
            pull()
            assertTrue(settle())
            val downAt = seen.size
            pull()
            assertTrue(
                "the second pull did nothing",
                seen.drop(downAt).contains(true),
            )
            assertTrue(settle())
        }
    }

    /** Opening the screen is not a pull, so nothing spins on arrival. */
    @Test
    fun `nothing is shown before anything is pulled`() {
        val seen = run { waitMillis(200) }
        assertEquals(emptyList<Boolean>(), seen.filter { it })
    }
}
