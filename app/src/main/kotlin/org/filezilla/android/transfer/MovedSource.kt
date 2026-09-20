package org.filezilla.android.transfer

import android.net.Uri
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.journal.TransferState
import java.io.File

/**
 * Taking away what a move has finished moving.
 *
 * Deliberately small and deliberately suspicious. Everything here removes
 * something the user cannot get back, so each answer is "no" unless the
 * record says otherwise in as many words:
 *
 *  - the transfer has to be COMPLETED, not started, not interrupted;
 *  - it has to have been queued as half of a move;
 *  - and for a file on the phone, the path has to be one this app made -- a
 *    `file://` URI that a pane walked to, never a document the system picker
 *    handed over, which belongs to whatever app owns it.
 *
 * A move whose upload failed leaves its file exactly where it was. That is
 * the whole reason the removal waits for the transfer instead of happening
 * when the queue is built.
 */
object MovedSource {

    /** What became of a finished download's bytes. */
    enum class Delivery {
        /** In the folder the user chose. The only outcome a move may act on. */
        SAVED,

        /** The user kept the file already there, so these bytes were dropped. */
        KEPT_EXISTING,

        /** Still in app-private storage, to be published on the next run. */
        NOT_YET,
    }

    /** Whether [record] has earned the right to have its source removed. */
    fun isDue(record: TransferRecord): Boolean =
        record.removeSourceWhenDone && record.state == TransferState.COMPLETED

    /**
     * Whether the server's copy may go, now that the download has ended.
     *
     * [Delivery.SAVED] and nothing else. A partial in app-private storage is
     * somewhere the user cannot reach, so removing the server's copy while
     * the file is only there is a move into nowhere -- and "keep the one I
     * already have" is not delivery either: those bytes were dropped on
     * purpose, which would leave the server's copy the last one in
     * existence.
     */
    fun mayRemoveRemote(record: TransferRecord, delivery: Delivery): Boolean =
        isDue(record) && delivery == Delivery.SAVED

    /**
     * The file on the phone behind an upload's source, or null when there is
     * not one this app may delete.
     */
    fun localFileOf(source: String): File? {
        val uri = runCatching { Uri.parse(source) }.getOrNull() ?: return null
        // Anything but a plain file is somebody else's to remove. A document
        // URI from the system picker is the case that matters: the user
        // chose to send that file, not to lose it.
        if (uri.scheme != "file") return null
        val path = uri.path ?: return null
        val file = File(path)
        // A directory is never an upload's source, and deleting one here
        // would be deleting something nothing has finished moving.
        return file.takeIf { it.isFile }
    }
}
