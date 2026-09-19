package org.filezilla.android.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferRateTest {

    private var clock = 1_000L
    private val rate = TransferRate { clock }

    private fun advance(millis: Long) {
        clock += millis
    }

    @Test
    fun `says nothing until there are two samples`() {
        rate.update(0)

        assertNull(rate.bytesPerSecond)
    }

    @Test
    fun `a steady megabyte a second reads as a megabyte a second`() {
        rate.update(0)
        advance(1_000)
        rate.update(1_000_000)

        assertEquals(1_000_000L, rate.bytesPerSecond)
    }

    /**
     * The engine reports every 64 KB, which on a fast link is a hundred times
     * a second. Sampling every one of those measures scheduler jitter.
     */
    @Test
    fun `callbacks closer together than the sample floor are ignored`() {
        rate.update(0)
        advance(TransferRate.MIN_SAMPLE_MILLIS - 1)
        rate.update(64 * 1024)

        assertNull(rate.bytesPerSecond)
    }

    @Test
    fun `a change in speed is followed`() {
        rate.update(0)
        advance(1_000); rate.update(1_000_000)
        val fast = rate.bytesPerSecond!!

        // The link slows to a tenth.
        repeat(20) {
            advance(1_000)
            rate.update(1_000_000 + 100_000L * (it + 1))
        }

        val slow = rate.bytesPerSecond!!
        assertTrue("$slow should be far below $fast", slow < fast / 5)
        assertTrue(slow in 90_000..110_000)
    }

    /** Smoothing exists so the figure can be read, not so it can be right once. */
    @Test
    fun `a single stalled sample does not drop the figure to nothing`() {
        rate.update(0)
        repeat(5) { advance(1_000); rate.update(1_000_000L * (it + 1)) }
        advance(1_000)
        rate.update(5_000_000)              // nothing moved this second

        assertTrue(rate.bytesPerSecond!! > 500_000)
    }

    /** A resume rewinds the running total; a negative speed is not a speed. */
    @Test
    fun `a rewind starts the measurement again rather than going negative`() {
        rate.update(0)
        advance(1_000); rate.update(1_000_000)
        advance(1_000); rate.update(0)

        assertNull(rate.bytesPerSecond)
    }

    @Test
    fun `reset forgets the previous run`() {
        rate.update(0)
        advance(1_000); rate.update(1_000_000)
        rate.reset()

        assertNull(rate.bytesPerSecond)
    }

    // ------------------------------------------------------------------ eta

    @Test
    fun `time left is what is left over the speed`() {
        assertEquals(10L, secondsRemaining(bytes = 0, totalBytes = 1_000, bytesPerSecond = 100))
    }

    @Test
    fun `no total means no estimate`() {
        assertNull(secondsRemaining(bytes = 500, totalBytes = null, bytesPerSecond = 100))
    }

    @Test
    fun `a stopped transfer has no estimate rather than an infinite one`() {
        assertNull(secondsRemaining(bytes = 0, totalBytes = 1_000, bytesPerSecond = 0))
        assertNull(secondsRemaining(bytes = 0, totalBytes = 1_000, bytesPerSecond = null))
    }

    @Test
    fun `a finished transfer has no estimate`() {
        assertNull(secondsRemaining(bytes = 1_000, totalBytes = 1_000, bytesPerSecond = 100))
    }

    // ------------------------------------------------- a transfer gone quiet

    /**
     * The card in the user's screenshot: 267.9 kB of 2.0 GB at 43.9 kB/s, and
     * none of it moving. Progress is pushed when bytes arrive, so a transfer
     * whose network has gone leaves the last figures on screen for ever --
     * and the speed is the one that lies, since it is the one that claims
     * something is still happening.
     */
    @Test
    fun `a transfer that has not moved for seconds counts as stalled`() {
        assertTrue(isStalled(updatedAtMillis = 1_000, nowMillis = 1_000 + STALL_AFTER_MILLIS))
    }

    @Test
    fun `a transfer that reported a moment ago does not`() {
        assertFalse(isStalled(updatedAtMillis = 1_000, nowMillis = 1_000 + STALL_AFTER_MILLIS - 1))
    }

    /**
     * A transfer that has never reported is not stalled; it has not started.
     * Treating the two the same would put "reconnecting" on every card the
     * moment the queue picked it up.
     */
    @Test
    fun `a transfer that has never reported is not stalled`() {
        assertFalse(isStalled(updatedAtMillis = 0, nowMillis = 10_000_000))
    }
}
