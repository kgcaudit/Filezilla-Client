package org.filezilla.android.ui

import org.filezilla.android.archive.ArchiveEntry
import org.filezilla.android.archive.ArchiveSession
import java.io.File

/**
 * The handful of types where the image/comic viewer and the archive reader
 * meet.
 *
 * A picture the viewer shows, and the next volume it runs on to, can each live
 * on the phone or still inside an archive -- so these name both at once and sit
 * here, out on their own, rather than nested in the view model. That is what
 * lets the archive side hand the viewer an image or a next book without either
 * reaching through [MainViewModel] to name the shape it is passing.
 */

/** One image the viewer can show, on the phone or still inside an archive. */
sealed interface ImageRef {
    val name: String

    data class OnDisk(val file: File) : ImageRef {
        override val name: String get() = file.name
    }

    data class InArchive(val session: ArchiveSession, val entry: ArchiveEntry) : ImageRef {
        override val name: String get() = entry.name
    }
}

/**
 * The next volume to run on to when a comic ends, wherever it lives.
 *
 * A series is a run of volumes, and a volume is a file beside this one, a
 * folder beside this one inside an archive, or another archive beside this
 * one inside an outer archive -- a whole collection bundled into a single
 * file. One card, one "continue", three ways of being the next book.
 */
sealed interface NextVolume {
    /** The volume's name, without its extension, for the end card. */
    val label: String

    /** A sibling archive file on the phone. */
    data class LocalFile(val file: File) : NextVolume {
        override val label: String get() = file.nameWithoutExtension
    }

    /** A sibling folder inside the same archive: volumes as sub-folders. */
    data class InArchiveFolder(val session: ArchiveSession, val at: String) : NextVolume {
        override val label: String get() = at.substringAfterLast('/')
    }

    /** A sibling archive inside the outer one: volumes as sub-archives. */
    data class InArchiveEntry(val outer: ArchiveSession, val name: String) : NextVolume {
        override val label: String get() = name.substringBeforeLast('.')
    }
}
