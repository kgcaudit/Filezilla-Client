package org.filezilla.android.transfer

import org.filezilla.android.data.SiteEntity
import org.filezilla.ftp.listing.DirectoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules the listing cache lives by, away from any server.
 *
 * What is being defended here is not speed -- that is measured end to end
 * in [org.filezilla.android.endtoend.CachedBrowsingTest] -- but the far
 * more expensive failure of showing somebody a folder that no longer looks
 * like that.
 */
class RemoteListingsTest {

    private var clock = 0L
    private val cache = RemoteListings { clock }

    private fun site(
        id: String = "one",
        host: String = "example.test",
        port: Int = 21,
        user: String = "me",
        encoding: String? = null,
    ) = SiteEntity(
        id = id,
        name = id,
        host = host,
        port = port,
        user = user,
        passwordCipher = "",
        security = "EXPLICIT_TLS",
        transferMode = "DEFAULT",
        pinnedCertificate = null,
        initialPath = null,
        encoding = encoding,
    )

    private fun rows(vararg names: String) = names.map { DirectoryEntry(name = it) }

    @Test
    fun `gives back what was put in`() {
        cache.remember(site(), "/a", "/a", rows("one.txt", "two.txt"))

        val known = cache.recall(site(), "/a")

        assertEquals(listOf("one.txt", "two.txt"), known?.entries?.map { it.name })
        assertEquals("/a", known?.path)
    }

    @Test
    fun `a folder never listed is not guessed at`() {
        assertNull(cache.recall(site(), "/a"))
    }

    @Test
    fun `keeps where the server said it landed, not where it was asked`() {
        // A symbolic link, or a relative path. The pane has to end up at the
        // real one or the next path it builds is built on a name the server
        // does not use.
        cache.remember(site(), "/link", "/real/place", rows("one.txt"))

        assertEquals("/real/place", cache.recall(site(), "/link")?.path)
    }

    @Test
    fun `stops being believed after a minute`() {
        cache.remember(site(), "/a", "/a", rows("one.txt"))

        clock = RemoteListings.LIFETIME_MILLIS - 1
        assertEquals(1, cache.recall(site(), "/a")?.entries?.size)

        clock = RemoteListings.LIFETIME_MILLIS
        assertNull("a listing a minute old is a guess", cache.recall(site(), "/a"))
    }

    @Test
    fun `a write to the server drops everything held for it`() {
        cache.remember(site(), "/a", "/a", rows("one.txt"))
        cache.remember(site(), "/b", "/b", rows("two.txt"))

        cache.forget(site())

        assertNull(cache.recall(site(), "/a"))
        // The folder that was written to is rarely the only one that
        // changed: deleting a folder changes what is inside it too.
        assertNull(cache.recall(site(), "/b"))
    }

    @Test
    fun `an upload drops the server it landed on and no other`() {
        cache.remember(site(id = "one"), "/a", "/a", rows("one.txt"))
        cache.remember(site(id = "two", host = "elsewhere.test"), "/a", "/a", rows("two.txt"))

        // All a finished upload knows about where its bytes went.
        cache.forgetServer("example.test", 21, "me")

        assertNull(cache.recall(site(id = "one"), "/a"))
        assertEquals(1, cache.recall(site(id = "two", host = "elsewhere.test"), "/a")?.entries?.size)
    }

    @Test
    fun `two saved sites onto one login are one filesystem`() {
        cache.remember(site(id = "one"), "/a", "/a", rows("one.txt"))
        cache.remember(site(id = "two"), "/a", "/a", rows("one.txt"))

        // Same host, port and user under two names. A write through either
        // changes what the other would see.
        cache.forget(site(id = "one"))

        assertNull(cache.recall(site(id = "two"), "/a"))
    }

    @Test
    fun `the same folder read as two encodings is two listings`() {
        // The bytes on the wire are the same; the names they turn into are
        // not. Handing one back for the other puts mojibake on screen.
        cache.remember(site(encoding = "EUC-KR"), "/a", "/a", rows("한글.txt"))

        assertNull(cache.recall(site(encoding = null), "/a"))
        assertEquals(1, cache.recall(site(encoding = "EUC-KR"), "/a")?.entries?.size)
    }

    @Test
    fun `holds a bounded number of folders, dropping the least recently used`() {
        for (i in 1..RemoteListings.MAX_FOLDERS) {
            cache.remember(site(), "/f$i", "/f$i", rows("one.txt"))
        }
        // Touched, so it is no longer the oldest.
        cache.recall(site(), "/f1")

        cache.remember(site(), "/extra", "/extra", rows("one.txt"))

        assertEquals(RemoteListings.MAX_FOLDERS, cache.size())
        assertNull("the least recently used should go", cache.recall(site(), "/f2"))
        assertEquals(1, cache.recall(site(), "/f1")?.entries?.size)
        assertEquals(1, cache.recall(site(), "/extra")?.entries?.size)
    }

    @Test
    fun `a few enormous folders cannot fill memory either`() {
        val huge = rows(*Array(RemoteListings.MAX_ROWS) { "file$it" })
        cache.remember(site(), "/big", "/big", huge)
        cache.remember(site(), "/small", "/small", rows("one.txt"))

        // Counting folders alone would have kept both, and thirty-two
        // folders of twenty thousand rows is not a cache, it is a leak.
        assertNull(cache.recall(site(), "/big"))
        assertEquals(1, cache.recall(site(), "/small")?.entries?.size)
    }

    @Test
    fun `re-listing a folder replaces it rather than counting it twice`() {
        cache.remember(site(), "/a", "/a", rows("one.txt", "two.txt"))
        cache.remember(site(), "/a", "/a", rows("three.txt"))

        assertEquals(1, cache.size())
        assertEquals(listOf("three.txt"), cache.recall(site(), "/a")?.entries?.map { it.name })
    }

    @Test
    fun `the same folder named two ways is one entry`() {
        cache.remember(site(), "/a/b", "/a/b", rows("one.txt"))

        assertEquals(1, cache.recall(site(), "/a/b/")?.entries?.size)
    }
    @Test
    fun `a listing that set out before a write is not put back afterwards`() {
        // The order that does the damage: the pane asks for a listing, an
        // upload lands and empties the cache, and then the listing -- taken
        // before the upload -- arrives and is remembered. Without this the
        // folder goes on not showing the file that was just put in it.
        val asOf = cache.asOf()

        cache.forget(site())
        cache.remember(site(), "/a", "/a", rows("stale.txt"), asOf)

        assertNull(cache.recall(site(), "/a"))
    }

    @Test
    fun `a listing taken after the write is kept`() {
        cache.forget(site())
        val asOf = cache.asOf()

        cache.remember(site(), "/a", "/a", rows("fresh.txt"), asOf)

        assertEquals(listOf("fresh.txt"), cache.recall(site(), "/a")?.entries?.map { it.name })
    }

    @Test
    fun `a write to another server does not throw this listing away`() {
        val asOf = cache.asOf()
        cache.forget(site(id = "other", host = "elsewhere.test"))

        cache.remember(site(), "/a", "/a", rows("one.txt"), asOf)

        // One counter for every server means a write anywhere costs a
        // re-listing here. That is the trade, and it is the safe direction
        // -- but it must not be so blunt that nothing is ever cached.
        assertNull(cache.recall(site(), "/a"))
        assertEquals(
            "a second listing, taken after that write, should be kept",
            1,
            cache.let {
                it.remember(site(), "/a", "/a", rows("one.txt"), it.asOf())
                it.recall(site(), "/a")?.entries?.size
            },
        )
    }

}
