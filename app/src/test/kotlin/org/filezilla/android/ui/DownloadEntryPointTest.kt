package org.filezilla.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * That there is only one way into the download queue.
 *
 * The bug this exists for: downloading a single file by tapping it had a path
 * of its own that queued the transfer directly, while selecting files and
 * downloading a folder went through a path that first looked in the
 * destination folder and asked about anything already there. So the most
 * ordinary download in the app -- one file, tapped -- never asked, and quietly
 * saved a second numbered copy beside the one the user already had.
 *
 * Nothing about that was visible in a screenshot or a compile; it was visible
 * in the call graph. So that is what this reads: a second way in fails here
 * rather than in the user's Downloads folder.
 */
class DownloadEntryPointTest {

    private val viewModelFile =
        File("src/main/kotlin/org/filezilla/android/ui/MainViewModel.kt")

    private fun uiSources(): List<Pair<String, String>> =
        File("src/main/kotlin").walkTopDown()
            .filter { it.extension == "kt" }
            .map { it.name to it.readText() }
            .toList()

    /** Every line that puts a transfer on the queue, as "File.kt:line". */
    private fun queueingLines(): List<String> =
        uiSources().flatMap { (name, source) ->
            source.lines().withIndex()
                .filter { (_, line) -> "transfers.enqueueDownload(" in line }
                .map { (i, _) -> "$name:${i + 1}" }
        }

    @Test
    fun `exactly one place queues a download`() {
        // Not "at most one": zero would mean this test had stopped watching
        // anything, which is the way a guard like this rots.
        assertEquals(1, queueingLines().size)
    }

    @Test
    fun `the place that queues is the one that has checked the destination`() {
        assertEquals("enqueuePlan", memberContaining("transfers.enqueueDownload("))
    }

    /**
     * And it is reached only through the two paths that know about conflicts:
     * one asks before queueing anything, the other applies the answer.
     */
    @Test
    fun `nothing reaches the queue without going through the dialog`() {
        assertEquals(
            setOf("enqueuePicks", "enqueuePlan"),
            membersContaining("findConflicts("),
        )
        assertEquals(
            setOf("resolveConflicts", "enqueuePicks"),
            membersContaining("enqueuePlan("),
        )
    }

    /**
     * Which top-level member of MainViewModel each line belongs to.
     *
     * Split on the file's own indentation rather than by counting braces: a
     * member of a class body starts at four spaces, and a brace counter has to
     * understand lambdas, strings and comments to get the same answer. The
     * first version of this test used one and blamed the wrong function.
     */
    private fun membersByLine(): List<String?> {
        val modifiers = "(?:private|internal|public|suspend|inline|override|abstract|open)"
        val function = Regex("""^ {4}(?:$modifiers\s+)*fun\s+(\w+)""")
        // What else can start a member, and so end the function above it. A
        // bare "starts at four spaces" was tried and was wrong: the closing
        // line of a multi-line signature, `): DownloadPlan {`, starts there
        // too, and ended every function before its body began.
        val otherMember = Regex(
            """^ {4}(?:(?:$modifiers|const|lateinit|data|inner|@\w+)\s+)*""" +
                """(?:val|var|class|object|interface|enum|init|companion)\b""",
        )
        var current: String? = null
        return viewModelFile.readLines().map { line ->
            val opened = function.find(line)
            when {
                opened != null -> {
                    current = opened.groupValues[1]
                    current
                }

                otherMember.containsMatchIn(line) -> {
                    current = null
                    null
                }

                else -> current
            }
        }
    }

    private fun membersContaining(call: String): Set<String> {
        val members = membersByLine()
        val name = call.removeSuffix("(").substringAfterLast('.')
        // A function's own signature is not a call to it, and counting it as
        // one would have findConflicts calling itself.
        val declaration = Regex("""\bfun\s+$name\s*\(""")
        return viewModelFile.readLines().withIndex()
            .filter { (_, line) ->
                call in line &&
                    !declaration.containsMatchIn(line) &&
                    !line.trimStart().startsWith("*")
            }
            .mapNotNull { (i, _) -> members[i] }
            .toSet()
    }

    private fun memberContaining(call: String): String? = membersContaining(call).singleOrNull()
}
