package org.filezilla.android.ui

import org.filezilla.android.files.FilePath

/** What a paste will do with what was picked up. */
enum class ClipboardMode { COPY, MOVE }

/**
 * What was cut or copied, and where from.
 *
 * The source is kept as well as the names because a paste means different
 * things depending on where the two panes are pointed: within one place it is
 * a file operation, and between two it is a transfer. Without the origin the
 * paste could not tell which it was.
 */
data class Clipboard(
    val mode: ClipboardMode,
    val source: PaneSource,
    val directory: String,
    val names: List<String>,
) {
    val isEmpty: Boolean get() = names.isEmpty()

    /** The full path of each item picked up. */
    fun paths(): List<String> = names.map { FilePath.child(directory, it) }
}

/** Why a paste cannot happen. Null means it can. */
enum class PasteRefusal {
    /** Nothing was picked up. */
    NOTHING_HELD,

    /** A move into the folder the items are already in would do nothing. */
    ALREADY_THERE,

    /** A folder cannot be put inside itself or anything under it. */
    INTO_ITSELF,

    /**
     * The two sides are different places.
     *
     * A transfer rather than a file operation, and not yet built. Named
     * rather than hidden, so the button can say why instead of doing nothing.
     */
    NEEDS_TRANSFER,
}

/**
 * Whether what is held can be put down here.
 *
 * Pure, and apart from the operations it guards, because these are the
 * answers that have to be right before anything is written: a folder pasted
 * into itself walks forever and starts copying on the way, and by the time
 * the filesystem objects there is a half-made tree to clean up.
 */
object PasteRules {

    fun refusal(
        clipboard: Clipboard?,
        targetSource: PaneSource,
        targetPath: String,
    ): PasteRefusal? {
        if (clipboard == null || clipboard.isEmpty) return PasteRefusal.NOTHING_HELD
        if (clipboard.source != targetSource) return PasteRefusal.NEEDS_TRANSFER

        val target = FilePath.normalize(targetPath)
        // Copying into the same folder is a duplicate, which is a fair thing
        // to want. Moving into it is not: the items are already there.
        if (clipboard.mode == ClipboardMode.MOVE &&
            FilePath.normalize(clipboard.directory) == target
        ) {
            return PasteRefusal.ALREADY_THERE
        }

        // A folder cannot swallow itself. Checked for every item, because one
        // bad one in a selection is enough to ruin the whole paste.
        if (clipboard.paths().any { FilePath.isWithin(target, it) }) {
            return PasteRefusal.INTO_ITSELF
        }
        return null
    }

    fun canPaste(clipboard: Clipboard?, targetSource: PaneSource, targetPath: String): Boolean =
        refusal(clipboard, targetSource, targetPath) == null
}
