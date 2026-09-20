package org.filezilla.android.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That a menu comes out of the button that opened it.
 *
 * The bug: the overflow's IconButton and its DropdownMenu were siblings in
 * the pane header's Row. A DropdownMenu anchors to its own parent layout
 * node, so the anchor was the Row -- which spans the whole screen -- and a
 * menu opened from a button at the top right slid in at the far left of the
 * phone, under nothing at all.
 *
 * Read from the source because there is nothing to assert about it at
 * runtime without measuring a popup's window position, and because the fix
 * is one line that is very easy to lose in a later tidy-up: the Box that
 * wraps the two together.
 */
class MenuAnchorTest {

    private val sources: List<File>
        get() = File("src/main/kotlin/org/filezilla/android/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `every menu is wrapped with the button that opens it`() {
        val loose = mutableListOf<String>()
        for (file in sources) {
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (!line.contains("DropdownMenu(expanded")) return@forEachIndexed
                // The button and the menu have to sit inside one container,
                // and it has to be opened close by -- the twenty lines above
                // cover the button, its icon and its content description.
                val above = lines.subList(maxOf(0, index - 20), index)
                val wrapped = above.any { it.trimEnd().endsWith("Box {") } ||
                    above.any { it.contains("ExposedDropdownMenuBox") }
                if (!wrapped) loose += "${file.name}:${index + 1}"
            }
        }

        assertTrue(
            "a menu with no container to anchor it opens at the edge of the screen: $loose",
            loose.isEmpty(),
        )
    }

    /** And the source really is being read, or the above passes on nothing. */
    @Test
    fun `there are menus to check`() {
        val menus = sources.sumOf { file ->
            file.readLines().count { it.contains("DropdownMenu(expanded") }
        }

        assertTrue("found no menus at all; the scan is looking in the wrong place", menus > 0)
    }
}
