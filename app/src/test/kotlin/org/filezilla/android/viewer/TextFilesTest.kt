package org.filezilla.android.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * Reading text in whatever encoding it is, and writing it back unchanged.
 *
 * The one that matters on this phone: a Korean .txt saved as CP949 must come
 * back as its Korean, not as mojibake, and an edit saved to it must stay
 * CP949 rather than turning into UTF-8 behind the user's back.
 */
class TextFilesTest {

    private val cp949: Charset = Charset.forName("x-windows-949")

    @Test
    fun `plain utf-8 is read as utf-8 and round-trips`() {
        val bytes = "hello 안녕\nsecond line\n".toByteArray(Charsets.UTF_8)
        val loaded = TextFiles.decode(bytes)
        assertEquals(Charsets.UTF_8, loaded.charset)
        assertFalse(loaded.hasBom)
        assertEquals("hello 안녕\nsecond line\n", loaded.text)
        assertArrayEquals(bytes, TextFiles.encode(loaded.text, loaded))
    }

    @Test
    fun `a cp949 korean file is recognised, not read as mojibake`() {
        val text = "한글 문서입니다\n둘째 줄\n"
        val bytes = text.toByteArray(cp949)
        val loaded = TextFiles.decode(bytes)
        assertEquals("the CP949 file should be recognised", cp949, loaded.charset)
        assertEquals(text, loaded.text)
        // Saving keeps it CP949, byte for byte.
        assertArrayEquals(bytes, TextFiles.encode(loaded.text, loaded))
    }

    @Test
    fun `an edit to a cp949 file is saved back as cp949`() {
        val loaded = TextFiles.decode("처음\n".toByteArray(cp949))
        val edited = loaded.text + "추가된 줄\n"
        assertArrayEquals("처음\n추가된 줄\n".toByteArray(cp949), TextFiles.encode(edited, loaded))
    }

    @Test
    fun `a utf-8 byte-order mark is stripped for reading and restored on save`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + "with a mark\n".toByteArray(Charsets.UTF_8)
        val loaded = TextFiles.decode(bytes)
        assertTrue(loaded.hasBom)
        assertEquals("with a mark\n", loaded.text)
        assertArrayEquals(bytes, TextFiles.encode(loaded.text, loaded))
    }

    @Test
    fun `windows line endings are kept on save`() {
        val bytes = "line one\r\nline two\r\n".toByteArray(Charsets.UTF_8)
        val loaded = TextFiles.decode(bytes)
        // The editor sees plain \n ...
        assertEquals("line one\nline two\n", loaded.text)
        // ... and the save puts the \r\n back.
        assertArrayEquals(bytes, TextFiles.encode(loaded.text, loaded))
    }

    @Test
    fun `unix line endings stay unix`() {
        val bytes = "a\nb\n".toByteArray(Charsets.UTF_8)
        val loaded = TextFiles.decode(bytes)
        assertEquals("\n", loaded.newline)
        assertArrayEquals(bytes, TextFiles.encode(loaded.text, loaded))
    }

    @Test
    fun `which files the viewer opens`() {
        assertTrue(TextFiles.looksTextual("notes.txt"))
        assertTrue(TextFiles.looksTextual("subtitles.SRT"))
        assertTrue(TextFiles.looksTextual("build.gradle"))
        assertTrue(TextFiles.looksTextual(".gitignore"))
        assertTrue(TextFiles.looksTextual("config.json"))
        // A docx is a zip; it must not be dragged into the text viewer.
        assertFalse(TextFiles.looksTextual("report.docx"))
        assertFalse(TextFiles.looksTextual("photo.jpg"))
        assertFalse(TextFiles.looksTextual("noextension"))
    }
}
