package org.filezilla.android.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether an upload continues a file on the server or replaces it.
 *
 * The bug: it always continued. An upload with resume on treats a file
 * already there as a half-sent copy of the one being sent and appends the
 * rest, so sending over an existing file spliced two different files together
 * -- silently, with nothing asked and nothing said afterwards.
 */
class UploadOverwriteTest {

    @Test
    fun `an ordinary upload may resume`() {
        // Nothing in the destination column is the ordinary case: a transfer
        // interrupted partway should pick up where it stopped.
        assertTrue(uploadResumes(null))
    }

    @Test
    fun `an upload the user chose to replace does not resume`() {
        assertFalse(uploadResumes(OVERWRITE_MARKER))
    }

    /**
     * The column is a download's destination folder, and those records were
     * written long before an upload could replace anything. They must not
     * start reading as replacements.
     */
    @Test
    fun `a download destination is not a replacement marker`() {
        assertTrue(uploadResumes("content://com.android.externalstorage/tree/primary%3ADownload"))
        assertTrue(uploadResumes("file:///storage/emulated/0/Download"))
        assertTrue(uploadResumes(""))
    }
}
