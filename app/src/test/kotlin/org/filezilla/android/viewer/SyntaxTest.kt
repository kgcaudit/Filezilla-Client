package org.filezilla.android.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enough colour to read code by, and -- the part that is easy to get wrong --
 * knowing where one kind of thing ends and the next begins: a `//` inside a
 * string is not a comment, and a string that never closes does not swallow
 * the rest of the file.
 */
class SyntaxTest {

    private fun spanText(source: String, span: Syntax.Span) = source.substring(span.start, span.end)

    @Test
    fun `the language is chosen by extension`() {
        assertEquals(Syntax.Lang.CLIKE, Syntax.langFor("Main.kt"))
        assertEquals(Syntax.Lang.JSON, Syntax.langFor("config.json"))
        assertEquals(Syntax.Lang.XML, Syntax.langFor("layout.xml"))
        assertEquals(Syntax.Lang.PLAIN, Syntax.langFor("notes.txt"))
    }

    @Test
    fun `a line comment is one span to the end of the line`() {
        val src = "val x = 1 // trailing\nval y = 2\n"
        val comment = Syntax.spans(src, Syntax.Lang.CLIKE).single { it.token == Syntax.Token.COMMENT }
        assertEquals("// trailing", spanText(src, comment))
    }

    @Test
    fun `a slash-slash inside a string is not a comment`() {
        val src = """val url = "https://example.com/path""""
        val spans = Syntax.spans(src, Syntax.Lang.CLIKE)
        assertTrue("the // is inside the string, so there is no comment span", spans.none { it.token == Syntax.Token.COMMENT })
        val string = spans.single { it.token == Syntax.Token.STRING }
        assertEquals("\"https://example.com/path\"", spanText(src, string))
    }

    @Test
    fun `keywords and numbers are lit, ordinary words are not`() {
        val src = "fun add() { return 42 }"
        val spans = Syntax.spans(src, Syntax.Lang.CLIKE)
        assertEquals("fun", spanText(src, spans.first { it.token == Syntax.Token.KEYWORD }))
        assertEquals("42", spanText(src, spans.single { it.token == Syntax.Token.NUMBER }))
        // "add" is an identifier, not a keyword.
        assertTrue(spans.none { it.token == Syntax.Token.KEYWORD && spanText(src, it) == "add" })
    }

    @Test
    fun `a hash starts a comment in clike but not in json`() {
        val src = "# a shell comment\n"
        assertEquals(1, Syntax.spans(src, Syntax.Lang.CLIKE).count { it.token == Syntax.Token.COMMENT })
        assertEquals(0, Syntax.spans(src, Syntax.Lang.JSON).count { it.token == Syntax.Token.COMMENT })
    }

    @Test
    fun `json lights strings, numbers and the three literals`() {
        val src = """{"on": true, "n": 12.5}"""
        val spans = Syntax.spans(src, Syntax.Lang.JSON)
        assertTrue(spans.any { it.token == Syntax.Token.KEYWORD && spanText(src, it) == "true" })
        assertTrue(spans.any { it.token == Syntax.Token.NUMBER && spanText(src, it) == "12.5" })
        assertTrue(spans.any { it.token == Syntax.Token.STRING && spanText(src, it) == "\"on\"" })
    }

    @Test
    fun `xml lights tags, attribute values and comments`() {
        val src = """<a href="x">text</a><!-- note -->"""
        val spans = Syntax.spans(src, Syntax.Lang.XML)
        assertTrue(spans.any { it.token == Syntax.Token.STRING && spanText(src, it) == "\"x\"" })
        assertTrue(spans.any { it.token == Syntax.Token.COMMENT && spanText(src, it) == "<!-- note -->" })
        assertTrue(spans.any { it.token == Syntax.Token.KEYWORD })
    }

    @Test
    fun `an unterminated string stops at the line, not the file`() {
        val src = "val a = \"oops\nval b = 2\n"
        val string = Syntax.spans(src, Syntax.Lang.CLIKE).single { it.token == Syntax.Token.STRING }
        assertEquals("\"oops", spanText(src, string))
    }
}
