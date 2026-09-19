package org.filezilla.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A path as a row of places you can tap back to.
 *
 * The header printed the path as one ellipsised line, which six folders down
 * showed the folder you were already in and hid every folder you might want
 * to get back to.
 */
class BreadcrumbsTest {

    private fun labels(crumbs: List<Crumb>) = crumbs.map { it.label }
    private fun paths(crumbs: List<Crumb>) = crumbs.map { it.path }

    @Test
    fun `a folder on the phone is named from its volume down`() {
        val crumbs = breadcrumbs(
            "/storage/emulated/0/Download/Invoices",
            rootPath = "/storage/emulated/0",
            rootLabel = "Internal storage",
        )

        assertEquals(listOf("Internal storage", "Download", "Invoices"), labels(crumbs))
        assertEquals(
            listOf(
                "/storage/emulated/0",
                "/storage/emulated/0/Download",
                "/storage/emulated/0/Download/Invoices",
            ),
            paths(crumbs),
        )
    }

    /**
     * The volume itself is one crumb, not none: the trail has to be tappable
     * even when there is nowhere above to tap to.
     */
    @Test
    fun `the volume root is a trail of one`() {
        val crumbs = breadcrumbs(
            "/storage/emulated/0",
            rootPath = "/storage/emulated/0",
            rootLabel = "Internal storage",
        )

        assertEquals(listOf("Internal storage"), labels(crumbs))
        assertEquals(listOf("/storage/emulated/0"), paths(crumbs))
    }

    @Test
    fun `a server's root is named and its folders follow`() {
        val crumbs = breadcrumbs("/pub/releases", rootPath = "/", rootLabel = "example.org")

        assertEquals(listOf("example.org", "pub", "releases"), labels(crumbs))
        assertEquals(listOf("/", "/pub", "/pub/releases"), paths(crumbs))
    }

    @Test
    fun `a server sitting at its root is a trail of one`() {
        assertEquals(listOf("example.org"), labels(breadcrumbs("/", "/", "example.org")))
    }

    /**
     * A pane can be pointed somewhere no volume covers. The trail is still
     * complete, from the filesystem root down, rather than empty or cut off:
     * an unnavigable header is worse than an unfamiliar one.
     */
    @Test
    fun `a path outside the root is still given a full trail`() {
        val crumbs = breadcrumbs(
            "/mnt/media_rw/1234/Music",
            rootPath = "/storage/emulated/0",
            rootLabel = "Internal storage",
        )

        assertEquals(listOf("/", "mnt", "media_rw", "1234", "Music"), labels(crumbs))
        assertEquals("/mnt/media_rw/1234/Music", crumbs.last().path)
    }

    /** A trailing slash names the same folder and must not add an empty crumb. */
    @Test
    fun `a trailing slash adds nothing`() {
        assertEquals(
            labels(breadcrumbs("/pub/releases", "/", "example.org")),
            labels(breadcrumbs("/pub/releases/", "/", "example.org")),
        )
    }

    /**
     * Near-miss prefixes: "Download" must not be treated as a volume root
     * just because the volume's name starts the same way.
     */
    @Test
    fun `a root is matched by segment and not by spelling`() {
        val crumbs = breadcrumbs(
            "/storage/emulated/01/Music",
            rootPath = "/storage/emulated/0",
            rootLabel = "Internal storage",
        )

        assertEquals(listOf("/", "storage", "emulated", "01", "Music"), labels(crumbs))
    }

    /** The last crumb is always where the pane actually is. */
    @Test
    fun `the trail ends where the pane is`() {
        for (path in listOf("/", "/pub", "/pub/a/b/c")) {
            assertEquals(path, breadcrumbs(path, "/", "example.org").last().path)
        }
    }
}
