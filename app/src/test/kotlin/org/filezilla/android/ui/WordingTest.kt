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

    /**
     * Where a hostname really is what is meant.
     *
     * The two failures are about a name lookup, and the other two are the
     * site editor's address field and the sentence under it. A field and its
     * own hint have to use one word between them -- calling the field "host"
     * and its hint "address" is the same two-names-for-one-thing this test
     * exists to stop, and it is a mistake this test's author made while
     * writing it.
     */
    private val hostIsRight = setOf("detail_host", "fail_host_advice", "field_host", "name_hint")

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
            // The verb a prompt's button carries. Two prompts each make
            // something and two each rename something, and they have to
            // say it the same way -- which is the whole reason the button
            // stopped saying "OK".
            "action_create", "action_change",
            // The share button's name and the title of the sheet it opens:
            // the sheet answers the button, same as queue_settings below.
            "action_share_selected",
            // Same again: the menu item and the title of the dialog it
            // opens. A menu that says one thing and a dialog that says
            // another reads as two features.
            "action_change_mode",
            // Our own "open with" sheet, and the system chooser it hands
            // off to: the same question asked by two pieces of machinery,
            // and it must read as one question.
            "open_with_title",
            // The mark on whatever option is in force, read out wherever one
            // is shown -- a chip in view options, a row in a picker's list.
            // One word, one meaning, and it has to be the same word or the
            // two controls would claim to mean different things.
            "chosen",
            "menu_filter",
            // Every ⋮ in the app is read out the same way. There are three
            // now -- a file row, a pane, the transfer list -- and they are
            // one control, so they are one word.
            "menu_more",
            "new_folder_detail", "new_folder_title", "pane_no_source",
            // The button that opens the transfer settings and the heading of
            // the sheet it opens: one name for one place, said twice on
            // purpose so the sheet answers the button.
            "queue_settings",
            "prompt_name", "prompt_new_name", "prompt_rename_title", "props_unknown",
            "queue_speed_remaining", "rename_detail", "side_local", "sites_empty_detail",
            "title_log", "title_queue",
        ).sorted()

        assertEquals(allowed, shared)
    }
}
