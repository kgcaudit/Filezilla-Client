package org.filezilla.android.files

/**
 * What a folder mirror decides to do, worked out from two listings and nothing
 * else.
 *
 * This is the whole of the "what changed" question, kept apart from the network
 * on purpose. Scanning a server is a round trip per folder and running a
 * transfer is another; both are slow, both are hard to test, and neither has
 * anything to say about *which* files differ. That decision is pure -- two maps
 * of relative path to [Meta] go in, an ordered list of [SyncAction] comes out --
 * so it is worked out here where it can be tested exhaustively without a device
 * or a server, and [SyncScan] and the view model only carry it out.
 *
 * One direction: the source is the answer and the target is made to match it.
 * A file missing from the target is copied, a file that differs is copied over,
 * a file that is already the same is left alone. Removing what the target has
 * and the source does not is destructive, so it is off unless asked for
 * ([deleteExtras]) -- see [SyncDiff.diff].
 */
data class Meta(
    val isDir: Boolean,
    /** Bytes, or null when the side that listed it did not say (some servers). */
    val size: Long? = null,
    /** When it last changed, in UTC millis, or null when unknown. */
    val mtimeMillis: Long? = null,
)

/**
 * One thing the mirror will do, named by the path relative to the two roots.
 *
 * [rel] is always relative -- `photos/trip/a.jpg`, never a full path on either
 * side -- because the whole point of the diff is that it does not care where
 * the two roots are, only how they differ below them. The view model splices
 * each [rel] back onto the right root when it carries the action out.
 */
sealed interface SyncAction {
    val rel: String

    /** Make this folder on the target. Emitted shallowest first. */
    data class MakeDir(override val rel: String) : SyncAction

    /**
     * Copy this file from source to target, replacing any file already there.
     *
     * [size] is the source's, carried so the transfer can show a total without
     * listing again; null when the source did not say.
     */
    data class Copy(override val rel: String, val size: Long?) : SyncAction

    /**
     * Remove this from the target: it is not in the source. Only ever emitted
     * when [SyncDiff.diff] is asked to ([deleteExtras]). Deepest first, so a
     * folder's contents go before the folder.
     */
    data class Delete(override val rel: String, val isDir: Boolean) : SyncAction
}

/**
 * A path that is one thing on the source and the other on the target -- a file
 * where the other side has a folder of the same name.
 *
 * Never resolved automatically in v1. Resolving it would mean deleting whatever
 * the target has there before the transfer, and a folder deleted to make room
 * for a file takes everything under it with it -- exactly the destructive
 * surprise this feature is built to avoid. So a conflict is reported, the source
 * side is left uncopied (a conflicting *folder* takes its whole subtree out of
 * the plan with it), and the user is told to sort it out by hand.
 */
data class SyncConflict(
    val rel: String,
    /** True when the source has a folder here and the target a file. */
    val sourceIsDir: Boolean,
)

/**
 * The mirror, worked out and ready to show or carry out.
 *
 * [actions] is already in the order it must run: every [SyncAction.MakeDir]
 * first and shallowest-first, then every [SyncAction.Copy], then every
 * [SyncAction.Delete] deepest-first. Carrying them out in list order therefore
 * makes folders before the files that go in them and empties a folder before
 * removing it, with no second sort needed.
 */
data class SyncPlan(
    val actions: List<SyncAction>,
    val makeDirCount: Int,
    val copyCount: Int,
    val deleteCount: Int,
    /** Files already identical on both sides, left alone. Shown, never acted on. */
    val skipCount: Int,
    val conflicts: List<SyncConflict>,
) {
    /** Nothing to make, copy or delete: the target already mirrors the source. */
    val isNoop: Boolean get() = makeDirCount == 0 && copyCount == 0 && deleteCount == 0
}

/**
 * Turns two listings into the plan that makes the target match the source.
 */
object SyncDiff {

