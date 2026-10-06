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

    // One instance, as production has it: AppGraph builds a single SafStorage
    // that both queue workers publish through, so the lock inside it is what
    // serialises them. A fresh instance per call would lock on a different
    // object each time and prove nothing about the race.
    private val storage = SafStorage(ApplicationProvider.getApplicationContext())

    private fun partial(text: String): File =
        File.createTempFile("partial", ".part").apply { writeText(text) }

    private fun into(
        root: File,
        vararg subPath: String,
        onConflict: ConflictChoice = ConflictChoice.DEFAULT,
    ) = DownloadDestination(
        tree = Uri.fromFile(root),
        subPath = subPath.toList(),
        onConflict = onConflict,
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

    /**
     * Two different files with the same name landing in one folder at once.
     *
     * The companion to the test above, and a worse failure: there the two had
     * different names and only the folder overlapped; here the *name* collides.
     * Keep-both numbers a second copy only once the first is there to be seen,
     * so if the existence check, the numbering and the create are not held
     * together, both workers see an empty folder, both write "film.mkv", and
     * one file's bytes land on the other's -- a download reported finished, with
     * the wrong contents, and no sign anything was lost. Both must survive, each
     * with its own bytes.
     */
    @Test
    fun `two same-named files landing at once both survive`() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(60) { round ->
                val root = phone.newFolder("same-$round")
                val start = CountDownLatch(1)
                val a = workers.submit {
                    start.await()
                    storage.publish(partial("one"), into(root, onConflict = ConflictChoice.KEEP_BOTH), "film.mkv")
                }
                val b = workers.submit {
                    start.await()
                    storage.publish(partial("two"), into(root, onConflict = ConflictChoice.KEEP_BOTH), "film.mkv")
                }

                start.countDown()
                a.get(20, TimeUnit.SECONDS)
                b.get(20, TimeUnit.SECONDS)

                // Two files, and between them both sets of bytes -- neither
                // overwritten by the other.
                val contents = root.listFiles().orEmpty().filter { it.isFile }.map { it.readText() }.sorted()
                assertEquals("round $round", listOf("one", "two"), contents)
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
