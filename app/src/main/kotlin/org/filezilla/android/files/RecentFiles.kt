package org.filezilla.android.files

import org.filezilla.android.data.RecentEntry
import java.io.File

/** Whether the file now at a recents entry's path is the one that was recorded. */
enum class RecentAvailability {
    /** The recorded file is right there. */
    PRESENT,

    /** Nothing is at the path any more. */
    MISSING,

    /** Something is at the path, but a different file than the one recorded. */
    CHANGED,
}

/**
 * Classifies the file now at [entry]'s path against the fingerprint recorded
 * when it was opened.
 *
 * Pure, so the rule can be stated once and checked at every edge. A legacy
 * entry with no fingerprint keeps the old path-only behaviour: if something is
 * there, it counts as present, because there is nothing to tell it apart by.
 */
fun recentAvailability(entry: RecentEntry, exists: Boolean, size: Long, modified: Long): RecentAvailability = when {
    !exists -> RecentAvailability.MISSING
    !entry.hasFingerprint -> RecentAvailability.PRESENT
    size == entry.size && modified == entry.modified -> RecentAvailability.PRESENT
    else -> RecentAvailability.CHANGED
}

/** The same, read from the filesystem. */
fun recentAvailability(entry: RecentEntry, file: File): RecentAvailability =
    if (!file.exists()) {
        RecentAvailability.MISSING
    } else {
        recentAvailability(entry, exists = true, size = file.length(), modified = file.lastModified())
    }

/**
 * Finds a file that has moved, by its fingerprint.
 *
 * A file keeps its name, size and modified time when it is moved to another
 * folder, so those three together identify it wherever it has gone -- and they
 * tell it apart from a different file that happens to share its name. This is
 * what lets a recents entry follow its file rather than going dead the moment
 * it is moved, and what stops it opening an impostor pasted in at the old path.
 */
object RecentLocator {

    /**
     * The first file under [roots] whose name, size and modified time all match
     * the fingerprint, or null when there is none.
     *
     * Name is compared before anything is stat'd, so a file whose name differs
     * costs only a string compare; only a name match is measured for size and
     * time. That is what keeps a walk of a whole volume affordable.
     */
    fun find(
        roots: List<File>,
        name: String,
        size: Long,
        modified: Long,
        cancelled: () -> Boolean = { false },
    ): File? {
        if (size < 0) return null
        for (root in roots) {
            if (!root.isDirectory) continue
            for (file in root.walkTopDown()) {
                if (cancelled()) return null
                if (file.name == name && file.isFile &&
                    file.length() == size && file.lastModified() == modified
                ) {
                    return file
                }
            }
        }
        return null
    }
}
