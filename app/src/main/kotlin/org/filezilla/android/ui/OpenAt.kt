package org.filezilla.android.ui

import android.content.Context
import android.content.Intent

/**
 * Which screen something outside the app is asking for.
 *
 * A notification that says "tap to see the transfer list" has to open the
 * transfer list. It opened the app. Both notifications carried a bare
 * intent for the activity, and the activity starts on the files screen
 * every time and reads nothing -- so tapping it brought the app forward on
 * whatever screen it was last on, which was almost never the one named in
 * the notification.
 *
 * The name is carried as a plain string rather than the enum's ordinal:
 * ordinals change when somebody adds a screen, and a pending intent handed
 * to the system outlives the build that made it.
 */
object OpenAt {

    private const val EXTRA = "org.filezilla.android.OPEN_AT"

    /** An intent that brings the app forward on [screen]. */
    fun intentTo(context: Context, screen: Screen): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA, screen.name)

    /** What [intent] asks for, or null when it asks for nothing in particular. */
    fun screenFor(intent: Intent?): Screen? {
        val name = intent?.getStringExtra(EXTRA) ?: return null
        // An unknown name is a build mismatch, not a reason to crash on a
        // notification tap.
        return Screen.entries.firstOrNull { it.name == name }
    }
}
