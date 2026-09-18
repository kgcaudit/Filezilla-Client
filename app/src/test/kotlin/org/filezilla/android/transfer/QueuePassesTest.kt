package org.filezilla.android.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuePassesTest {

    @Test
    fun `a transfer is picked back up while it has passes left`() {
        assertFalse(hasExhaustedQueuePasses(0))
        assertFalse(hasExhaustedQueuePasses(1))
        assertFalse(hasExhaustedQueuePasses(MAX_QUEUE_PASSES - 1))
    }

    @Test
    fun `a transfer that has used every pass is given up on`() {
        // The property that matters: without this the queue re-runs an
        // INTERRUPTED record forever, because INTERRUPTED is resumable.
        assertTrue(hasExhaustedQueuePasses(MAX_QUEUE_PASSES))
        assertTrue(hasExhaustedQueuePasses(MAX_QUEUE_PASSES + 10))
    }
}
