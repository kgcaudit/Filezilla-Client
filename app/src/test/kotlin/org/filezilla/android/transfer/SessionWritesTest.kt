package org.filezilla.android.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every way of changing a server has to say that it did.
 *
 * [RemoteListings] holds what a server last said about a folder, and the
 * only thing that makes holding it honest is that our own writes throw it
 * away. That happens because [FtpSession] counts them and
 * [TransferManager.browse] reads the count -- which works exactly as long
 * as nobody adds a fifth operation that writes without going through
 * `writing`.
 *
 * Nothing in the compiler stops that. This does. It reads the class and
 * insists that any method handing work to the file operations is either
 * one of the four known questions or wrapped in the counter.
 *
 * Read on its source rather than by reflection because the thing being
 * checked is how the method is written, which a compiled method no longer
 * knows.
 */
class SessionWritesTest {

    private val source = File("src/main/kotlin/org/filezilla/android/transfer/FtpSession.kt")

    /**
     * The operations that only ask. Everything else on the connection
     * changes something, so a new method not named here has to prove it is
     * counted.
     */
    private val questions = setOf("currentDirectory", "changeDirectory", "changeToParentDirectory")

    /** `fun name(...) = ...` and `fun name(...): T = ...` alike. */
    private val declaration = Regex("""^\s*fun\s+(\w+)\s*\(""")

    private fun methodsOf(text: String): Map<String, String> {
        val found = mutableMapOf<String, String>()
        val lines = text.lines()
        for ((index, line) in lines.withIndex()) {
            val name = declaration.find(line)?.groupValues?.get(1) ?: continue
            // The body, as far as the next blank line: every method in this
            // class is an expression body or a short block.
            val body = lines.drop(index)
                .takeWhile { it.isNotBlank() }
                .joinToString("\n")
            found[name] = body
        }
        return found
    }

    @Test
    fun `the file this reads is where it thinks it is`() {
        assertTrue(
            "FtpSession moved; this guard is now checking nothing",
            source.isFile,
        )
    }

    @Test
    fun `nothing changes the server without counting it`() {
        val uncounted = methodsOf(source.readText())
            .filterValues { it.contains("operations.") }
            .filterNot { (_, body) -> questions.any { body.contains("operations.$it(") } }
            .filterNot { (_, body) -> body.contains("writing {") }
            .keys

        assertEquals(
            "these change the server without telling the listing cache; wrap " +
                "them in writing { } or the app will go on showing folders as " +
                "they were before the change",
            emptySet<String>(),
            uncounted,
        )
    }
}
