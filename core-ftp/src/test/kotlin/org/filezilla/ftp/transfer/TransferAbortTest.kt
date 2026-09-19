package org.filezilla.ftp.transfer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The bookkeeping [TransferAbort] does around a socket it does not own.
 *
 * The socket half is covered against a live server in [AbortIntegrationTest];
 * what is left here is the ordering, which is where the races are: a stop can
 * arrive before the connection exists, between two connections, or twice.
 */
class TransferAbortTest {

    private val abort = TransferAbort()
    private var closed = 0

    @Test
    fun `closes the armed connection`() {
        abort.arm { closed++ }

        abort.abortAndStop()

        assertEquals(1, closed)
        assertTrue(abort.isStopped)
    }

    /**
     * A pause pressed a moment before the data connection opened. Without
     * this the stop would be delivered to nothing and the transfer would go on
     * as if it had never been asked -- the pause button doing nothing, which
     * is the bug all of this exists to fix.
     */
    @Test
    fun `a stop that arrived first lands on the next connection`() {
        abort.abortAndStop()

        abort.arm { closed++ }

        assertEquals(1, closed)
    }

    /** Between connections there is nothing to close, and that is not an error. */
    @Test
    fun `a stop with nothing armed is remembered rather than lost`() {
        abort.arm { closed++ }
        abort.disarm()

        abort.abortAndStop()

        assertEquals(0, closed)
        assertTrue(abort.isStopped)
    }

    /**
     * A network that moved is not a pause: the connection is dead and should
     * be dropped, but the transfer is still wanted and the retry loop has to
     * carry on.
     */
    @Test
    fun `a retry abort closes the connection without stopping the transfer`() {
        abort.arm { closed++ }

        abort.abortAndRetry()

        assertEquals(1, closed)
        assertFalse(abort.isStopped)
    }

    /**
     * The split a caller on the main thread needs: the stop is recorded where
     * it happens, and the socket is closed from a thread that may write.
     */
    @Test
    fun `a stop can be recorded without touching the socket`() {
        abort.arm { closed++ }

        abort.stop()

        assertTrue(abort.isStopped)
        assertEquals(0, closed)

        abort.abortAndStop()
        assertEquals(1, closed)
    }

    @Test
    fun `reset readies it for another run`() {
        abort.arm { closed++ }
        abort.abortAndStop()

        abort.reset()
        abort.arm { closed++ }

        assertFalse(abort.isStopped)
        // Once for the stop; the fresh arm must not fire the old one again.
        assertEquals(1, closed)
    }
}
