package org.filezilla.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What colour and shape a row gets.
 *
 * A row's icon used to be a small drawing on a pale chip -- a third of the
 * tile, legible if you looked and not if you scanned, and the same drawing
 * for every file whatever it was.
 */
class FileKindTest {

    @Test
    fun `a folder is a folder whatever it is called`() {
        assertEquals(FileKind.FOLDER, kindOf("Season 1", isDirectory = true))
        // Including one whose name carries an extension, which happens.
        assertEquals(FileKind.FOLDER, kindOf("backup.zip", isDirectory = true))
    }

    @Test
    fun `the common kinds are told apart`() {
        assertEquals(FileKind.VIDEO, kindOf("Coyote.vs.Acme.2026.1080p.mkv", false))
        assertEquals(FileKind.ARCHIVE, kindOf("아이콘 팩 part1.zip", false))
        assertEquals(FileKind.IMAGE, kindOf("holiday.JPEG", false))
        assertEquals(FileKind.AUDIO, kindOf("track01.flac", false))
        assertEquals(FileKind.APP, kindOf("olo-explorer.apk", false))
        assertEquals(FileKind.CODE, kindOf("MainViewModel.kt", false))
        assertEquals(FileKind.DOCUMENT, kindOf("report.pdf", false))
    }

    /**
     * A subtitle beside the film it belongs to must not look like the film.
     * Telling them apart at a glance is most of what this list is scanned for.
     */
    @Test
    fun `a subtitle is a document, not a video`() {
        assertEquals(FileKind.DOCUMENT, kindOf("Coyote.vs.Acme.2026.1080p.srt", false))
        assertEquals(FileKind.VIDEO, kindOf("Coyote.vs.Acme.2026.1080p.mkv", false))
    }

    @Test
    fun `case in the extension makes no difference`() {
        assertEquals(FileKind.VIDEO, kindOf("FILM.MKV", false))
    }

    /** The last dot: "archive.tar.gz" is a gz. */
    @Test
    fun `the extension is the last one`() {
        assertEquals(FileKind.ARCHIVE, kindOf("sources.tar.gz", false))
    }

    /**
     * Honest rather than clever. A server full of release names has plenty of
     * files with no extension, and guessing would put a confident wrong
     * colour on them.
     */
    @Test
    fun `an unknown or absent extension is not guessed at`() {
        assertEquals(FileKind.OTHER, kindOf("README", false))
        assertEquals(FileKind.OTHER, kindOf("notes.qqqzzz", false))
    }

    /** A dotfile's name is not its extension. */
    @Test
    fun `a dotfile has no extension`() {
        assertEquals(FileKind.OTHER, kindOf(".bashrc", false))
    }

    /** Every kind has a glyph, including the one that means "no idea". */
    @Test
    fun `every kind draws something`() {
        for (kind in FileKind.entries) {
            assert(kind.glyph != 0) { "$kind has no glyph" }
        }
    }
}
