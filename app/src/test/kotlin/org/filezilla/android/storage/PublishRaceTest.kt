package org.filezilla.android.storage

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Two downloads finishing into the same folder tree at the same moment.
 *
 * The bug this pins reached a live-server test as an intermittent missing
 * file, which is the worst way to find one. Transfers run two at a time, and
 * a folder tree hands them destinations that overlap: publishing into
 * "Vision/test" creates "Vision" on the way down, so "Vision" arriving a
 * moment later found `mkdirs` returning false for a folder that was, by
 * then, right there. Read as a failure it threw -- and a throw here means
 * "could not save it", so the bytes stayed in app-private storage, the
 * transfer still showed as finished, and the user was left with a folder
 * quietly missing a file until something else happened to be queued.
 *
 * It depended on which of the two workers won, which is why it showed up
 * roughly one run in four rather than never or always.
 */
@RunWith(RobolectricTestRunner::class)
class PublishRaceTest {

    @get:Rule
    val phone = TemporaryFolder()

    private val storage: SafStorage
        get() = SafStorage(ApplicationProvider.getApplicationContext())

    private fun partial(text: String): File =
        File.createTempFile("partial", ".part").apply { writeText(text) }

    private fun into(root: File, vararg subPath: String) = DownloadDestination(
        tree = Uri.fromFile(root),
        subPath = subPath.toList(),
    )

    /**
     * Both at once, repeatedly: one crossing of the two is enough to fail,
     * and a single attempt would pass on most runs whether or not the bug
     * is there.
     */
    @Test
    fun `two files landing in overlapping folders both arrive`() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(60) { round ->
                val root = phone.newFolder("round-$round")
                val start = CountDownLatch(1)
                val deep = workers.submit {
                    start.await()
                    storage.publish(partial("one"), into(root, "Vision", "test"), "film.mkv")
                }
                val shallow = workers.submit {
                    start.await()
                    storage.publish(partial("two"), into(root, "Vision"), "subtitle.srt")
                }

                start.countDown()
                deep.get(20, TimeUnit.SECONDS)
                shallow.get(20, TimeUnit.SECONDS)

                assertEquals("round $round", "one", File(root, "Vision/test/film.mkv").readText())
                assertEquals("round $round", "two", File(root, "Vision/subtitle.srt").readText())
            }
        } finally {
            workers.shutdownNow()
        }
    }

    /** A folder already there is not a reason to refuse, single-threaded too. */
    @Test
    fun `publishing twice into the same folder works`() {
        val root = phone.newFolder("twice")

        storage.publish(partial("one"), into(root, "Vision"), "a.mkv")
        storage.publish(partial("two"), into(root, "Vision"), "b.mkv")

        assertEquals("one", File(root, "Vision/a.mkv").readText())
        assertEquals("two", File(root, "Vision/b.mkv").readText())
    }

    /** And a folder that genuinely cannot be made still says so. */
    @Test
    fun `a destination that cannot be made is still a failure`() {
        val root = phone.newFolder("blocked")
        // A file where the folder needs to be: mkdirs cannot win this one.
        File(root, "Vision").writeText("in the way")

        val failed = runCatching {
            storage.publish(partial("one"), into(root, "Vision"), "film.mkv")
        }

        assertTrue("a file in the way was treated as a folder", failed.isFailure)
    }
}
