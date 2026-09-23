package org.filezilla.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One glyph, one verb.
 *
 * The audit that produced this found the same picture standing for different
 * things all over the app, and the user found the worst of it before we did:
 * the transfer list's top-right list icon meant "clear the finished rows" --
 * something that cannot be undone -- and read as "show the list", on a
 * screen that already was the list. Beside it, SwapVert, which is Material's
 * sorting glyph, meant "start transferring".
 *
 * That is not a thing a compiler can see and not a thing a screenshot shows
 * unless you already know what each button does. So the pairings are written
 * down here, and a second meaning for a glyph that already has one fails.
 *
 * This is about the *symbols*, not the wording; WordingTest covers the words.
 */
class IconMeaningTest {

    private val sources: List<File>
        get() = File("src/main/kotlin/org/filezilla/android/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList() +
            File("src/main/kotlin/org/filezilla/android/service")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }

    /**
     * Every glyph in the app paired with the name of what it says it does.
     *
     * Three shapes, because the app writes them three ways: the glyph then
     * its description, a description then its glyph, and the menu helper
     * that takes the string first. What this cannot see is a pairing
     * decided at runtime -- `if (isLocal) Open else Download` with a
     * matching conditional description -- and those are left out rather
     * than guessed at. There are four of them, they are all the same
     * one-glyph-one-verb shape, and each is beside its own comment saying
     * why it changes.
     */
    private fun pairings(): List<Pair<String, String>> {
        val glyph = """Icons\.(?:AutoMirrored\.)?(?:Filled|Outlined)\.(\w+)"""
        val named = """stringResource\(\s*R\.string\.(\w+)"""
        val shapes = listOf(
            // Icon(Icons.Filled.X, contentDescription = stringResource(R.string.y
            Regex("""$glyph\s*,\s*contentDescription\s*=\s*$named""", RegexOption.DOT_MATCHES_ALL)
                to (1 to 2),
            // icon = Icons.Filled.X, description = stringResource(R.string.y
            Regex("""icon\s*=\s*$glyph\s*,\s*description\s*=\s*$named""", RegexOption.DOT_MATCHES_ALL)
                to (1 to 2),
            // Item(R.string.y, Icons.Filled.X)
            Regex("""Item\(\s*R\.string\.(\w+)\s*,\s*$glyph""") to (2 to 1),
        )
        return sources.flatMap { file ->
            val text = file.readText()
            shapes.flatMap { (pattern, order) ->
                pattern.findAll(text).map {
                    it.groupValues[order.first] to it.groupValues[order.second]
                }
            }
        }.distinct()
    }

    /**
     * What each glyph is allowed to mean.
     *
     * Written out rather than derived, because the rule is about meaning and
     * nothing in the source carries meaning. One glyph may cover several
     * entries only where the *verb* is the same and the object differs --
     * closing a filter and closing a panel are both closing. Where the verb
     * differs, so must the picture.
     *
     * Adding a line here is the point: it makes "what does this symbol
     * already mean" a question somebody answers on purpose, which is
     * exactly what had stopped happening.
     */
    private val meanings: Map<String, Set<String>> = mapOf(
        // Closing. None of these destroys anything -- taking a transfer out
        // of the queue, which does, is Cancel below, and keeping the two
        // apart is most of what this test is for.
        "Close" to setOf("action_cancel", "filter_clear", "menu_select_none", "search_close"),
        // Taking something out for good.
        "Cancel" to setOf("queue_remove"),
        "Delete" to setOf("action_delete_selected", "sites_delete"),
        // Clearing a list of what is finished with. The transfer list says
        // this in words now -- three kinds of clearing no three pictures
        // would tell apart -- so the broom is the log screen's alone.
        "DeleteSweep" to setOf("log_clear"),
        // Making transfers go, whether one of them or all of them.
        "PlayArrow" to setOf("queue_resume", "queue_start_all"),
        "Pause" to setOf("queue_pause", "queue_pause_all"),
        "Refresh" to setOf("queue_retry_now", "browse_refresh"),
        // A menu of choices. Not the settings sheet, which is a gear: a ⋮
        // that opens a panel with one switch in it is a promise unkept.
        "MoreVert" to setOf("browse_more", "menu_more"),
        // Chosen: a row, an option, either way the same idea.
        "Check" to setOf("browse_select", "chosen"),
        // Renaming a file and editing a server are the same act on
        // different things: changing what something is called or is.
        "Edit" to setOf("action_rename_selected", "sites_edit"),
        "ContentCut" to setOf("action_cut"),
        "ContentCopy" to setOf("action_copy"),
        // Making a folder, from the menu and from the paste bar. Two
        // routes to one act, and the bar's exists because the round button
        // that usually does it is hidden while the bar is up.
        "CreateNewFolder" to setOf("new_folder_title", "fab_new_folder"),
        "Upload" to setOf("browse_upload"),
        "CheckCircle" to setOf("menu_select"),
        "DoneAll" to setOf("menu_select_all"),
        "Tune" to setOf("menu_view_options"),
        // Which app opens which kind of file.
        "AppShortcut" to setOf("menu_associations"),
        "Share" to setOf("action_share_selected"),
        "Download" to setOf("action_download_selected", "browse_download"),
        "Search" to setOf("menu_filter"),
        "Add" to setOf("sites_add"),
        "ArrowBack" to setOf("action_back"),
        // The player's rotate switch: free to turn with the phone, or held.
        "ScreenRotation" to setOf("action_rotate"),
        "ScreenLockRotation" to setOf("action_rotate_lock"),
        // The player's subtitle settings -- which track, and how it looks.
        "Subtitles" to setOf("action_subtitles"),
        // The picture's fit: letterbox, crop-to-fill, or stretch.
        "AspectRatio" to setOf("action_aspect"),
        // Packing files into an archive, and unpacking one. Verbs, a pair,
        // and the only zip glyphs in the app -- the row icons for archives
        // are the app's own tiles, not these.
        "FolderZip" to setOf("archive_compress"),
        "Unarchive" to setOf("archive_extract_all", "archive_extract_picked"),
        "KeyboardArrowUp" to setOf("sites_move_up"),
        "KeyboardArrowDown" to setOf("sites_move_down"),
    )

