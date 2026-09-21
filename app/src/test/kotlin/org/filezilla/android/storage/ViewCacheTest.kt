package org.filezilla.android.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * What the viewing cache promises, and the two ways it could quietly break.
 *
 * It can show somebody yesterday's copy of a file that has since changed,
 * which is the app lying about the server. And it can grow without end,
 * which is a few hundred megabytes nobody can trace to a cause.
 */
@RunWith(RobolectricTestRunner::class)
class ViewCacheTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var cache: ViewCache

    @Before
    fun start() {
        File(context.cacheDir, "viewing").deleteRecursively()
        cache = ViewCache(context, limitBytes = 1000)
    }

    private fun key(
        path: String = "/films/one.mkv",
        name: String = "one.mkv",
        size: Long = 10,
        modified: Long = 5,
        server: String = "nas:21:me",
    ) = ViewCache.Key(server, path, name, size, modified)

    private fun write(file: File, bytes: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
    }

    @Test
    fun `the same file lands in the same place`() {
        assertEquals(cache.fileFor(key()), cache.fileFor(key()))
    }

    @Test
    fun `the extension survives, because it is what says what the file is`() {
        assertEquals("mkv", cache.fileFor(key()).extension)
        assertEquals("txt", cache.fileFor(key(name = "notes.txt")).extension)
    }

    @Test
    fun `a name the server chose cannot escape the folder`() {
        // A remote path can hold anything at all. The name here is a digest
        // of it, so there is nothing to escape with.
        val file = cache.fileFor(key(path = "/../../etc/passwd", name = "../../x"))

        assertEquals(File(context.cacheDir, "viewing"), file.parentFile)
        assertFalse("a separator reached the file name", '/' in file.name)

        // The dot in "archive.tar.gz" is fine; the one in "../../x" is not,
        // and what tells them apart is what follows it.
        assertEquals("gz", cache.fileFor(key(name = "archive.tar.gz")).extension)
        assertEquals("", cache.fileFor(key(name = "no-extension")).extension)
        assertEquals("", cache.fileFor(key(name = "odd.na me")).extension)
    }

    @Test
    fun `a file edited on the server is not the file that was held`() {
        // The path is the same and the name is the same. If those were the
        // key, tapping it would open the copy from before the edit -- and
        // nothing on screen would say so.
        assertNotEquals(cache.fileFor(key(modified = 5)), cache.fileFor(key(modified = 6)))
        assertNotEquals(cache.fileFor(key(size = 10)), cache.fileFor(key(size = 11)))
    }

    @Test
    fun `the same path on two servers is two files`() {
        assertNotEquals(cache.fileFor(key(server = "a:21:me")), cache.fileFor(key(server = "b:21:me")))
    }

    @Test
    fun `a copy is only ready when all of it is here`() {
        val file = cache.fileFor(key(size = 10))

        assertNull("nothing fetched yet", cache.readyFile(key(size = 10)))

        write(file, 4)
        // A cancelled fetch leaves a piece behind. Opening it would show
        // somebody the first four bytes of their film.
        assertNull("half a file is not the file", cache.readyFile(key(size = 10)))

        write(file, 10)
        assertEquals(file, cache.readyFile(key(size = 10)))
    }

    @Test
    fun `a server that would not say the size is taken at its word`() {
        val file = cache.fileFor(key(size = -1))
        write(file, 4)

        // Nothing here can tell whole from partial without a size. The
        // fetch deletes what it failed to finish, which is what covers it.
        assertEquals(file, cache.readyFile(key(size = -1)))
    }

    @Test
    fun `holding more than the limit drops the least recently used`() {
        val old = cache.fileFor(key(path = "/old", name = "old.bin"))
        val middle = cache.fileFor(key(path = "/middle", name = "middle.bin"))
        val fresh = cache.fileFor(key(path = "/fresh", name = "fresh.bin"))
        write(old, 600)
        write(middle, 600)
        write(fresh, 600)
        old.setLastModified(1_000)
        middle.setLastModified(2_000)
        fresh.setLastModified(3_000)

        cache.evictDownTo()

        assertFalse("the oldest should have gone", old.exists())
        assertTrue(fresh.exists())
        assertTrue(cache.totalBytes() <= 1000)
    }

    @Test
    fun `the file just fetched is not the one thrown away`() {
        val older = cache.fileFor(key(path = "/older", name = "older.bin"))
        val justFetched = cache.fileFor(key(path = "/now", name = "now.bin"))
        write(older, 900)
        write(justFetched, 900)
        // Oldest by the clock, and the one being opened right now.
        justFetched.setLastModified(1_000)
        older.setLastModified(2_000)

        cache.evictDownTo(keep = justFetched)

        assertTrue("the file about to be opened was evicted", justFetched.exists())
        assertFalse(older.exists())
    }

    @Test
    fun `touching a file saves it from the next eviction`() {
        val first = cache.fileFor(key(path = "/first", name = "first.bin"))
        val second = cache.fileFor(key(path = "/second", name = "second.bin"))
        write(first, 600)
        write(second, 600)
        first.setLastModified(1_000)
        second.setLastModified(2_000)

        cache.touch(first)
        cache.evictDownTo()

        assertTrue("the one just opened should stay", first.exists())
        assertFalse(second.exists())
    }

    @Test
    fun `emptying it leaves nothing behind`() {
        write(cache.fileFor(key()), 100)
        write(cache.fileFor(key(path = "/two", name = "two.bin")), 100)

        cache.clear()

        assertEquals(0, cache.totalBytes())
    }

    @Test
    fun `an empty cache is not a negative number`() {
        assertEquals(0, cache.totalBytes())
    }
}
