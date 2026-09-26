package org.filezilla.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        // The copy and the move behind a paste live in one pure function,
        // pasteLocally, and only in the file that holds it; the trash's own
        // moves -- a delete that stashes and a restore that puts back -- live
        // the same way in Trash.kt. Two named places, so a third one writing
        // files behind a delete or a paste would show up here.
        val copyMoveFiles = uiSources()
            .filter { (_, source) -> "LocalOperations.copy(" in source || "LocalOperations.move(" in source }
            .map { it.first }
            .toSet()
        assertEquals(setOf("PasteConflicts.kt", "Trash.kt"), copyMoveFiles)
        // And the view model reaches that one function through runPaste alone.
        assertEquals(setOf("runPaste"), membersContaining("pasteLocally("))
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
    /**
     * Two places, and the second one is why this guard is worth having: a
     * move within one server that lands on a name already taken removes what
     * it is replacing, and it has to do that through the same walk. RNTO onto
     * an existing name is refused by some servers and silently overwrites on
     * others, so neither is left to chance.
     */
    // ------------------------- the phone's own work, on the phone's own pane

    /**
     * That nothing does file work on a pane without asking where it points.
     *
     * The fourth time this shape of bug appeared. The screen called the
     * phone's own delete, rename and new-folder on whichever pane was in
     * front, including a server one -- so selecting files on a server and
     * pressing delete ran java.io.File work against the server's path. The
     * phone has no such path, so nothing was deleted; then the pane was
     * re-listed by the phone's file reader, which reported the server's
     * folder missing, in the phone's words, above the server's rows.
     *
     * The three that dispatch are the only ones the screen may call, so this
     * reads the screen rather than the view model.
     */
    @Test
    fun `the screen never calls the phone's own operations directly`() {
        val phoneOnly = listOf(
            "model.deleteSelection(",
            "model.renameLocal(",
            "model.createFolder(",
            "model.createFile(",
        )
        val screens = uiSources().filter { (name, _) -> name != "MainViewModel.kt" }
        for (call in phoneOnly) {
            val callers = screens.filter { (_, source) -> call in source }.map { it.first }
            assertEquals("$call is called from the screen", emptyList<String>(), callers)
        }
    }

    /** And each of the three that may be called does dispatch. */
    @Test
    fun `each pane operation asks where the pane points`() {
        val source = viewModelFile.readText()
        for (member in listOf("deleteSelectionIn", "renameIn", "createFolderIn", "createFileIn")) {
            val body = source.substringAfter("fun $member(").substringBefore("\n    fun ")
            assertTrue("$member does not look at the pane's source", "isLocal" in body)
        }
    }

    @Test
    fun `only the two places that walked the folder remove anything from a server`() {
        // A selection delete walks the folder in removeRemotely; a same-server
        // move that lands on a taken name clears it through deleteRemoteTree,
        // which runRemoteMove calls -- both go through the RemoteDelete walk, so
        // a bare RMD on a non-empty folder cannot creep back in.
        val removers = setOf("removeRemotely", "deleteRemoteTree")
        assertEquals(removers, membersContaining("session.removeDirectory("))
        assertEquals(removers, membersContaining("session.deleteFile("))
        assertEquals(removers, membersContaining("RemoteDelete.plan("))
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

/**
 * That a copy to a server is made of folders as well as files.
 *
 * The bug the user found: the files all arrived and the empty folder beside
 * them did not. An upload was built by walking the selection for files, so a
 * folder holding none produced nothing to queue -- and nothing queued means
 * nothing made, no row in the transfer list, and no error. The folder simply
 * was not there afterwards.
 *
 * Read from the source rather than driven end to end, and that is a
 * limitation worth naming: nothing here can connect the view model to a
 * server, because passwords are sealed with the Android keystore and there
 * is no keystore off a device. So the walk is tested on its own
 * ([org.filezilla.android.files.LocalWalkFoldersTest]), the folders are made
 * on a live server by the engine's own tests, and what this covers is the
 * join between them -- which is exactly where the bug was.
 */
class UploadCarriesFoldersTest {

    private val viewModel =
        File("src/main/kotlin/org/filezilla/android/ui/MainViewModel.kt").readText()

    @Test
    fun `the paste walks folders as well as files`() {
        assertTrue(
            "an upload is built from files alone again",
            "LocalWalk.foldersUnder(" in viewModel,
        )
    }

    /** And makes them before it queues anything, so an empty one still lands. */
    @Test
    fun `the folders are made before the files are queued`() {
        val queueing = viewModel.substring(viewModel.indexOf("private suspend fun queueUploads("))
        val makes = queueing.indexOf("makeRemoteFolders(")
        val queues = queueing.indexOf("enqueueUpload(")

        assertTrue("nothing makes the folders", makes >= 0)
        assertTrue("nothing queues the files", queues >= 0)
        assertTrue("the folders are made after the files are queued", makes < queues)
    }
}
