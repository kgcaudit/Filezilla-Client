package org.filezilla.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * That the app really is offered in another app's "Open with" for what it can
 * view, and is not offered for what it cannot.
 *
 * This is a manifest promise, and a manifest is easy to break by hand -- a
 * dropped data line, a missing DEFAULT category -- in a way nothing else would
 * catch. So it is asked of the platform the way the chooser does: resolve a
 * VIEW of this type and see whether this activity is among the answers.
 */
@RunWith(RobolectricTestRunner::class)
class OpenWithRegistrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun offeredFor(type: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://example.provider/media/file"), type)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        return context.packageManager.queryIntentActivities(intent, 0).any {
            it.activityInfo.packageName == context.packageName &&
                it.activityInfo.name == MainActivity::class.java.name
        }
    }

    @Test
    fun `offered for the kinds the viewers handle`() {
        assertTrue("audio", offeredFor("audio/mpeg"))
        assertTrue("video", offeredFor("video/mp4"))
        assertTrue("image", offeredFor("image/jpeg"))
        assertTrue("text", offeredFor("text/plain"))
        assertTrue("pdf", offeredFor("application/pdf"))
        assertTrue("epub", offeredFor("application/epub+zip"))
        assertTrue("zip", offeredFor("application/zip"))
    }

    @Test
    fun `not offered for a kind it cannot open`() {
        // An .apk is handed to the system installer, not viewed here, so the
        // app has no business in its "Open with".
        assertFalse(offeredFor("application/vnd.android.package-archive"))
    }
}
