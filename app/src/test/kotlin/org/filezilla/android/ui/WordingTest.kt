package org.filezilla.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * That the app says one thing one way.
 *
 * Two kinds of mistake, both of which shipped and neither of which a compiler
 * or a screenshot catches.
 *
 * A string written for one screen borrowed by another: the new-file dialog
 * labelled its name box with the site editor's hint, so asking for a file
 * name said "optional -- the host is used if left blank". It is the right
 * sentence in the wrong place, which is the hardest kind to notice, because
 * every word of it is something the app really does say somewhere.
 *
 * And the same thing named two ways. A folder was a folder in sixteen places
 * and a directory in three, so the plus button offered "New folder" and the
 * menu three inches away offered "New directory" -- two names for one thing,
 * on one screen, in one app.
 */
class WordingTest {

    private val resDir = File("src/main/res")
    private val kotlinDir = File("src/main/kotlin")

    private fun strings(values: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(resDir, "$values/strings.xml"))
        val out = mutableMapOf<String, String>()
        val root = doc.documentElement
        for (i in 0 until root.childNodes.length) {
            val node = root.childNodes.item(i) as? Element ?: continue
            if (node.tagName == "string") out[node.getAttribute("name")] = node.textContent
        }
        return out
    }

    /**
     * The words that lost.
     *
     * Each was a real choice between two names for one thing, settled in
     * favour of the one already in the majority and the one an Android file
     * app uses. The exceptions are named, not waved through: "host" survives
     * where it means a hostname lookup, because that is what failed.
     */
    private val retired = mapOf(
        "디렉터리" to "폴더",
        "지우기" to "삭제 (파일을 지우지 않는 곳은 해제)",
        "내려받기" to "다운로드",
    )

    private val hostIsRight = setOf("detail_host", "fail_host_advice", "field_host")

    @Test
    fun `no string uses a word the app decided against`() {
        val found = strings("values-ko")
            .flatMap { (name, text) ->
                retired.filterKeys { it in text }.map { (word, instead) -> "$name: $word -> $instead" }
            }
            .sorted()

        assertEquals(emptyList<String>(), found)
    }

    /** And "host" only where a hostname is what is meant. */
    @Test
    fun `the address is a server everywhere except where it is a hostname`() {
        val stray = strings("values-ko")
            .filter { (name, text) -> "호스트" in text && name !in hostIsRight }
            .keys
            .sorted()

        assertEquals(emptyList<String>(), stray)
    }

    /**
     * That no string is shared by two screens that are asking different
     * things.
     *
     * Sharing is the right thing for a word like "Cancel" -- it is one word
     * and one meaning wherever it sits. It is the wrong thing for a sentence
     * about one screen's field, which is what went wrong. So the shared ones
     * are listed, and adding to the list is a decision somebody has to make
     * on purpose rather than by reaching for the nearest string.
     */
    @Test
    fun `only strings meant to be shared are shared`() {
        val names = strings("values").keys
        val sources = kotlinDir.walkTopDown().filter { it.extension == "kt" }
            .map { it.name to it.readText() }.toList()

        val shared = names.filter { name ->
            sources.count { (_, text) -> Regex("""R\.string\.$name\b""").containsMatchIn(text) } > 1
        }.sorted()

        // Verbs and nouns with one meaning, plus the screen names and the
        // three questions the panes and the old browser both ask.
        val allowed = listOf(
            "action_cancel", "action_delete", "confirm_delete_detail", "filter_clear",
            "menu_filter", "new_folder_detail", "new_folder_title", "pane_no_source",
            "prompt_name", "prompt_new_name", "prompt_rename_title", "props_unknown",
            "queue_speed_remaining", "rename_detail", "side_local", "sites_empty_detail",
            "title_log", "title_queue",
        ).sorted()

        assertEquals(allowed, shared)
    }
}
