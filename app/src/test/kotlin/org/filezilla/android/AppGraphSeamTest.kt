package org.filezilla.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * That the seam in the composition root stays a seam for tests only.
 *
 * [AppGraph.sealPasswordsWith] exists so a test can build the graph with a
 * cipher that works off a device. It would also let the app itself ship with
 * one, which would mean saved passwords written in something other than the
 * keystore -- a security change hiding inside a testing convenience.
 *
 * So the rule is simply that nothing outside the tests assigns it, and this
 * is what says so. Reading it is fine; the graph has to.
 */
class AppGraphSeamTest {

    private val main = File("src/main/kotlin")

    @Test
    fun `nothing in the app chooses its own password cipher`() {
        // An assignment as a caller would write it. The declaration in
        // AppGraph.kt carries a type between the name and the `=`, so a
        // pattern loose enough to catch both catches neither cleanly -- the
        // first version of this test matched nothing at all and passed.
        val assigning = main.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { Regex("""AppGraph\.sealPasswordsWith\s*=""").containsMatchIn(it.readText()) }
            .map { it.name }
            .sorted()
            .toList()

        assertEquals(emptyList<String>(), assigning)
    }

    /** And the default it is declared with is still the keystore. */
    @Test
    fun `the graph seals passwords with the keystore`() {
        val graph = File(main, "org/filezilla/android/AppGraph.kt").readText()

        assertEquals(
            1,
            Regex("""internal var sealPasswordsWith: \(\) -> PasswordCipher =\s*\{ KeystorePasswordCipher\(\) }""")
                .findAll(graph).count(),
        )
    }

    /** And nothing in the app throws the graph away mid-flight. */
    @Test
    fun `nothing in the app forgets the graph`() {
        val callers = main.walkTopDown()
            .filter { it.extension == "kt" && it.name != "AppGraph.kt" }
            .filter { "AppGraph.forget()" in it.readText() }
            .map { it.name }
            .sorted()
            .toList()

        assertEquals(emptyList<String>(), callers)
    }
}
