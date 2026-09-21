package org.filezilla.android.service

import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import org.filezilla.android.R
import org.filezilla.android.ui.OpenAt
import org.filezilla.android.ui.Screen
import org.filezilla.android.transfer.ActiveProgress
import org.filezilla.ftp.journal.TransferDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TransferNotificationsTest {

    private lateinit var notifications: TransferNotifications

    @Before
    fun setUp() {
        notifications = TransferNotifications(ApplicationProvider.getApplicationContext())
    }

    private fun progress(
        speed: Long? = 6_500_000,
        direction: TransferDirection = TransferDirection.DOWNLOAD,
    ) = ActiveProgress(
        id = "one",
        remotePath = "/HDD1/One.Night.Only.mkv",
        direction = direction,
        bytes = 102_100_000,
        totalBytes = 2_000_000_000,
        bytesPerSecond = speed,
        startedAtMillis = 1,
    )

    private fun Notification.text(): String =
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

    private fun Notification.subText(): String =
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

    @Test
    fun `the arrow points the way the bytes are going`() {
        // The reported bug: an upload showed the download arrow, so a file
        // going up the tray had an arrow pointing down at it. The small
        // icon has to follow the transfer's own direction.
        val up = notifications.build(progress(direction = TransferDirection.UPLOAD), queued = 0)
        val down = notifications.build(progress(direction = TransferDirection.DOWNLOAD), queued = 0)
        assertEquals(android.R.drawable.stat_sys_upload, up.smallIcon.resId)
        assertEquals(android.R.drawable.stat_sys_download, down.smallIcon.resId)
    }

    @Test
    fun `the speed is on the line the user reads`() {
        val text = notifications.build(progress(), queued = 0).text()

        assertTrue("speed missing from \"$text\"", text.contains("6.5 MB/s"))
        assertTrue("size missing from \"$text\"", text.contains("2.0 GB"))
    }

    /**
     * The bug this pins: the speed and the queue count both wrote the
     * notification's subtext, and the queue count won. Since a queue is the
     * normal state while transfers run, the speed was missing almost whenever
     * it mattered.
     */
    @Test
    fun `a queued transfer does not take the speed away`() {
        val notification = notifications.build(progress(), queued = 3)

        assertTrue(
            "speed missing from \"${notification.text()}\"",
            notification.text().contains("6.5 MB/s"),
        )
        assertTrue(
            "queue count missing from \"${notification.subText()}\"",
            notification.subText().contains("3"),
        )
    }

    /** A size the server never gave is no reason to hide how fast it is going. */
    @Test
    fun `the speed shows even when the total size is unknown`() {
        val unsized = progress().copy(totalBytes = null)

        val text = notifications.build(unsized, queued = 0).text()

        assertTrue("speed missing from \"$text\"", text.contains("6.5 MB/s"))
    }

    /** Before there is enough of the transfer to measure, it says nothing. */
    @Test
    fun `no speed is claimed before one has been measured`() {
        val text = notifications.build(progress(speed = null), queued = 0).text()

        assertTrue("invented a speed: \"$text\"", !text.contains("/s"))
        assertTrue("size missing from \"$text\"", text.contains("2.0 GB"))
    }

    /** What a pending intent will actually launch. */
    private fun PendingIntent.opens(): Screen? = OpenAt.screenFor(shadowOf(this).savedIntent)

    /**
     * Where the notification sends you.
     *
     * Both of these said "tap to see the transfer list" and carried a bare
     * intent for the activity, which only brings the app forward on whatever
     * screen it was last on. The test reaches into the pending intent and
     * asks what it opens, because the wording and the destination are written
     * in different places and only the wording was ever checked.
     */
    @Test
    fun `the progress notification opens the transfer list`() {
        val notification = notifications.build(progress(), queued = 0)

        assertEquals(Screen.QUEUE, notification.contentIntent.opens())
    }

    @Test
    fun `the finished notification opens the transfer list`() {
        notifications.announce(QueueNotice(R.string.done_all, listOf(3)))

        val manager: NotificationManager =
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).allNotifications.single()

        assertEquals(Screen.QUEUE, posted.contentIntent.opens())
    }

    /** The stop action is the one that must not open anything. */
    @Test
    fun `stopping is still a service action, not a screen`() {
        val stop = notifications.build(progress(), queued = 0).actions.single().actionIntent

        assertEquals(
            TransferService.ACTION_STOP,
            shadowOf(stop).savedIntent.action,
        )
    }

    @Test
    fun `waiting for the network says so rather than showing a stale bar`() {
        val text = notifications.build(progress = null, queued = 0, heldForNetwork = true).text()

        assertTrue("\"$text\"", text.isNotBlank())
    }
}
