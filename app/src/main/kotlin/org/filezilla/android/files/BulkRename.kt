package org.filezilla.android.files

/**
 * Renaming many files by one rule.
 *
 * The rule is the handful of changes people actually make to a batch of names:
 * find-and-replace a piece of the name, put something on the front or the
 * back, and number them in order. They apply in that order to the name's stem
 * -- the part before the last dot -- so a file's extension is never disturbed
 * and never numbered into the middle of. A folder has no extension to protect,
 * so the whole of its name is the stem.
 *
 * Pure and off to one side so the preview and the rename share one answer:
 * what the dialog shows a name will become is exactly what it becomes.
 */
object BulkRename {

    /** One name to rename, and whether it is a folder (so it has no extension). */
    data class Target(val name: String, val isDirectory: Boolean)

    data class Rule(
        val prefix: String = "",
        val suffix: String = "",
        val find: String = "",
        val replace: String = "",
        /** The first number when the batch is numbered; null leaves it unnumbered. */
        val numberFrom: Int? = null,
    )

    /** One file's old name and the name the rule gives it. */
    data class Change(val from: String, val to: String)

    /** Applies [rule] to [targets] in order, one [Change] each, order preserved. */
    fun apply(targets: List<Target>, rule: Rule): List<Change> {
        // Wide enough for the largest number in the run, so 1..10 numbers as
        // 01..10 rather than 1..10 and sorts right in any file list.
        val width = rule.numberFrom?.let { start ->
            (start + targets.size - 1).coerceAtLeast(0).toString().length
        } ?: 0
        return targets.mapIndexed { index, target ->
            val dot = if (target.isDirectory) -1 else target.name.lastIndexOf('.')
            val hasExtension = dot > 0
            var stem = if (hasExtension) target.name.substring(0, dot) else target.name
            val extension = if (hasExtension) target.name.substring(dot) else ""

            if (rule.find.isNotEmpty()) stem = stem.replace(rule.find, rule.replace)
            stem = rule.prefix + stem + rule.suffix
            if (rule.numberFrom != null) {
                stem += (rule.numberFrom + index).toString().padStart(width, '0')
            }
            Change(target.name, stem + extension)
        }
    }
}
