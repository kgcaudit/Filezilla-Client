package org.filezilla.android.ui

import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.listing.EntryTime
import org.filezilla.ftp.listing.TimeAccuracy
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseListingTest {

    private fun file(name: String, size: Long = 0, at: Long? = null) =
        DirectoryEntry(
            name = name,
            size = size,
            isDirectory = false,
            time = at?.let { EntryTime(it, TimeAccuracy.MINUTES) },
        )

    private fun dir(name: String, at: Long? = null) =
        DirectoryEntry(
            name = name,
            size = -1,
            isDirectory = true,
            time = at?.let { EntryTime(it, TimeAccuracy.MINUTES) },
        )

    private fun names(
        entries: List<DirectoryEntry>,
        options: BrowseOptions = BrowseOptions(),
        filter: String = "",
    ) = BrowseListing.arrange(entries, options, filter).map { it.name }

    @Test
    fun `folders come first and each group is sorted by name`() {
        val listing = listOf(file("beta.txt"), dir("zulu"), file("alpha.txt"), dir("alpha"))
        assertEquals(listOf("alpha", "zulu", "alpha.txt", "beta.txt"), names(listing))
    }

    @Test
    fun `reversing the order keeps folders first`() {
        // The bug this guards: applying folders-first before the direction,
        // so that reversing drops every directory to the bottom.
        val listing = listOf(dir("a"), dir("b"), file("x.txt"), file("y.txt"))
        val descending = BrowseOptions(ascending = false)
        assertEquals(listOf("b", "a", "y.txt", "x.txt"), names(listing, descending))
    }

    @Test
    fun `turning folders-first off lets them sort with everything else`() {
        val listing = listOf(file("b.txt"), dir("c"), file("a.txt"))
        val mixed = BrowseOptions(foldersFirst = false)
        assertEquals(listOf("a.txt", "b.txt", "c"), names(listing, mixed))
    }

    @Test
    fun `name order ignores case`() {
        // Otherwise every capitalised name sorts above every lowercase one,
        // which looks like the sort is simply broken.
        val listing = listOf(file("banana"), file("Apple"), file("cherry"))
        assertEquals(listOf("Apple", "banana", "cherry"), names(listing))
    }

    @Test
    fun `size order puts directories together rather than scattering them`() {
        val listing = listOf(file("big", 900), dir("folder"), file("small", 10))
        val bySize = BrowseOptions(sortKey = SortKey.SIZE, foldersFirst = false)
        assertEquals(listOf("folder", "small", "big"), names(listing, bySize))
    }

    @Test
    fun `date order keeps undated entries together at one end`() {
        val listing = listOf(file("new", at = 3_000), file("undated"), file("old", at = 1_000))
        val byDate = BrowseOptions(sortKey = SortKey.DATE)
        assertEquals(listOf("undated", "old", "new"), names(listing, byDate))
    }

    @Test
    fun `type order groups by extension`() {
        val listing = listOf(file("b.zip"), file("a.txt"), file("c.txt"))
        val byType = BrowseOptions(sortKey = SortKey.TYPE)
        assertEquals(listOf("a.txt", "c.txt", "b.zip"), names(listing, byType))
    }

    @Test
    fun `the filter matches anywhere in the name and ignores case`() {
        val listing = listOf(file("Report.PDF"), file("notes.txt"), file("quarterly-report.doc"))
        assertEquals(
            listOf("quarterly-report.doc", "Report.PDF"),
            names(listing, filter = "report"),
        )
    }

    @Test
    fun `hidden entries are left out until they are asked for`() {
        val listing = listOf(file("visible"), file(".hidden"), dir(".config"))
        assertEquals(listOf("visible"), names(listing))
        assertEquals(
            listOf(".config", ".hidden", "visible"),
            names(listing, BrowseOptions(showHidden = true)),
        )
    }

    @Test
    fun `dot and dot-dot are never shown`() {
        // Some servers list them. The screen has an up button, so they are
        // navigation the user already has, not content.
        val listing = listOf(dir("."), dir(".."), file("real.txt"))
        assertEquals(listOf("real.txt"), names(listing))
        assertEquals(
            listOf("real.txt"),
            names(listing, BrowseOptions(showHidden = true)),
        )
    }
}
