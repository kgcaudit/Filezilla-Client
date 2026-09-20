package org.filezilla.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That there is one dialog, wearing nine hats.
 *
 * There were nine, and no two agreed: two carried an icon and seven did not,
 * two offered a way out and seven did not, and one question -- "delete these?"
 * -- had three separate implementations, one of which could not be opened at
 * all because nothing ever set the flag that showed it.
 *
 * Drift is not the only cost. The same question written twice is the same fix
 * applied once: the new-file prompt hard-coded its label where the other
 * prompt took one, so it showed the site editor's hint in its name box, and
 * the label a reader would have to change to correct it was three files away
 * in a function that screen never called.
 *
 * Checked by reading the sources, because it is a fact about how the screens
 * are built and not about what any one of them looks like. A tenth dialog
 * written from scratch compiles and renders perfectly well; it just is not
 * this app's dialog, and that is what this notices.
 */
class DialogShellTest {

    private val uiDir = File("src/main/kotlin/org/filezilla/android/ui")
    private val shell = "Dialogs.kt"

    private fun sources() = uiDir.walkTopDown().filter { it.extension == "kt" }

    @Test
    fun `only the shell builds a dialog out of Material's`() {
        val rolledTheirOwn = sources()
            .filter { it.name != shell }
            .filter { Regex("""\bAlertDialog\(""").containsMatchIn(it.readText()) }
            .map { it.name }
            .sorted()
            .toList()

        assertEquals(emptyList<String>(), rolledTheirOwn)
    }

    /** And the shell is really used, rather than sitting there unused. */
    @Test
    fun `the screens ask through the shell`() {
        val users = sources()
            .filter { it.name != shell }
            .filter { Regex("""\bOlo(Dialog|PromptDialog|ConfirmDialog|InfoDialog)\(""").containsMatchIn(it.readText()) }
            .map { it.name }
            .sorted()
            .toList()

        assertEquals(
            listOf("BrowseMenus.kt", "BrowseScreen.kt", "ConflictDialog.kt",
                   "FilePanes.kt", "MainActivity.kt", "SiteEditor.kt"),
            users,
        )
    }

    /**
     * A name is asked for in one place.
     *
     * Two prompts is how the label came to be wrong in one of them; this is
     * the specific shape of that bug, rather than the general rule above.
     */
    @Test
    fun `there is one text prompt`() {
        val prompts = sources()
            .flatMap { file ->
                Regex("""fun (\w*(?:Prompt|Name)Dialog)\(""")
                    .findAll(file.readText())
                    .map { "${file.name}:${it.groupValues[1]}" }
            }
            .sorted()
            .toList()

        assertEquals(listOf("Dialogs.kt:OloPromptDialog"), prompts)
    }

    /** Nothing shows a dialog that nothing can open. */
    @Test
    fun `every dialog has something that opens it`() {
        val dead = mutableListOf<String>()
        for (file in sources()) {
            val text = file.readText()
            for (match in Regex("""var (\w+) by remember \{ mutableStateOf\(false\) }""").findAll(text)) {
                val flag = match.groupValues[1]
                val raised = Regex("""\b$flag = true\b""").containsMatchIn(text)
                val shows = Regex("""\bif \($flag\)""").containsMatchIn(text)
                if (shows && !raised) dead += "${file.name}:$flag"
            }
        }
        assertTrue("nothing raises these: $dead", dead.isEmpty())
    }
}

/**
 * That every box you type into is the app's box.
 *
 * Material's outlined field stands 56dp tall with sixteen points of air
 * above and below the text, which is a lot of frame around one short word --
 * the site editor paid it nine times over. [OloTextField] is the same field
 * with the padding brought in, and the point of having it is that nothing
 * reaches past it: a screen written later gets the height everything else
 * has without anybody remembering to ask for it.
 *
 * The three pickers are why this is worth a test rather than a convention.
 * They were converted one pass after the text boxes were, so for a while the
 * site editor showed six fields at one height and three at another, on one
 * form, and nothing said a word about it.
 */
class TextFieldShellTest {

    private val uiDir = File("src/main/kotlin/org/filezilla/android/ui")

    @Test
    fun `only the shared field builds on Material's`() {
        val raw = uiDir.walkTopDown()
            .filter { it.extension == "kt" && it.name != "OloTextField.kt" }
            .filter { Regex("""\b(Outlined)?TextField\(""").containsMatchIn(it.readText()) }
            .map { it.name }
            .sorted()
            .toList()

        assertEquals(emptyList<String>(), raw)
    }

    /** And it is what the screens use, rather than sitting there unused. */
    @Test
    fun `the screens type through the shared field`() {
        val users = uiDir.walkTopDown()
            .filter { it.extension == "kt" && it.name != "OloTextField.kt" }
            .filter { "OloTextField(" in it.readText() }
            .map { it.name }
            .sorted()
            .toList()

        assertEquals(listOf("BrowseScreen.kt", "Dialogs.kt", "SiteEditor.kt"), users)
    }
}
