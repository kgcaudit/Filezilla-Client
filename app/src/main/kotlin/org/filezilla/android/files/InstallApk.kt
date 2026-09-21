package org.filezilla.android.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Handing a downloaded `.apk` to the system's installer.
 *
 * A file manager's whole job for an apk is to pass it on, and this one
 * could not: tapping a downloaded apk offered the package installer, the
 * installer was launched, and nothing happened -- no install, no error, no
 * dialog. Two things were missing and one of them is invisible.
 *
 * Since Android 8 an app that starts an install has to declare
 * `REQUEST_INSTALL_PACKAGES`, and separately the user has to have allowed
 * *this* app to be a source of them. The declaration alone is not enough
 * and the permission is not one that can be asked for with a prompt: it is
 * a settings screen the user has to visit. An app that has neither reaches
 * the installer and is turned away silently, which is the worst of the
 * possible failures because there is nothing to read and nothing to fix.
 *
 * So this is asked *before* the installer is launched, and when the answer
 * is no the user is taken to the screen that says yes rather than to an
 * installer that will not talk to them.
 */
object InstallApk {

    /** What Android calls an installable package. */
    const val TYPE = "application/vnd.android.package-archive"

    /** True when [fileName] is one, by the same extension rule as everything else. */
    fun isPackage(fileName: String): Boolean =
        FileAssociations.extensionOf(fileName) == "apk"

    /**
     * Whether this app is allowed to be a source of installs.
     *
     * Below Android 8 there is no per-app setting -- "unknown sources" was
     * one switch for the whole phone -- so there is nothing here to check
     * and the installer will say its own piece.
     */
    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    /**
     * The settings screen that grants it, for this app.
     *
     * Addressed to this package rather than to the general list: the
     * general one makes the user find the app among every app on the
     * phone, and the whole reason they are being sent anywhere is that
     * something did not work.
     */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
}
