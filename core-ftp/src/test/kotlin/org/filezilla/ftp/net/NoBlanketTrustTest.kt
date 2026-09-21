package org.filezilla.ftp.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That nobody puts "accept everything" back.
 *
 * It is four lines, it makes a stubborn server connect immediately, and it
 * is the single change that would undo all of this without failing any
 * other test here -- every one of them would go on passing, because a
 * connection that trusts anything also trusts the right thing.
 *
 * So the source is read. A trust manager whose check does nothing, or a
 * host name verifier that says yes, fails the build wherever it appears.
 */
class NoBlanketTrustTest {

    private val mainSources: List<File> =
        listOf(File("src/main/kotlin"), File("../app/src/main/kotlin"))
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { file -> file.extension == "kt" } }

    @Test
    fun `the sources this reads are where it thinks they are`() {
        assertTrue(mainSources.size > 20, "found ${mainSources.size} files; this guard is reading nothing")
        assertTrue(
            mainSources.any { it.name == "PinningTrustManager.kt" },
            "the trust manager is not among the files being checked",
        )
    }

    @Test
    fun `no trust manager waves a certificate through`() {
        val offenders = mainSources.filter { file ->
            val text = file.readText()
            // A check with no body, written either way round. The real one
            // has statements in it and does not match.
            Regex("""checkServerTrusted\([^)]*\)\s*(=\s*Unit|\{\s*\})""").containsMatchIn(text)
        }

        assertEquals(
            emptyList<String>(),
            offenders.map { it.name },
            "a trust manager here accepts any certificate, which leaves the " +
                "connection encrypted but addressed to nobody in particular",
        )
    }

    @Test
    fun `no host name verifier says yes to everything`() {
        val offenders = mainSources.filter { file ->
            Regex("""HostnameVerifier\s*(\{|\()""").containsMatchIn(file.readText())
        }

        assertEquals(
            emptyList<String>(),
            offenders.map { it.name },
            "the host name check belongs to the socket's own parameters; a " +
                "verifier here is either duplicating it or overruling it",
        )
    }

    /**
     * The file with its string literals taken out.
     *
     * A migration that rebuilt the sites table still quotes the old
     * column's name in its SQL, and has to: that is what the column was
     * called in the database being migrated away from. Reading the text
     * whole would fail the build over a historical fact.
     */
    private fun codeOf(file: File): String =
        file.readText().replace(Regex(""""(\\.|[^"\\])*""""), "\"\"")

    @Test
    fun `nothing carries a switch for trusting any certificate`() {
        val offenders = mainSources.filter { "trustAllCertificates" in codeOf(it) }

        assertEquals(
            emptyList<String>(),
            offenders.map { it.name },
            "that setting is gone; a certificate is accepted by being " +
                "recognised, one at a time",
        )
    }
}
