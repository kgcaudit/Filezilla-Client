package org.filezilla.android.files

import org.junit.Assert.assertEquals
import org.junit.Test

class BulkRenameTest {

    private fun file(name: String) = BulkRename.Target(name, isDirectory = false)
    private fun folder(name: String) = BulkRename.Target(name, isDirectory = true)

    @Test
    fun `prefix and suffix wrap the stem and keep the extension`() {
        val changes = BulkRename.apply(
            listOf(file("a.txt"), file("b.jpg")),
            BulkRename.Rule(prefix = "x_", suffix = "_y"),
        )
        assertEquals(
            listOf("x_a_y.txt", "x_b_y.jpg"),
            changes.map { it.to },
        )
    }

    @Test
    fun `find and replace touches the stem, not the extension`() {
        val changes = BulkRename.apply(
            listOf(file("IMG_001.jpg")),
            BulkRename.Rule(find = "IMG", replace = "사진"),
        )
        assertEquals("사진_001.jpg", changes.single().to)
    }

    @Test
    fun `numbering pads to the width of the largest number`() {
        val targets = (1..3).map { file("clip$it.mp4") }
        // Start at 9 over three files reaches 11, so two digits throughout.
        val changes = BulkRename.apply(
            targets,
            BulkRename.Rule(find = "clip1", replace = "", numberFrom = 9),
        )
        // find "clip1" only rewrites the first; the point here is the numbers.
        assertEquals(listOf("09", "10", "11"), changes.map { it.to.substringBefore(".").takeLast(2) })
    }

    @Test
    fun `a folder keeps its whole name, dots and all`() {
        val changes = BulkRename.apply(
            listOf(folder("my.stuff")),
            BulkRename.Rule(suffix = "_z"),
        )
        // Not "my_z.stuff": a folder has no extension to protect.
        assertEquals("my.stuff_z", changes.single().to)
    }

    @Test
    fun `a dotfile is all stem, so its leading dot is kept`() {
        val changes = BulkRename.apply(
            listOf(file(".gitignore")),
            BulkRename.Rule(prefix = "x"),
        )
        assertEquals("x.gitignore", changes.single().to)
    }
}
