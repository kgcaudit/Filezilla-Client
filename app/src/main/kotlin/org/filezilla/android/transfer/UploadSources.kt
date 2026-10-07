package org.filezilla.android.transfer

import android.net.Uri

/**
 * Thrown when a queued upload's local file cannot be found when the upload
 * comes to run -- moved somewhere a search could not reach, or deleted.
 *
 * Its own type so the queue can tell it apart from a dropped connection: a
 * missing file will not come back by trying again, so it is a permanent
 * failure, not one to spend the retry budget on. [message] is already the line
 * to show the user.
 */
class SourceMissingException(message: String) : Exception(message)

/**
 * Where a queued upload's bytes are read from, resolved as the upload starts.
 *
 * Between joining the queue and running, an upload's file may have been moved
 * or renamed on the phone. This is the seam that tries to find it again, so a
 * harmless reorganisation does not turn a queued upload into a dead error. The
 * Android that can search the device is [AndroidUploadSources]; the default
 * here changes nothing, for the tests and callers that do not need recovery.
 */
fun interface UploadSources {

    /**
     * The URI to actually read the upload from, given the one [stored] in the
     * record and the [size] it had when queued.
     *
     * Returns [stored] unchanged when the file is still there. Returns a new
     * URI when the file was found again somewhere else -- the caller records it,
     * so a restart and the queue screen see the file where it now is. Throws
     * [SourceMissingException] when it is gone for good.
     */
    fun resolve(stored: Uri, size: Long?): Uri

    companion object {
        /** Leaves every source as it is: the behaviour before recovery existed. */
        val AsIs = UploadSources { stored, _ -> stored }
    }
}
