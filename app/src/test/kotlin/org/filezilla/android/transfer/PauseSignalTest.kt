package org.filezilla.android.transfer

import org.filezilla.ftp.transfer.RetryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PauseSignalTest {

    @Test
    fun `a pause stops the transfer it names`() {
        val signal = PauseSignal()
        signal.request("one")

        val thrown = assertThrows(TransferPausedException::class.java) {
            signal.stopIfRequested("one")
        }
        assertTrue(thrown.transferId == "one")
    }

    @Test
    fun `a pause does not stop another transfer`() {
        val signal = PauseSignal()
        signal.request("one")
        // Only one transfer runs at a time, but the queue moves between them,
        // so a request aimed at one must not take down the next.
        signal.stopIfRequested("two")
        assertFalse(signal.isRequested("two"))
    }

    @Test
    fun `clearing stops a late request from killing the next run`() {
        val signal = PauseSignal()
        signal.request("one")
        // What happens when the transfer finished before the pause arrived:
        // the run ends, the signal is cleared, and resuming must actually run.
        signal.clear()
        signal.stopIfRequested("one")
        assertFalse(signal.isRequested("one"))
    }

    @Test
    fun `a paused transfer is never retried by the engine`() {
        // The property that makes the pause button work at all. RetryPolicy
        // retries IOExceptions, because a dropped socket is what resume is
        // for. If pausing threw one of those, the engine would reconnect and
        // carry on, and the pause would look like it did nothing.
        // That it is not an IOException the compiler already proves -- an
        // `is IOException` check here does not even compile as a live test.
        // What needs pinning is the consequence: the policy lets it through.
        assertFalse(RetryPolicy().shouldRetry(TransferPausedException("one"), attempt = 1))
    }

    @Test
    fun `a dropped connection is still retried`() {
        // Guards the test above from being satisfied by a policy that simply
        // stopped retrying everything.
        assertTrue(RetryPolicy().shouldRetry(IOException("connection reset"), attempt = 1))
    }
}
