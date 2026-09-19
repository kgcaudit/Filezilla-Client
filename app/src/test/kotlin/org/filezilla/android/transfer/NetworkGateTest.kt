package org.filezilla.android.transfer

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The waiting [NetworkGate] does between attempts, and getting out of it.
 *
 * This is where a transfer sits out a phone with no signal, for as long as ten
 * minutes -- so it is exactly where the user reaches for the pause button, and
 * it used to be where pressing it did nothing at all. The gate now polls for
 * an abandoned transfer, and these check that it really does.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkGateTest {

    private val gate = NetworkGate(ApplicationProvider.getApplicationContext())

    /** Generous, so a slow machine cannot fail this; the bug was minutes. */
    private val promptly = 3_000L

    @Test
    fun `a backoff ends early when the transfer has been stopped`() {
        val started = System.currentTimeMillis()

        gate.waitBeforeRetry(backoffMillis = 60_000, offlineCapMillis = 0) { true }

        val took = System.currentTimeMillis() - started
        assertTrue("waited ${took}ms of a 60s backoff before noticing", took < promptly)
    }

    /**
     * The harder half: the backoff is over, the phone is still offline, and
     * the gate is holding the transfer until a network appears. Nothing about
     * the network changes here -- only the user's mind.
     */
    @Test
    fun `an offline wait ends when the transfer is stopped`() {
        val stopped = AtomicBoolean(false)
        Thread {
            Thread.sleep(200)
            stopped.set(true)
        }.start()
        val started = System.currentTimeMillis()

        gate.waitBeforeRetry(backoffMillis = 0, offlineCapMillis = 600_000) { stopped.get() }

        val took = System.currentTimeMillis() - started
        assertTrue("waited ${took}ms of a ten-minute offline wait before noticing", took < promptly)
    }

    /** It still waits when nothing has asked it not to, or it would retry at once. */
    @Test
    fun `a backoff nobody abandoned is served`() {
        val started = System.currentTimeMillis()

        gate.waitBeforeRetry(backoffMillis = 700, offlineCapMillis = 0)

        val took = System.currentTimeMillis() - started
        assertTrue("returned after only ${took}ms of a 700ms backoff", took >= 700)
    }
}
