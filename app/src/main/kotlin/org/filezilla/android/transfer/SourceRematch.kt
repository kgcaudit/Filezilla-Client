package org.filezilla.android.transfer

/** A local file considered when a queued upload's source is no longer where it was. */
data class SourceCandidate(val path: String, val name: String, val size: Long)

/**
 * Finding a queued upload's source again after it was moved or renamed.
 *
 * An upload records where its file was when it joined the queue. If the file is
 * moved, renamed into a new folder, or the folder is reorganised before the
 * upload runs, that path no longer opens. The search that turns up candidates
 * -- a MediaStore query, a walk of the volume -- lives with the Android that
 * can do it; this is only the decision at the end of it: which candidate, if
 * any, is the very file that was queued.
 *
 * Pure on purpose, because the cost of getting it wrong is high: sending a
 * different file of the same name in another folder would upload the wrong
 * bytes under the right name, with nothing said. So the rule is strict -- same
 * name, same size, and exactly one -- and anything short of that is treated as
 * "not found" rather than guessed.
 */
object SourceRematch {

    /**
     * The one candidate that is the same file as what was queued, or null when
     * there is no single confident answer.
     *
     * Same name is necessary but not enough: two unrelated files can share a
     * name across folders. When the queued size is known it must match too, and
     * either way the answer must be unambiguous -- more than one file that fits
     * is a tie this refuses to break, because sending the wrong one is worse
     * than failing and asking.
     */
    fun bestMatch(name: String, size: Long?, candidates: List<SourceCandidate>): SourceCandidate? {
        val byName = candidates.filter { it.name == name }
        val fit = if (size != null && size >= 0) byName.filter { it.size == size } else byName
        return fit.singleOrNull()
    }
}