    /**
     * Works out the mirror from [source] and [target].
     *
     * Both maps are keyed by path relative to their own root, in [FilePath]
     * form (no leading slash, `/` between segments, no trailing slash) -- the
     * empty string is not a key, since it would name the root itself. Every
     * folder is its own entry, not just implied by the files under it, so an
     * empty folder in the source is still made on the target.
     *
     * [deleteExtras] is the one destructive switch. Off (the default), the
     * target only ever gains: what it has and the source does not is left be.
     * On, those extras are removed -- and the caller has already made the user
     * say so.
     */
    fun diff(
        source: Map<String, Meta>,
        target: Map<String, Meta>,
        deleteExtras: Boolean = false,
    ): SyncPlan {
        val makeDirs = mutableListOf<String>()
        val copies = mutableListOf<SyncAction.Copy>()
        val deletes = mutableListOf<SyncAction.Delete>()
        val conflicts = mutableListOf<SyncConflict>()
        var skipped = 0

        // A source folder that clashes with a target file takes its whole
        // subtree out of the plan: nothing under it can be made or copied until
        // the file in its way is gone, which is the user's to do. Collected
        // first so the copy/mkdir pass below can pass over anything beneath one.
        val blocked = mutableListOf<String>()
        for ((rel, src) in source) {
            val dst = target[rel] ?: continue
            if (src.isDir && !dst.isDir) blocked += rel
        }
        fun underABlockedFolder(rel: String): Boolean =
            blocked.any { rel != it && rel.startsWith("$it/") }

        for ((rel, src) in source) {
            val dst = target[rel]
            when {
                // In the source, missing from the target.
                dst == null -> {
                    if (underABlockedFolder(rel)) continue
                    if (src.isDir) makeDirs += rel else copies += SyncAction.Copy(rel, src.size)
                }
                // Both folders: the target already has it.
                src.isDir && dst.isDir -> Unit
                // Both files: copy only if they differ.
                !src.isDir && !dst.isDir -> if (differs(src, dst)) {
                    copies += SyncAction.Copy(rel, src.size)
                } else {
                    skipped++
                }
                // One is a folder where the other is a file: a conflict.
                else -> conflicts += SyncConflict(rel, sourceIsDir = src.isDir)
            }
        }

        if (deleteExtras) {
            for ((rel, dst) in target) {
                val src = source[rel]
                // A path the source also has, as the same kind, is not an extra.
                // A conflict (different kind) is left to the user, not deleted.
                if (src != null && src.isDir == dst.isDir) continue
                if (src != null) continue
                deletes += SyncAction.Delete(rel, dst.isDir)
            }
        }

        // Shallowest first, so a parent is made before its child; ties broken
        // by name so the order is stable for tests and for the preview.
        makeDirs.sortWith(compareBy({ depthOf(it) }, { it }))
        copies.sortBy { it.rel }
        // Deepest first, so a folder's contents are removed before the folder,
        // and the target is never asked to remove a folder that still has
        // things in it.
        deletes.sortWith(compareByDescending<SyncAction.Delete> { depthOf(it.rel) }.thenByDescending { it.rel })

        val actions = buildList {
            makeDirs.forEach { add(SyncAction.MakeDir(it)) }
            addAll(copies)
            addAll(deletes)
        }
        return SyncPlan(
            actions = actions,
            makeDirCount = makeDirs.size,
            copyCount = copies.size,
            deleteCount = deletes.size,
            skipCount = skipped,
            conflicts = conflicts.sortedBy { it.rel },
        )
    }

    /**
     * Whether the source file is worth copying over the target's.
     *
     * The source is the answer, so "differs" is asked from its side. Sizes
     * decide it when both are known and disagree. Otherwise a timestamp does,
     * but only when both sides carry one: a source known to be newer wins. When
     * only one side knows a size or a time, there is nothing to compare it
     * against, so the file is left alone rather than copied on a guess -- a
     * mirror that recopied every file each run because one side never reports a
     * time would be useless.
     */
    private fun differs(src: Meta, dst: Meta): Boolean {
        if (src.size != null && dst.size != null && src.size != dst.size) return true
        if (src.mtimeMillis != null && dst.mtimeMillis != null) {
            return src.mtimeMillis > dst.mtimeMillis
        }
        return false
    }

    private fun depthOf(rel: String): Int = rel.count { it == '/' }
}
