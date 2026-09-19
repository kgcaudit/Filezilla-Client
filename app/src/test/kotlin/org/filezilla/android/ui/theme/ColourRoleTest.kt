package org.filezilla.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * That the screens only ask for colours this theme has chosen.
 *
 * The bug: a bar was drawn in `tertiaryContainer`, and this theme defines no
 * tertiary at all. Material fills an unset role from its own default palette,
 * so the bar came out pink -- on a screen where pink already means a failure.
 * It compiled, it ran, and it looked like an error message.
 *
 * An unset role is therefore not an omission the compiler can catch but a
 * colour from somebody else's palette, which is why this is checked by
 * reading the source rather than by looking at a screenshot.
 */
class ColourRoleTest {

    private val themeFile = File("src/main/kotlin/org/filezilla/android/ui/theme/Theme.kt")
    private val uiDir = File("src/main/kotlin/org/filezilla/android/ui")

    /** Every `role = ` the theme assigns in its two schemes. */
    private fun rolesDefined(): Set<String> =
        Regex("""^\s{4}(\w+)\s*=""", RegexOption.MULTILINE)
            .findAll(themeFile.readText())
            .map { it.groupValues[1] }
            .toSet()

    /** Every `colorScheme.role` the screens ask for. */
    private fun rolesUsed(): Map<String, List<String>> {
        val used = mutableMapOf<String, MutableList<String>>()
        for (file in uiDir.walkTopDown().filter { it.extension == "kt" }) {
            for (match in Regex("""colorScheme\.(\w+)""").findAll(file.readText())) {
                used.getOrPut(match.groupValues[1]) { mutableListOf() } += file.name
            }
        }
        return used
    }

    @Test
    fun `no screen asks for a colour the theme never set`() {
        val defined = rolesDefined()
        val missing = rolesUsed()
            .filterKeys { it !in defined }
            .map { (role, files) -> "$role (${files.distinct().joinToString()})" }
            .sorted()

        assertEquals(emptyList<String>(), missing)
    }

    /**
     * And the check itself is looking at something. A regex that matched
     * nothing would pass the test above while watching no colours at all.
     */
    @Test
    fun `the theme and the screens are both actually being read`() {
        val defined = rolesDefined()
        val used = rolesUsed()

        // Roles the app plainly does use and the theme plainly does set.
        for (role in listOf("primary", "surface", "error", "onSurfaceVariant")) {
            assert(role in defined) { "the theme reader found no '$role'" }
        }
        assert("primaryContainer" in used) { "the screen reader found no colorScheme use" }
    }
}
