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
    fun plan(lister: RemoteLister, directory: String, picks: List<DirectoryEntry>): RemovalPlan {
        val steps = mutableListOf<PlannedRemoval>()
        var links = 0
        var truncated = false

        fun walk(path: String, depth: Int) {
            if (depth >= FolderDownload.MAX_DEPTH) {
                truncated = true
                return
            }
            for (entry in lister.list(path)) {
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

        return RemovalPlan(steps, links, truncated)
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
