package org.filezilla.android.files

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowMimeTypeMap

/**
 * Finding the apps that will actually open a file.
 *
 * The user's report: "파일 열기가 안되는데?" The app asked the phone once,
 * with the exact type the platform gives an extension, and took no answer
 * for an answer. Android calls a `.srt` an `application/x-subrip` and
 * almost nothing declares that string; the editors that would happily open
 * it declare the whole text family, and several more declare a wildcard.
 * So asking precisely is how a phone with four suitable apps reports none.
 *
 * Tested against a stub of what the phone offers rather than through the
 * package manager, because Robolectric's shadow hands back whatever was
 * registered for any query it is asked -- so the very thing that matters
 * here, which type an app was found under and therefore where it sits in
 * the list, is the one thing it cannot show.
 */
@RunWith(RobolectricTestRunner::class)
class OpenCandidatesTest {

    private val types: ShadowMimeTypeMap
        get() = Shadows.shadowOf(android.webkit.MimeTypeMap.getSingleton())

    @Before
    fun setUp() {
        types.addExtensionMimeTypeMapping("srt", "application/x-subrip")
        types.addExtensionMimeTypeMapping("mkv", "video/x-matroska")
    }

    /** A phone where each named app declares exactly the types listed. */
    private fun phoneWith(vararg apps: Pair<String, List<String>>) =
        OpenFile.AppsOffering { type ->
            apps.filter { (_, declared) -> type in declared }
                .map { (name, _) ->
                    OpenFile.Candidate(
                        component = ComponentName(name, "$name.Main"),
                        label = name,
                        type = type,
                    )
                }
        }

    private fun opening(name: String, offering: OpenFile.AppsOffering) =
        OpenFile.candidatesFor(name, offering).map { it.component.packageName }

    /** The order the questions are asked in, which is the fix in one line. */
    @Test
    fun `a file is asked about from its own type outwards`() {
        assertEquals(
            listOf("application/x-subrip", "application/*", "*/*"),
            OpenFile.typesToAsk("movie.ko.srt"),
        )
    }

    /** A name the platform does not know is a wildcard, asked once. */
    @Test
    fun `an unknown extension asks only the one question worth asking`() {
        assertEquals(listOf("*/*"), OpenFile.typesToAsk("thing.qqqzzz"))
    }

    /**
     * The user's own case: nothing declares the exact type, and the app
     * that declares the family is exactly the one that should open it.
     */
    @Test
    fun `an app that declares a family is found for a file of that family`() {
        val phone = phoneWith("com.example.editor" to listOf("application/*"))

        assertEquals(listOf("com.example.editor"), opening("movie.ko.srt", phone))
    }

    @Test
    fun `an app that declares the exact type is found`() {
        val phone = phoneWith("com.example.player" to listOf("video/x-matroska"))

        assertEquals(listOf("com.example.player"), opening("film.mkv", phone))
    }

    /** The catch-all is the last thing tried and still counts. */
    @Test
    fun `an app that declares anything is found for a type nothing else wants`() {
        val phone = phoneWith("com.example.anything" to listOf("*/*"))

        assertTrue("com.example.anything" in opening("thing.qqqzzz", phone))
    }

    /**
     * The widening stops as soon as it has an answer.
     *
     * The user's second report, and the reason this matters: an `.apk`,
     * which exactly one app installs, came back offering the installer, a
     * certificate wizard, Chrome, an HTML viewer, Photos, Gmail twice,
     * Google and PASS -- because the last question asked was "anything at
     * all?" and nine apps said yes. Widening is what you do *because*
     * nothing was found, not a net to drag everything up in.
     */
    @Test
    fun `an exact answer is not padded out with everything else`() {
        val phone = phoneWith(
            "com.example.anything" to listOf("*/*"),
            "com.example.installer" to listOf("video/x-matroska"),
        )

        assertEquals(listOf("com.example.installer"), opening("film.mkv", phone))
    }

    /** And it widens when, and only when, there is nothing to report. */
    @Test
    fun `it widens when the narrower question finds nobody`() {
        val phone = phoneWith("com.example.anything" to listOf("*/*"))

        assertEquals(listOf("com.example.anything"), opening("film.mkv", phone))
    }

    /**
     * One app, one row. Gmail appeared twice because it declares two
     * activities, and to the reader that is one app offered twice with
     * nothing to tell the rows apart.
     */
    @Test
    fun `an app with two activities is offered once`() {
        val phone = OpenFile.AppsOffering { type ->
            if (type != "video/x-matroska") emptyList() else listOf(
                OpenFile.Candidate(ComponentName("com.example.mail", "a.One"), "Mail", type),
                OpenFile.Candidate(ComponentName("com.example.mail", "a.Two"), "Mail", type),
            )
        }

        assertEquals(listOf("com.example.mail"), opening("film.mkv", phone))
    }

    /** It is launched with the type its answer came from. */
    @Test
    fun `a candidate carries the type it was found under`() {
        val phone = phoneWith("com.example.player" to listOf("video/x-matroska"))

        assertEquals(
            "video/x-matroska",
            OpenFile.candidatesFor("film.mkv", phone).single().type,
        )
    }

    @Test
    fun `an app found only by the family is launched with the family`() {
        val phone = phoneWith("com.example.editor" to listOf("application/*"))

        assertEquals(
            "application/*",
            OpenFile.candidatesFor("movie.ko.srt", phone).single().type,
        )
    }

    @Test
    fun `a phone with nothing suitable is an empty list rather than a crash`() {
        assertEquals(emptyList<String>(), opening("film.mkv", phoneWith()))
    }
}
