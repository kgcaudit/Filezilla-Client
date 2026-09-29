package org.filezilla.android.ui

import org.filezilla.android.files.Meta
import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.listing.EntryTime
import org.filezilla.ftp.listing.TimeAccuracy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walk that turns one side of a mirror into the map the diff compares on.
 *
 * Tested against a tree made of maps, like the deep search it is modelled on:
 * what can go wrong is the shape of the walk -- a relative path built wrong, a
 * link followed into a loop, a cap that stops without saying so -- and none of
 * that cares whether the folders came from the phone or a server.
 */
class SyncScanTest {

    private fun tree(vararg folders: Pair<String, List<DirectoryEntry>>): RemoteLister {
        val map = folders.toMap()
        return RemoteLister { path -> map[path] ?: error("no such folder: $path") }
    }

    private fun file(name: String, size: Long = 1, mtime: Long? = null) = DirectoryEntry(
        name = name,
        size = size,
        time = mtime?.let { EntryTime(it, TimeAccuracy.SECONDS) },
    )

    private fun dir(name: String) = DirectoryEntry(name = name, isDirectory = true)
    private fun link(name: String) = DirectoryEntry(name = name, isDirectory = true, isLink = true)

    @Test
    fun `it records every entry by its path relative to the root`() {
        val lister = tree(
            "/src" to listOf(dir("photos"), file("readme.txt", size = 10)),
            "/src/photos" to listOf(file("a.jpg", size = 20)),
        )

        val result = SyncScan.scan("/src", lister)

        assertEquals(
            mapOf(
                "readme.txt" to Meta(isDir = false, size = 10),
                "photos" to Meta(isDir = true, size = null),
                "photos/a.jpg" to Meta(isDir = false, size = 20),
            ),
            result.entries,
        )
    }

    @Test
    fun `a directory's own size is not invented`() {
        val lister = tree("/src" to listOf(dir("empty")))

        val meta = SyncScan.scan("/src", lister).entries.getValue("empty")

        assertTrue(meta.isDir)
        assertEquals(null, meta.size)
    }

    @Test
    fun `a time is carried through for the diff to compare`() {
        val lister = tree("/src" to listOf(file("a.txt", size = 3, mtime = 1_700)))

        val meta = SyncScan.scan("/src", lister).entries.getValue("a.txt")

        assertEquals(1_700L, meta.mtimeMillis)
    }

    @Test
    fun `an empty folder is still recorded so the mirror can make it`() {
        val lister = tree(
            "/src" to listOf(dir("empty")),
            "/src/empty" to emptyList(),
        )

        val result = SyncScan.scan("/src", lister)

        assertEquals(setOf("empty"), result.entries.keys)
        assertTrue(result.entries.getValue("empty").isDir)
    }

    @Test
    fun `links are passed over and counted, not followed`() {
        val lister = tree("/src" to listOf(file("real.txt"), link("loop")))

        val result = SyncScan.scan("/src", lister)

        assertEquals(setOf("real.txt"), result.entries.keys)
        assertEquals(1, result.skippedLinks)
    }

    @Test
    fun `hidden entries are left out unless asked for`() {
        val lister = tree("/src" to listOf(file("seen.txt"), file(".hidden")))

        val hidden = SyncScan.scan("/src", lister, showHidden = false)
        val shown = SyncScan.scan("/src", lister, showHidden = true)

        assertEquals(setOf("seen.txt"), hidden.entries.keys)
        assertEquals(setOf("seen.txt", ".hidden"), shown.entries.keys)
    }

    @Test
    fun `a folder that will not open is passed over, not fatal`() {
        val lister = RemoteLister { path ->
            when (path) {
                "/src" -> listOf(dir("locked"), file("ok.txt"))
                else -> throw java.io.IOException("no permission")
            }
        }

        val result = SyncScan.scan("/src", lister)

        // The unreadable folder still appears (so it is mirrored as a folder),
        // but nothing under it, and the walk did not stop at it.
        assertEquals(setOf("locked", "ok.txt"), result.entries.keys)
    }

    @Test
    fun `a stop between folders returns what it had, marked cancelled`() {
        var opened = 0
        val lister = RemoteLister { path ->
            opened++
            when (path) {
                "/src" -> listOf(dir("a"), dir("b"))
                else -> listOf(file("deep.txt"))
            }
        }

        // Called off after the first folder is read.
        val result = SyncScan.scan("/src", lister, cancelled = { opened >= 1 })

        assertTrue(result.cancelled)
    }

    @Test
    fun `the folder count is reported`() {
        val lister = tree(
            "/src" to listOf(dir("a")),
            "/src/a" to listOf(dir("b")),
            "/src/a/b" to emptyList(),
        )

        val result = SyncScan.scan("/src", lister)

        assertEquals(3, result.foldersRead)
        assertFalse(result.truncated)
    }

    @Test
    fun `a root and a nested file both scan from a non-slash root too`() {
        // Local roots look like "/storage/emulated/0/Sync"; the relative paths
        // must still come out relative to it, not carry the root in them.
        val lister = tree(
            "/storage/emulated/0/Sync" to listOf(dir("d")),
            "/storage/emulated/0/Sync/d" to listOf(file("x.txt", size = 5)),
        )

        val result = SyncScan.scan("/storage/emulated/0/Sync", lister)

        assertEquals(setOf("d", "d/x.txt"), result.entries.keys)
    }
}
