package org.filezilla.android.files

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Remembering which app opens which kind of file.
 *
 * The bug behind this: tapping a file built an `ACTION_VIEW` and hoped.
 * That answers well for a photo and badly for most of what a file manager
 * holds -- Android calls a `.srt` an `application/x-subrip`, almost nothing
 * declares that, and the wildcard the app fell back on matches very little
 * on a modern phone. So a tap said "no app can open this" on a phone with
 * several apps that would have opened it.
 *
 * Asking is the fix and remembering is what makes it bearable, so what
 * matters here is that the memory is keyed on what the user was actually
 * choosing about, and that it can be taken back.
 */
@RunWith(RobolectricTestRunner::class)
class FileAssociationsTest {

    private lateinit var associations: FileAssociations

    @Before
    fun setUp() {
        associations = FileAssociations(ApplicationProvider.getApplicationContext())
    }

    private val player = ComponentName("com.example.player", "com.example.player.Main")
    private val editor = ComponentName("com.example.editor", "com.example.editor.Main")

    @Test
    fun `nothing is remembered to begin with`() {
        assertNull(associations.appFor("mkv"))
    }

    @Test
    fun `a choice comes back`() {
        associations.remember("mkv", player)

        assertEquals(player, associations.appFor("mkv"))
    }

    /** The point of keying on the extension: one kind of file, one answer. */
    @Test
    fun `a choice for one kind is not a choice for another`() {
        associations.remember("mkv", player)

        assertNull(associations.appFor("srt"))
    }

    @Test
    fun `choosing again replaces the old choice`() {
        associations.remember("srt", player)
        associations.remember("srt", editor)

        assertEquals(editor, associations.appFor("srt"))
    }

    /**
     * A choice that cannot be unmade is a trap: pick the wrong app once for
     * a kind of file you open daily and it is wrong for ever.
     */
    @Test
    fun `a choice can be taken back`() {
        associations.remember("mkv", player)
        associations.forget("mkv")

        assertNull(associations.appFor("mkv"))
    }

    /** Extensions are written both ways and mean the same kind of file. */
    @Test
    fun `case in the extension makes no difference`() {
        associations.remember("MKV", player)

        assertEquals(player, associations.appFor("mkv"))
        assertEquals(player, associations.appForFile("HOLIDAY.MKV"))
    }

    @Test
    fun `a file finds the choice made for its kind`() {
        associations.remember("srt", editor)

        assertEquals(editor, associations.appForFile("A.Big.Bold.Journey.ko.srt"))
    }

    /** The last dot, as everywhere else: "archive.tar.gz" is a gz. */
    @Test
    fun `the extension is the last one`() {
        associations.remember("gz", editor)

        assertEquals(editor, associations.appForFile("archive.tar.gz"))
    }

    /**
     * A dotfile's name is not its extension, and the rule has to be the
     * same one the type lookup uses or the two would disagree about what
     * kind of file a .bashrc is.
     */
    @Test
    fun `a dotfile has no kind to remember against`() {
        assertNull(FileAssociations.extensionOf(".bashrc"))
        assertNull(associations.appForFile(".bashrc"))
    }

    @Test
    fun `a name with no extension has no kind`() {
        assertNull(FileAssociations.extensionOf("README"))
        assertNull(associations.appForFile("README"))
    }

    /** What the management list shows, in an order that does not shuffle. */
    @Test
    fun `every choice is listed, sorted`() {
        associations.remember("srt", editor)
        associations.remember("mkv", player)

        assertEquals(listOf("mkv" to player, "srt" to editor), associations.all())
    }

    @Test
    fun `a forgotten choice leaves the list`() {
        associations.remember("srt", editor)
        associations.remember("mkv", player)
        associations.forget("mkv")

        assertEquals(listOf("srt" to editor), associations.all())
    }
}
