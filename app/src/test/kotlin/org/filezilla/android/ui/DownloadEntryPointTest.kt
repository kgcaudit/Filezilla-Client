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
        // enqueuePicks asks before queueing anything, downloadHeld asks
        // before turning a paste into transfers, and enqueuePlan asks again
        // to know which files a "skip" drops. Anything else calling these
        // would be a fourth opinion about what the destination holds.
        assertEquals(
            setOf("enqueuePicks", "downloadHeld", "enqueuePlan"),
            membersContaining("findConflicts("),
        )
        assertEquals(
            setOf("resolveConflicts", "enqueuePicks", "downloadHeld"),
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

    // --------------------------------------------------------- uploads

    /**
     * The same guard, the other way. Uploads queued straight from the paste
     * asked the server nothing, and an upload that resumes onto a file
     * already there appends to it -- so sending over an existing file spliced
     * two of them together with nothing said.
     */
    @Test
    fun `only the path that asked the server queues an upload`() {
        assertEquals(setOf("queueUploads"), membersContaining("transfers.enqueueUpload("))
    }

    // ------------------------------------------- a paste inside the phone

    /**
     * And again for the third way files get written, which had the same hole.
     *
     * Copying inside the phone did not ask either. The file operations refuse
     * to write over anything, so the paste failed and the pane re-listed
     * unchanged -- which looks precisely like a paste that never ran.
     */
    @Test
    fun `only one place copies or moves what is held`() {
        assertEquals(setOf("runPaste"), membersContaining("LocalOperations.copy("))
        assertEquals(setOf("runPaste"), membersContaining("LocalOperations.move("))
    }

    @Test
    fun `and it is reached only after the question has been put`() {
        // paste gathers the collisions and either runs straight away or holds
        // the paste for the dialog; resolvePasteConflicts is the dialog's
        // answer arriving. A third caller would be a paste that never asked.
        assertEquals(setOf("paste", "resolvePasteConflicts"), membersContaining("runPaste("))
    }

    @Test
    fun `exactly one place looks for what a paste would land on`() {
        assertEquals(setOf("paste"), membersContaining("localPasteConflicts("))
    }

    // ------------------------------------------------ removing on a server

    /**
     * And a fourth time, for the same shape of hole.
     *
     * Deleting had two ways in -- one row's menu, and a selection -- and both
     * sent a bare `RMD`. FTP's `RMD` refuses a directory that is not empty,
     * so deleting a folder with anything in it came back as
     * "550 Directory not empty" while deleting a file worked. Two call sites,
     * one missing walk, and nothing in a compile to say so.
     */
    @Test
    fun `only one place removes anything from a server`() {
        assertEquals(setOf("removeRemotely"), membersContaining("session.removeDirectory("))
        assertEquals(setOf("removeRemotely"), membersContaining("session.deleteFile("))
    }

    @Test
    fun `and it is the place that walked the folder first`() {
        assertEquals(setOf("removeRemotely"), membersContaining("RemoteDelete.plan("))
    }

    @Test
    fun `queueing uploads is fed by the paths that looked at the server`() {
        assertEquals(
            setOf("sendToServer", "resolveUploadConflicts"),
            membersContaining("queueUploads("),
        )
        // And the only way into that: a paste, or the app-bar picker.
        assertEquals(
            setOf("uploadHeld", "enqueueUpload"),
            membersContaining("sendToServer("),
        )
    }
}
