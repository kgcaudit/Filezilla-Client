package org.filezilla.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `sizes read in decimal units, as the phone's own downloader shows them`() {
        assertEquals("999 B", formatSize(999))
        assertEquals("1.0 kB", formatSize(1_000))
        assertEquals("4.6 MB", formatSize(4_600_000))
        assertEquals("1.7 GB", formatSize(1_700_000_000))
    }

    @Test
    fun `an unknown size is blank, not minus one bytes`() {
        assertEquals("", formatSize(-1))
    }

    @Test
    fun `speed is a size per second`() {
        assertEquals("1.2 MB/s", formatSpeed(1_200_000))
    }

    @Test
    fun `durations read as people say them`() {
        assertEquals("0:05", formatDuration(5))
        assertEquals("4:07", formatDuration(247))
        assertEquals("25:04", formatDuration(1_504))
        assertEquals("1:25:04", formatDuration(5_104))
    }

    @Test
    fun `a negative duration does not print a minus sign`() {
        assertEquals("0:00", formatDuration(-3))
    }
}
