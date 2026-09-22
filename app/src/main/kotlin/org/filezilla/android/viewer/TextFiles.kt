package org.filezilla.android.viewer

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Reading a text file into something an editor can show, and writing it back
 * the way it came.
 *
 * A phone is full of text in more than one encoding: a note saved by a modern
 * editor is UTF-8, but a great many Korean .txt and .srt files on the same
 * phone are CP949, and opening one as the other turns every Korean character
 * into mojibake. So the bytes are sniffed rather than assumed, and what was
 * sniffed is remembered -- the charset, the byte-order mark, the kind of line
 * ending -- so that saving an edit does not quietly rewrite a CP949 file as
 * UTF-8 or turn every line ending on its head.
 */
object TextFiles {

    /**
     * The largest file opened in the in-app viewer.
     *
     * A text editor holds the whole thing in memory and lays every line out
     * at once; past a few megabytes that is slow enough to feel broken, and
     * such a file is better handed to an app built for it. The cap is on the
     * bytes on disk, checked before any of it is read.
     */
    const val MAX_BYTES: Long = 4L * 1024 * 1024

    private val UTF8: Charset = Charsets.UTF_8
    private val UTF16LE: Charset = Charsets.UTF_16LE
    private val UTF16BE: Charset = Charsets.UTF_16BE

    // The same fallback the zip reader uses for names: CP949, the Windows
    // Korean code page, which is where most non-UTF-8 Korean text on a phone
    // comes from. Named the Java way rather than "MS949", which not every
    // Android build knows.
    private val CP949: Charset = Charset.forName("x-windows-949")

    /** How a file decoded: its text, and what it takes to write it back. */
    data class Loaded(
        val text: String,
        val charset: Charset,
        val hasBom: Boolean,
        /** The line ending the file used, restored on save. */
        val newline: String,
    )

    /** The extensions opened as text, edited or merely read. */
    private val TEXT_EXTENSIONS: Set<String> = buildSet {
        // Plain documents and logs.
        addAll("txt log md markdown csv tsv rtf ini conf cfg properties env".split(" "))
        // Subtitles, which are text and often the thing beside a film worth a look.
        addAll("srt smi vtt ass sub".split(" "))
        // Code and config, shown with highlighting. No extension here is a
        // binary format -- a .docx is a zip and must not land in the text
        // viewer, so it is deliberately left out.
        addAll(
            ("kt kts java py js mjs cjs ts tsx jsx json json5 xml html htm css scss " +
                "sh bash zsh c h cpp cc hpp rs go rb php pl lua sql yaml yml toml " +
                "gradle cmake gitignore").split(" "),
        )
    }

    /** Whether a name is one the text viewer opens. */
    fun looksTextual(name: String): Boolean {
        val cut = name.lastIndexOf('.')
        if (cut < 0) return false
        // A dotfile with no other dot -- ".gitignore" -- is named by what
        // follows its only dot, so an empty stem still has an extension.
        val extension = name.substring(cut + 1).lowercase()
        return extension.isNotEmpty() && extension in TEXT_EXTENSIONS
    }

    /**
     * Turns file bytes into text, sniffing the encoding.
     *
     * A byte-order mark settles it outright. Failing that, the bytes are run
     * through a strict UTF-8 decode: valid UTF-8 is distinctive enough that
     * text which decodes without a single malformed sequence is taken to be
     * UTF-8, and anything else falls back to CP949, which never rejects bytes
     * and so is the honest last resort rather than a second guess.
     */
    fun decode(bytes: ByteArray): Loaded {
        bomOf(bytes)?.let { (charset, mark) ->
            val text = String(bytes, mark.size, bytes.size - mark.size, charset)
            return Loaded(normalizeNewlines(text), charset, hasBom = true, newline = newlineOf(bytes))
        }
        val charset = if (isValidUtf8(bytes)) UTF8 else CP949
        return Loaded(normalizeNewlines(String(bytes, charset)), charset, hasBom = false, newline = newlineOf(bytes))
    }

    /**
     * Turns edited text back into bytes for the encoding it came in.
     *
     * The mark goes back if it was there, the line ending the file used is
     * restored from the single `\n` the editor works in, and the charset is
     * the one sniffed -- so a save is a change of content and nothing else.
     */
    fun encode(text: String, loaded: Loaded): ByteArray {
        val restored = if (loaded.newline == "\n") text else text.replace("\n", loaded.newline)
        val body = restored.toByteArray(loaded.charset)
        val mark = if (loaded.hasBom) bomBytes(loaded.charset) else ByteArray(0)
        return if (mark.isEmpty()) body else mark + body
    }

    /** The byte-order mark at the front, as (charset, mark bytes), or null. */
    private fun bomOf(bytes: ByteArray): Pair<Charset, ByteArray>? {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        return when {
            bytes.startsWith(utf8) -> UTF8 to utf8
            bytes.startsWith(le) -> UTF16LE to le
            bytes.startsWith(be) -> UTF16BE to be
            else -> null
        }
    }

    private fun bomBytes(charset: Charset): ByteArray = when (charset) {
        UTF16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        UTF16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        else -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    /** Whether every byte is valid UTF-8, decided by a strict decoder. */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        val decoder = UTF8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)) }.isSuccess
    }

    /** The dominant line ending, so a save keeps the file's own. */
    private fun newlineOf(bytes: ByteArray): String {
        var crlf = 0
        var lf = 0
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == '\n'.code.toByte()) {
                if (i > 0 && bytes[i - 1] == '\r'.code.toByte()) crlf++ else lf++
            }
            i++
        }
        return if (crlf > lf) "\r\n" else "\n"
    }

    /** Everything becomes `\n` for editing; the original is restored on save. */
    private fun normalizeNewlines(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
}
