package org.filezilla.android.viewer

/**
 * A rough syntax highlighter: enough colour to read code by, no more.
 *
 * Not a parser. A viewer wants comments to recede, strings and numbers to
 * stand out, and keywords to catch the eye -- and it wants that in one pass
 * over any of a dozen languages without a grammar for each. So this scans
 * characters, not tokens, and knows only the four things every C-ish
 * language, JSON and XML share: where a comment runs to, where a string
 * begins and ends, what a number looks like, and a short list of words worth
 * lighting up. A false colour here costs nothing; the text is still the text.
 */
object Syntax {

    enum class Token { KEYWORD, STRING, COMMENT, NUMBER }

    /** A run of one colour, half-open [start, end). */
    data class Span(val start: Int, val end: Int, val token: Token)

    enum class Lang { PLAIN, CLIKE, JSON, XML }

    /**
     * Past this many characters, highlighting is dropped and the text shown
     * plain. Re-scanning a very large file on the way to the screen is slower
     * than it is worth, and colour on something that big is not what the file
     * is being opened for.
     */
    private const val MAX_HIGHLIGHT = 200_000

    fun langFor(name: String): Lang {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "json", "json5" -> Lang.JSON
            "xml", "html", "htm", "svg" -> Lang.XML
            in CLIKE_EXTENSIONS -> Lang.CLIKE
            else -> Lang.PLAIN
        }
    }

    /** The coloured runs of [text], or empty for none. */
    fun spans(text: String, lang: Lang): List<Span> {
        if (lang == Lang.PLAIN || text.length > MAX_HIGHLIGHT) return emptyList()
        return when (lang) {
            Lang.CLIKE -> scanClike(text, CLIKE_KEYWORDS, hashComment = true)
            Lang.JSON -> scanClike(text, JSON_KEYWORDS, hashComment = false)
            Lang.XML -> scanXml(text)
            Lang.PLAIN -> emptyList()
        }
    }

    // ------------------------------------------------------------- scanners

    private fun scanClike(text: String, keywords: Set<String>, hashComment: Boolean): List<Span> {
        val spans = ArrayList<Span>()
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    val end = lineEnd(text, i)
                    spans += Span(i, end, Token.COMMENT); i = end
                }
                hashComment && c == '#' -> {
                    val end = lineEnd(text, i)
                    spans += Span(i, end, Token.COMMENT); i = end
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val end = blockEnd(text, i + 2, "*/")
                    spans += Span(i, end, Token.COMMENT); i = end
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val end = stringEnd(text, i, c)
                    spans += Span(i, end, Token.STRING); i = end
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val end = identEnd(text, i)
                    if (text.substring(i, end) in keywords) spans += Span(i, end, Token.KEYWORD)
                    i = end
                }
                c.isDigit() -> {
                    val end = numberEnd(text, i)
                    spans += Span(i, end, Token.NUMBER); i = end
                }
                else -> i++
            }
        }
        return spans
    }

    private fun scanXml(text: String): List<Span> {
        val spans = ArrayList<Span>()
        val n = text.length
        var i = 0
        while (i < n) {
            if (text.startsWith("<!--", i)) {
                val end = blockEnd(text, i + 4, "-->")
                spans += Span(i, end, Token.COMMENT); i = end
                continue
            }
            if (text[i] == '<') {
                val start = i
                i++
                while (i < n && (text[i] == '/' || text[i] == '?' || text[i] == '!')) i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == ':' || text[i] == '-' || text[i] == '_')) i++
                spans += Span(start, i, Token.KEYWORD)
                while (i < n && text[i] != '>') {
                    val c = text[i]
                    if (c == '"' || c == '\'') {
                        val end = stringEnd(text, i, c)
                        spans += Span(i, end, Token.STRING); i = end
                    } else {
                        i++
                    }
                }
                if (i < n) { spans += Span(i, i + 1, Token.KEYWORD); i++ }
                continue
            }
            i++
        }
        return spans
    }

    // --------------------------------------------------------------- pieces

    private fun lineEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    private fun blockEnd(text: String, from: Int, close: String): Int {
        val at = text.indexOf(close, from)
        return if (at < 0) text.length else at + close.length
    }

    /** Past the closing quote, honouring `\` escapes; to the line's end if unterminated. */
    private fun stringEnd(text: String, open: Int, quote: Char): Int {
        var i = open + 1
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '\\' -> i += 2
                c == quote -> return i + 1
                c == '\n' && quote != '`' -> return i // a normal string does not cross a line
                else -> i++
            }
        }
        return n
    }

    private fun identEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) i++
        return i
    }

    private fun numberEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '.' || text[i] == '_')) i++
        return i
    }

    // ------------------------------------------------------------- tables

    private val CLIKE_EXTENSIONS: Set<String> = (
        "kt kts java py js mjs cjs ts tsx jsx css scss sh bash zsh c h cpp cc hpp " +
            "rs go rb php pl lua sql yaml yml toml gradle cmake ini conf cfg properties env"
        ).split(" ").toSet()

    private val JSON_KEYWORDS = setOf("true", "false", "null")

    // A union across the languages above. A viewer does not need to know which
    // language it is looking at to know that these words are usually keywords;
    // the odd one lit up in a file that does not use it is harmless.
    private val CLIKE_KEYWORDS: Set<String> = (
        "abstract and as assert async await base bool boolean break byte case catch " +
            "char class const constructor continue data debugger def default defer del " +
            "delete do double elif else end enum event export extends false final finally " +
            "float fn for foreach from fun func function global go goto if impl implements " +
            "import in init inline instanceof int interface internal is lambda late let " +
            "match module mut namespace new next nil none not null object of open operator " +
            "or override package pass private protected public raise readonly rec ref " +
            "register return sealed self short signed sizeof static str string struct super " +
            "switch synchronized template then this throw throws trait true try type typedef " +
            "typeof union unsafe unsigned until use using val var virtual void volatile when " +
            "where while with yield"
        ).split(" ").toSet()
}
