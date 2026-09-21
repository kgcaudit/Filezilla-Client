package org.filezilla.android.endtoend

import org.filezilla.android.ui.PaneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A server that speaks a legacy encoding, which is most of them in Korea.
 *
 * The site editor has offered a per-site filename encoding since early on,
 * and its own hint says so: "국내 NAS는 EUC-KR로 저장하는 경우가 많습니다".
 * Nothing tested it. The setting was stored, carried through
 * `SiteEntity.toSettings`, and handed to the control connection, and
 * whether any of that produced a readable Korean filename at the other end
 * was a matter of faith -- on the one configuration the app's own user
 * actually connects to.
 *
 * So the test server can now speak one. pyftpdlib encodes every path on
 * the control channel with its `encoding`, and leaves `UTF8` out of `FEAT`
 * when that is not UTF-8, which is exactly how such a NAS behaves.
 *
 * The pair of tests is the point. That the right setting works is half an
 * answer; the other half is that the wrong one visibly fails, because a
 * setting that changes nothing would pass the first test on its own.
 */
@RunWith(RobolectricTestRunner::class)
class KoreanNamesOnServerTest : AppAgainstAServer() {

    override val serverEncoding: String get() = "euc-kr"

    private val korean = "나토리 - 아이리스.mp3"

    @Test
    fun `korean names come back readable when the encoding is set`() {
        File(onServer, korean).writeText("one")

        val site = savedSite(encoding = "EUC-KR")
        val model = model()
        openOnServer(model, PaneId.LEFT, site)
        waitFor("the listing") { model.pane(PaneId.LEFT).entries.isNotEmpty() }

        assertEquals(listOf(korean), model.pane(PaneId.LEFT).entries.map { it.name })
    }

    /**
     * The other half. Without the setting the app negotiates UTF-8 and
     * reads EUC-KR bytes as if they were UTF-8, which is what mojibake is.
     * If this passed, the setting would be doing nothing and the test above
     * would be passing for the wrong reason.
     */
    @Test
    fun `without it the same names come back wrong`() {
        File(onServer, korean).writeText("one")

        val site = savedSite(encoding = null)
        val model = model()
        openOnServer(model, PaneId.LEFT, site)
        waitFor("the listing") { !model.pane(PaneId.LEFT).loading }

        val shown = model.pane(PaneId.LEFT).entries.map { it.name }
        assertTrue(
            "the encoding setting makes no difference: got $shown",
            shown != listOf(korean),
        )
    }

    /** And a name sent the other way has to arrive as what it was called. */
    @Test
    fun `a korean name survives being uploaded`() {
        val from = phone.newFolder("from")
        File(from, korean).writeText("one")

        val site = savedSite(encoding = "EUC-KR")
        val model = model()
        openOnPhone(model, PaneId.LEFT, from)
        waitFor("the row") { model.pane(PaneId.LEFT).entries.any { it.name == korean } }

        model.toggleSelected(korean)
        model.copySelection(PaneId.LEFT)
        openOnServer(model, PaneId.RIGHT, site)
        model.pasteAcross(PaneId.RIGHT) { }
        waitFor("the queue") { model.transfers.value.isNotEmpty() }
        runTheQueue()

        assertEquals("one", File(onServer, korean).readText())
    }

    /** Walking into a folder whose own name is Korean. */
    @Test
    fun `a korean folder can be opened`() {
        File(onServer, "영화/한국").mkdirs()
        File(onServer, "영화/한국/자막.srt").writeText("two")

        val site = savedSite(encoding = "EUC-KR")
        val model = model()
        openOnServer(model, PaneId.LEFT, site)
        waitFor("the listing") { model.pane(PaneId.LEFT).entries.any { it.name == "영화" } }

        model.openChild(PaneId.LEFT, "영화")
        waitFor("the folder") { model.pane(PaneId.LEFT).path == "/영화" }
        model.openChild(PaneId.LEFT, "한국")
        waitFor("the subfolder") { model.pane(PaneId.LEFT).path == "/영화/한국" }

        assertEquals(
            listOf("자막.srt"),
            model.pane(PaneId.LEFT).entries.map { it.name },
        )
    }
}
