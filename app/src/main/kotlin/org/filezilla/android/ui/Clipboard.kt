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
     * Between two servers.
     *
     * FTP has no copy, so this would mean fetching every byte and sending it
     * straight back -- twice the data for something that looks like a local
     * operation. Named rather than hidden, so the bar can say why.
     */
    BETWEEN_SERVERS,
}

/** What a paste will actually do, once it is allowed. */
enum class PasteKind {
    /** Within one place: a file operation. */
    FILE_OPERATION,

    /** Phone to server: an upload. */
    UPLOAD,

    /** Server to phone: a download. */
    DOWNLOAD,
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

        // Between two places the paste is a transfer, and the only pairing
        // that cannot be one is server to server: FTP has no copy command, so
        // it would mean pulling every byte down and pushing it back up.
        if (clipboard.source != targetSource) {
            return if (clipboard.source is PaneSource.Remote && targetSource is PaneSource.Remote) {
                PasteRefusal.BETWEEN_SERVERS
            } else {
                null
            }
        }

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

    /** What an allowed paste would do. Null when it would not be allowed. */
    fun kind(clipboard: Clipboard?, targetSource: PaneSource): PasteKind? {
        val from = clipboard?.source ?: return null
        return when {
            from == targetSource -> PasteKind.FILE_OPERATION
            from is PaneSource.Local && targetSource is PaneSource.Remote -> PasteKind.UPLOAD
            from is PaneSource.Remote && targetSource is PaneSource.Local -> PasteKind.DOWNLOAD
            else -> null
        }
    }
}
