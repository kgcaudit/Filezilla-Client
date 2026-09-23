package org.filezilla.android.ui

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * SAMI (.smi) subtitles, turned into something the player can read.
 *
 * SAMI is the subtitle format most Korean films on a phone come with, and the
 * one media3 has no reader for -- so a .smi sitting beside a film used to be
 * passed over, and the film played with no subtitle at all. It is a simple
 * HTML-ish thing: a run of <SYNC Start=ms> marks, each starting a caption that
 * lasts until the next mark, with a blank one (a lone &nbsp;) to clear the
 * screen. That converts cleanly to WebVTT, which the player does read, so a
 * .smi is rewritten to a .vtt in the cache once and handed over as that.
 */
object SamiSubtitles {

    /**
     * Reads [sami] and writes an equivalent .vtt into [cacheDir], returning it,
     * or null if the file is not SAMI or holds no captions. The .vtt is named
     * from the source so the same file is not converted twice.
     */
    fun toVttFile(cacheDir: File, sami: File): File? = runCatching {
        val vtt = toVtt(decodeBytes(sami.readBytes())) ?: return null
        val out = File(cacheDir, "sami_" + keyOf(sami.path + ":" + sami.length()) + ".vtt")
        if (!out.exists()) out.writeText(vtt)
        out
    }.getOrNull()

    /** The SAMI body as WebVTT, or null if it does not look like SAMI. */
    fun toVtt(raw: String): String? {
        if (!raw.contains("<sync", ignoreCase = true)) return null
        val sync = Regex(
            "<sync\\b[^>]*\\bstart\\s*=\\s*\"?(\\d+)\"?[^>]*>(.*?)(?=<sync\\b|</body>|</sami>|$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        data class Cue(val start: Long, val text: String)
        val cues = mutableListOf<Cue>()
        for (match in sync.findAll(raw)) {
            val start = match.groupValues[1].toLongOrNull() ?: continue
            val text = match.groupValues[2]
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]*>", RegexOption.DOT_MATCHES_ALL), "")
                .replace("&nbsp;", " ", ignoreCase = true)
                .replace("&lt;", "<", ignoreCase = true)
                .replace("&gt;", ">", ignoreCase = true)
                .replace("&quot;", "\"", ignoreCase = true)
                .replace("&amp;", "&", ignoreCase = true)
                .lines().joinToString("\n") { it.trim() }.trim()
            cues += Cue(start, text)
        }
        if (cues.isEmpty()) return null

        val out = StringBuilder("WEBVTT\n\n")
        for (i in cues.indices) {
            val cue = cues[i]
            if (cue.text.isEmpty()) continue // a clear marker, nothing to show
            val end = cues.getOrNull(i + 1)?.start ?: (cue.start + 5_000)
            if (end <= cue.start) continue
            out.append(timestamp(cue.start)).append(" --> ").append(timestamp(end)).append('\n')
            out.append(cue.text).append("\n\n")
        }
        return out.toString()
    }

    private fun timestamp(ms: Long): String {
        val hours = ms / 3_600_000
        val minutes = (ms % 3_600_000) / 60_000
        val seconds = (ms % 60_000) / 1_000
        val millis = ms % 1_000
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    // SAMI files come as often in a Korean code page (MS949) as in UTF-8, and
    // reading one as the other turns the captions to nonsense. UTF-8 is tried
    // strictly first -- it fails cleanly on bytes that are not UTF-8 -- and
    // MS949 is the fallback.
    private fun decodeBytes(bytes: ByteArray): String {
        runCatching {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }
        return runCatching { String(bytes, charset("MS949")) }
            .getOrElse { String(bytes, Charsets.UTF_8) }
    }

    private fun keyOf(source: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
}
