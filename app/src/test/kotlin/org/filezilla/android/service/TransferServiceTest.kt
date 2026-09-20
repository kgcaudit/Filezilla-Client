package org.filezilla.android.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the service does the whole of what a drained queue calls for.
 *
 * A move is carried in two parts: each transfer removes the file it carried
 * as it lands, and the folders those files were in are cleared afterwards.
 * The second part belongs to whoever notices the queue has finished, which
 * on a phone is this service and nothing else -- so a service that runs the
 * queue and stops there leaves every folder of a move standing, which is the
 * bug the user reported.
 *
 * Read from the source because the alternative is starting a real foreground
 * service under Robolectric to watch it not do something.
 */
class TransferServiceTest {

    private val source: String
        get() = File("src/main/kotlin/org/filezilla/android/service/TransferService.kt").readText()

    @Test
    fun `the queue draining is followed by the sweep`() {
        val body = source.substringAfter("private fun startQueue()")

        val ranQueue = body.indexOf("transfers.runQueue()")
        val swept = body.indexOf("moveCleanup.sweep()")

        assertTrue("the service never sweeps up after a move", swept >= 0)
        assertTrue("the queue is never run", ranQueue >= 0)
        assertTrue("swept before the queue had finished", swept > ranQueue)
    }

    /** And the sweep is not inside the finally, which also runs on a stop. */
    @Test
    fun `a queue the user stopped is not swept`() {
        val body = source.substringAfter("private fun startQueue()")
        val finallyBlock = body.substringAfter("} finally {").substringBefore("\n            }")

        assertTrue(
            "the sweep runs even when the user pressed stop: $finallyBlock",
            "sweep" !in finallyBlock,
        )
    }
}
