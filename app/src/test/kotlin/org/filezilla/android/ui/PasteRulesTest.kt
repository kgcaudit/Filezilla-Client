package org.filezilla.android.ui

import org.filezilla.android.data.SiteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What may be put down where.
 *
 * These answers have to be right before anything is written. A folder pasted
 * into itself walks forever and starts copying on the way, so by the time the
 * filesystem objects there is already a half-made tree to clear up -- which
 * is why the refusal is a decision taken here rather than an error caught
 * later.
 */
class PasteRulesTest {

    private val site = SiteEntity(
        id = "s1", name = "NAS", host = "h", port = 21, user = "u",
        passwordCipher = "x", security = "PLAIN", transferMode = "DEFAULT",
        trustAllCertificates = false, initialPath = null,
    )

    private fun held(
        mode: ClipboardMode = ClipboardMode.COPY,
        source: PaneSource = PaneSource.Local,
        directory: String = "/storage/Movies",
        names: List<String> = listOf("2026"),
    ) = Clipboard(mode, source, directory, names)

    @Test
    fun `an ordinary copy is allowed`() {
        assertNull(PasteRules.refusal(held(), PaneSource.Local, "/storage/target"))
    }

    @Test
    fun `nothing held is refused`() {
        assertEquals(
            PasteRefusal.NOTHING_HELD,
            PasteRules.refusal(null, PaneSource.Local, "/storage"),
        )
        assertEquals(
            PasteRefusal.NOTHING_HELD,
            PasteRules.refusal(held(names = emptyList()), PaneSource.Local, "/storage"),
        )
    }

    /**
     * Moving into the folder the items already sit in does nothing, so the
     * button says so rather than appearing to work.
     */
    @Test
    fun `a move into the folder they came from is refused`() {
        assertEquals(
            PasteRefusal.ALREADY_THERE,
            PasteRules.refusal(held(mode = ClipboardMode.MOVE), PaneSource.Local, "/storage/Movies"),
        )
    }

    /** Copying there is a duplicate, which is a fair thing to want. */
    @Test
    fun `a copy into the folder they came from is allowed`() {
        assertNull(
            PasteRules.refusal(held(mode = ClipboardMode.COPY), PaneSource.Local, "/storage/Movies"),
        )
    }

    @Test
    fun `a folder cannot be pasted into itself`() {
        assertEquals(
            PasteRefusal.INTO_ITSELF,
            PasteRules.refusal(held(), PaneSource.Local, "/storage/Movies/2026"),
        )
    }

    @Test
    fun `a folder cannot be pasted into its own descendant`() {
        assertEquals(
            PasteRefusal.INTO_ITSELF,
            PasteRules.refusal(held(), PaneSource.Local, "/storage/Movies/2026/clips"),
        )
    }

    /**
     * One bad item spoils the paste, because the others would be written
     * first and then the whole thing would stop partway.
     */
    @Test
    fun `one unsafe item in a selection refuses the whole paste`() {
        val clipboard = held(names = listOf("safe.txt", "2026"))

        assertEquals(
            PasteRefusal.INTO_ITSELF,
            PasteRules.refusal(clipboard, PaneSource.Local, "/storage/Movies/2026"),
        )
    }

    /**
     * Why the check compares segments and not prefixes: "2026-old" is not
     * inside "2026", and refusing it would block an ordinary paste.
     */
    @Test
    fun `a folder whose name merely starts the same is allowed`() {
        assertNull(PasteRules.refusal(held(), PaneSource.Local, "/storage/Movies/2026-old"))
    }

    // --------------------------------------------- between the two places

    @Test
    fun `pasting from the phone into a server is allowed, and is an upload`() {
        assertNull(
            PasteRules.refusal(held(source = PaneSource.Local), PaneSource.Remote(site), "/pub"),
        )
        assertEquals(
            PasteKind.UPLOAD,
            PasteRules.kind(held(source = PaneSource.Local), PaneSource.Remote(site)),
        )
    }

    @Test
    fun `pasting from a server onto the phone is allowed, and is a download`() {
        assertNull(
            PasteRules.refusal(held(source = PaneSource.Remote(site)), PaneSource.Local, "/storage"),
        )
        assertEquals(
            PasteKind.DOWNLOAD,
            PasteRules.kind(held(source = PaneSource.Remote(site)), PaneSource.Local),
        )
    }

    /**
     * FTP has no copy command, so this would mean pulling every byte down and
     * pushing it straight back up -- twice the data for something that looks
     * like a local operation. Refused with a reason rather than attempted.
     */
    @Test
    fun `pasting between two servers is refused with a reason`() {
        val other = site.copy(id = "s2", host = "other")

        assertEquals(
            PasteRefusal.BETWEEN_SERVERS,
            PasteRules.refusal(
                held(source = PaneSource.Remote(site)),
                PaneSource.Remote(other),
                "/pub",
            ),
        )
        assertNull(PasteRules.kind(held(source = PaneSource.Remote(site)), PaneSource.Remote(other)))
    }

    /**
     * A folder cannot swallow itself, but only within one place. The same
     * path on the phone and on a server are different folders entirely, and
     * comparing them would refuse a perfectly ordinary transfer.
     */
    @Test
    fun `the same path on two different sides is not a folder inside itself`() {
        assertNull(
            PasteRules.refusal(
                held(source = PaneSource.Local, directory = "/pub", names = listOf("Vision")),
                PaneSource.Remote(site),
                "/pub/Vision",
            ),
        )
    }

    @Test
    fun `pasting within one server is a file operation`() {
        assertNull(
            PasteRules.refusal(
                held(source = PaneSource.Remote(site), directory = "/pub"),
                PaneSource.Remote(site),
                "/elsewhere",
            ),
        )
    }

    @Test
    fun `canPaste agrees with the refusal`() {
        assertTrue(PasteRules.canPaste(held(), PaneSource.Local, "/storage/target"))
        assertFalse(PasteRules.canPaste(held(), PaneSource.Local, "/storage/Movies/2026"))
    }

    @Test
    fun `the held paths are the directory and the names together`() {
        assertEquals(
            listOf("/storage/Movies/a.txt", "/storage/Movies/b.txt"),
            held(names = listOf("a.txt", "b.txt")).paths(),
        )
    }
}