    @Test
    fun `every icon means what it is declared to mean`() {
        val undeclared = pairings().filterNot { (glyph, name) ->
            meanings[glyph]?.contains(name) == true
        }.sortedBy { it.first }

        assertEquals(
            "these symbols have not been given a meaning, or have been given a second one: " +
                undeclared.joinToString { "${it.first} -> ${it.second}" },
            emptyList<Pair<String, String>>(),
            undeclared,
        )
    }

    /** And nothing declared has quietly stopped being used. */
    @Test
    fun `nothing is declared that the app does not use`() {
        val used = pairings().toSet()
        val stale = meanings.flatMap { (glyph, names) -> names.map { glyph to it } }
            .filterNot { it in used }

        assertEquals("declared and then dropped: $stale", emptyList<Pair<String, String>>(), stale)
    }

    /**
     * The three the user actually reported, pinned by name.
     *
     * The test above would let all three back in as long as each glyph were
     * used only once, so the specific mistakes are nailed down too.
     */
    @Test
    fun `the transfer list's buttons say what they do`() {
        val byAction = pairings().associate { (glyph, name) -> name to glyph }

        assertEquals("clearing the log is not a list", "DeleteSweep", byAction["log_clear"])
        assertEquals("starting is not sorting", "PlayArrow", byAction["queue_start_all"])
        assertEquals("stopping everything is the pause glyph", "Pause", byAction["queue_pause_all"])
        assertEquals(
            "taking a transfer out of the queue is not a closing",
            "Cancel",
            byAction["queue_remove"],
        )
    }

    /**
     * The tidying actions are words, and stay words.
     *
     * Three kinds of clearing -- the finished ones, the failed ones, the
     * whole list -- are three different acts that no three pictures would
     * tell apart. The old bar tried, with a broom, and the user could not
     * read it. A glyph appearing against any of them means somebody went
     * back to guessing.
     */
    @Test
    fun `emptying the transfer list is said in words`() {
        val byAction = pairings().associate { (glyph, name) -> name to glyph }

        for (named in listOf("queue_clear_finished", "queue_clear_failed", "queue_clear_all")) {
            assertEquals("$named was given an icon", null, byAction[named])
        }
    }

    /** And the scan is really reading the app, or the above passes on nothing. */
    @Test
    fun `there are icons to check`() {
        val found = pairings()

        assertTrue("found no icons at all; the scan looks in the wrong place", found.size > 15)
    }

    /**
     * One weight throughout.
     *
     * Outlined beside filled reads as two icon sets in one app, and it got
     * in the way once already: a single CheckCircleOutline among thirty
     * filled glyphs.
     */
    @Test
    fun `the icons are all the same weight`() {
        val outlined = sources.flatMap { file ->
            Regex("""Icons\.Outlined\.(\w+)""").findAll(file.readText()).map { it.groupValues[1] }
        }.distinct()

        assertEquals("outlined icons among filled ones", emptyList<String>(), outlined)
    }
}
