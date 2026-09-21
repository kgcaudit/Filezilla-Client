package org.filezilla.android.ui

import org.filezilla.ftp.listing.DirectoryEntry

/** One thing a delete will ask the server to remove. */
data class PlannedRemoval(val path: String, val isDirectory: Boolean)

/** What a selection expands to, and why it might not be runnable. */
data class RemovalPlan(
    /** In the order to send: nothing is removed before its contents are. */
    val steps: List<PlannedRemoval> = emptyList(),
    /** Symbolic links unlinked rather than followed; see [RemoteDelete.plan]. */
    val links: Int = 0,
    /** True when the walk did not reach the bottom, so the plan is incomplete. */
    val truncated: Boolean = false,
    /** True when the walk was called off, so the plan is not the whole tree. */
    val cancelled: Boolean = false,
    /** Folders opened. What the walk cost, and what it has to show for itself. */
    val foldersRead: Int = 0,
)

/**
 * Turns "delete this folder" into the commands FTP actually has.
 *
 * FTP has no recursive delete. `RMD` removes an empty directory and refuses
 * anything else, so deleting a folder with a file in it came back as
 * `550 Directory not empty` and the folder stayed where it was -- while
 * deleting a file worked, which is what made it look like the app was broken
 * rather than the protocol being narrow.
 *
 * The phone's side has always done this: [org.filezilla.android.files.LocalOperations.delete]
 * walks a folder and removes what is in it. This is the same walk with `DELE`
 * and `RMD` in place of `File.delete`.
 */
object RemoteDelete {

    /**
     * Expands [picks] -- rows of [directory] -- into removals, deepest first.
     *
     * A symbolic link is unlinked with `DELE` and never walked into. Both
     * halves of that matter: following one would leave the folder the user is
     * looking at -- a link to `/` would take the server with it -- and
     * skipping it would leave the folder non-empty, so the `RMD` above it
     * would fail and the whole delete would stop for a reason the user could
     * not see. A listing marks a link as a directory whether or not it is
     * one, so this is decided on the link flag before the directory flag.
     *
     * The walk is bounded the same way [FolderDownload] is bounded, and for
     * the same reason: a remote tree is not ours to trust. A plan that hit a
     * bound is marked [RemovalPlan.truncated] and must not be run -- half a
     * delete leaves a tree the user did not ask for and cannot see the shape
     * of.
     */
    fun plan(
        lister: RemoteLister,
        directory: String,
        picks: List<DirectoryEntry>,
        /**
         * Asked between folders, so a walk somebody has called off stops
         * within one listing rather than reading the whole tree first.
         */
        cancelled: () -> Boolean = { false },
        /** Told after each folder, so the count can be shown as it climbs. */
        onFolder: (Int) -> Unit = {},
    ): RemovalPlan {
        val steps = mutableListOf<PlannedRemoval>()
        var links = 0
        var truncated = false
        var stopped = false
        var foldersRead = 0

        fun walk(path: String, depth: Int) {
            if (depth >= FolderDownload.MAX_DEPTH) {
                truncated = true
                return
            }
            if (stopped || cancelled()) {
                stopped = true
                return
            }
            val listing = lister.list(path)
            foldersRead++
            onFolder(foldersRead)
            for (entry in listing) {
                if (stopped) return
                if (steps.size >= FolderDownload.MAX_FILES) {
                    truncated = true
                    return
                }
                val child = remotePathOf(path, entry.name)
                when {
                    entry.isLink -> {
                        links++
                        steps += PlannedRemoval(child, isDirectory = false)
                    }

                    entry.isDirectory -> {
                        walk(child, depth + 1)
                        // After its contents, never before: this is the whole
                        // point of the walk.
                        steps += PlannedRemoval(child, isDirectory = true)
                    }

                    else -> steps += PlannedRemoval(child, isDirectory = false)
                }
            }
        }

        for (pick in picks) {
            if (steps.size >= FolderDownload.MAX_FILES) {
                truncated = true
                break
            }
            if (stopped || cancelled()) {
                stopped = true
                break
            }
            val path = remotePathOf(directory, pick.name)
            when {
                pick.isLink -> {
                    links++
                    steps += PlannedRemoval(path, isDirectory = false)
                }

                pick.isDirectory -> {
                    walk(path, 1)
                    steps += PlannedRemoval(path, isDirectory = true)
                }

                else -> steps += PlannedRemoval(path, isDirectory = false)
            }
        }

        return RemovalPlan(steps, links, truncated, stopped, foldersRead)
    }

    /**
     * Sends a plan, reporting as it goes and stopping when asked.
     *
     * Here rather than in the view model because it is the half of the
     * delete that can be checked: a walk that produces the right plan and
     * a loop that reports the wrong thing is still an app that looks
     * broken, and the loop is where the counting is.
     *
     * [cancelled] is asked *between* steps and never inside one. A stop
     * that interrupted a command would leave the control connection with a
     * reply nobody read, and the next thing to borrow it would read this
     * one's answer as its own.
     *
     * Returns how many steps were sent, which is the whole plan unless
     * somebody stopped it.
     */
    fun remove(
        steps: List<PlannedRemoval>,
        remover: Remover,
        cancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int, next: PlannedRemoval) -> Unit = { _, _, _ -> },
    ): Int {
        var done = 0
        for (step in steps) {
            if (cancelled()) break
            onProgress(done, steps.size, step)
            remover.remove(step)
            done++
        }
        return done
    }

    /** What actually sends `DELE` and `RMD`. The seam the tests replace. */
    fun interface Remover {
        fun remove(step: PlannedRemoval)
    }

    /** Whether removing [picks] has to ask the server what is inside anything. */
    fun needsRemoteWalk(picks: List<DirectoryEntry>): Boolean =
        picks.any { it.isDirectory && !it.isLink }
}

/**
 * Thrown when a folder holds more than one delete can take in one go.
 *
 * An [java.io.IOException] because that is what everything else that fails
 * mid-operation looks like to the code above, but it is not a network
 * failure: nothing was sent, and nothing was removed.
 */
class TooMuchToDeleteException :
    java.io.IOException("the folder holds more than one delete can take at once")
