package org.filezilla.android.files

/**
 * A two-way sync, worked out from two listings -- the correct version of "run
 * the one-way diff both ways", which is subtly wrong.
 *
 * The one-way [SyncDiff] decides "differs" by size first, which is symmetric: a
 * file that is a different size on the two sides looks different from either
 * side, so running the one-way diff both ways would copy it *both* ways and let
 * the older overwrite the newer. Two-way has to pick a winner instead.
 *
 * So here the rule for a file on both sides is the newer one wins -- strictly
 * newer, by modification time -- and it copies to the other side. A file on
 * only one side copies to the other. A file that differs but whose age cannot
 * be told apart (no timestamps, or the same timestamp with different contents)
 * is a conflict: copying either way might lose an edit, so neither is touched
 * and the user is told. Nothing is ever deleted: without a record of what was
 * there last time, a deletion on one side cannot be told from a new file on the
 * other.
 *
 * The result is two [SyncPlan]s with disjoint copies -- [forward] for
 * source->target, [backward] for target->source -- that the view model runs one
 * after the other.
 */
object TwoWayDiff {

    data class Result(val forward: SyncPlan, val backward: SyncPlan)

    fun diff(source: Map<String, Meta>, target: Map<String, Meta>): Result {
        val toTargetDirs = mutableListOf<String>()
        val toSourceDirs = mutableListOf<String>()
        val toTarget = mutableListOf<SyncAction.Copy>()
        val toSource = mutableListOf<SyncAction.Copy>()
        val conflicts = mutableListOf<SyncConflict>()
        var skipped = 0

        val keys = source.keys + target.keys
        for (rel in keys) {
            val s = source[rel]
            val t = target[rel]
            when {
                // Only on the source: it goes to the target.
                t == null -> {
                    s!!
                    if (s.isDir) toTargetDirs += rel else toTarget += SyncAction.Copy(rel, s.size)
                }
                // Only on the target: it comes to the source.
                s == null -> {
                    if (t.isDir) toSourceDirs += rel else toSource += SyncAction.Copy(rel, t.size)
                }
                // Both folders: nothing to do.
                s.isDir && t.isDir -> Unit
                // One a folder where the other is a file: a conflict either way.
                s.isDir != t.isDir -> conflicts += SyncConflict(rel, sourceIsDir = s.isDir)
                // Both files: the newer wins, or it is a conflict if the age
                // cannot be told and the contents differ.
                else -> when (winner(s, t)) {
                    Side.SOURCE -> toTarget += SyncAction.Copy(rel, s.size)
                    Side.TARGET -> toSource += SyncAction.Copy(rel, t.size)
                    Side.SAME -> skipped++
                    Side.AMBIGUOUS -> conflicts += SyncConflict(rel, sourceIsDir = false)
                }
            }
        }

        return Result(
            forward = assemble(toTargetDirs, toTarget, skipped, conflicts),
            // The conflicts and the skip count belong to the pair, not to each
            // half, so they are carried on the forward plan alone.
            backward = assemble(toSourceDirs, toSource, skipCount = 0, conflicts = emptyList()),
        )
    }

    private enum class Side { SOURCE, TARGET, SAME, AMBIGUOUS }

    private fun winner(s: Meta, t: Meta): Side {
        val sameSize = s.size != null && t.size != null && s.size == t.size
        val sameTime = s.mtimeMillis != null && t.mtimeMillis != null && s.mtimeMillis == t.mtimeMillis
        // Same size and same time (both known) -- or nothing to tell them apart
        // by -- counts as the same file, left alone rather than copied on a guess.
        if (sameSize && sameTime) return Side.SAME
        val knownBothTimes = s.mtimeMillis != null && t.mtimeMillis != null
        val knownBothSizes = s.size != null && t.size != null
        if (!knownBothSizes && !knownBothTimes) return Side.SAME
        // They differ in some known way. The newer wins; a tie in time with a
        // size difference is a real conflict -- same age, different contents.
        return when {
            !knownBothTimes -> Side.AMBIGUOUS
            s.mtimeMillis!! > t.mtimeMillis!! -> Side.SOURCE
            s.mtimeMillis!! < t.mtimeMillis!! -> Side.TARGET
            else -> Side.AMBIGUOUS
        }
    }

    private fun assemble(
        dirs: List<String>,
        copies: List<SyncAction.Copy>,
        skipCount: Int,
        conflicts: List<SyncConflict>,
    ): SyncPlan {
        val sortedDirs = dirs.sortedWith(compareBy({ it.count { c -> c == '/' } }, { it }))
        val sortedCopies = copies.sortedBy { it.rel }
        val actions = buildList {
            sortedDirs.forEach { add(SyncAction.MakeDir(it)) }
            addAll(sortedCopies)
        }
        return SyncPlan(
            actions = actions,
            makeDirCount = sortedDirs.size,
            copyCount = sortedCopies.size,
            deleteCount = 0,
            skipCount = skipCount,
            conflicts = conflicts.sortedBy { it.rel },
        )
    }
}
