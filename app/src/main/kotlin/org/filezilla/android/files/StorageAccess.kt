package org.filezilla.android.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

/**
 * How a given Android version lets an app reach the whole of shared storage.
 *
 * There is no single answer, which is why this is an enum rather than a
 * boolean: the way in changed twice, and on one version there is no way in at
 * all. Getting this wrong means either asking for a permission that does
 * nothing or never asking for one that was needed, and both look to the user
 * like a file manager that cannot see their files.
 */
enum class AccessRoute {
    /**
     * Android 9 and below. A runtime permission covers it, because scoped
     * storage does not exist yet and `java.io.File` still reaches `/sdcard`.
     */
    RUNTIME_PERMISSION,

    /**
     * Android 11 and up. Not a permission dialog but a settings toggle the
     * user has to find, so the app has to explain itself before sending them
     * there -- there is no second chance prompt to fall back on.
     */
    ALL_FILES_SETTING,

    /**
     * Android 10, and only Android 10.
     *
     * Scoped storage arrived here and applies to us, since it is targeting
     * that decides it and we target 35. The permission that would lift it,
     * MANAGE_EXTERNAL_STORAGE, does not arrive until the next version. So
     * there is no route: on this one version the panes are limited to folders
     * the user grants one at a time.
     */
    UNAVAILABLE,
}

/**
 * Which route applies on [sdkInt].
 *
 * Pure, and tested at every boundary, because the boundaries are the whole
 * content: one version either side of each is a different answer, and the
 * middle case exists on exactly one version.
 */
fun accessRouteFor(sdkInt: Int): AccessRoute = when {
    sdkInt >= Build.VERSION_CODES.R -> AccessRoute.ALL_FILES_SETTING
    sdkInt == Build.VERSION_CODES.Q -> AccessRoute.UNAVAILABLE
    else -> AccessRoute.RUNTIME_PERMISSION
}

/** Whether the app can currently walk shared storage, and how to ask if not. */
class StorageAccess(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {

    val route: AccessRoute get() = accessRouteFor(sdkInt)

    /**
     * True when the panes can list any folder on the device.
     *
     * Asked of the platform every time rather than remembered: the user can
     * take this away in Settings while the app is in the background, and a
     * remembered yes would turn into a screen of empty folders with no
     * explanation.
     */
    fun isGranted(): Boolean = when (route) {
        AccessRoute.ALL_FILES_SETTING ->
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

        AccessRoute.RUNTIME_PERMISSION ->
            ContextCompat.checkSelfPermission(context, LEGACY_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED

        AccessRoute.UNAVAILABLE -> false
    }

    /** The runtime permissions to ask for, empty when asking is not the way. */
    fun permissionsToRequest(): Array<String> =
        if (route == AccessRoute.RUNTIME_PERMISSION) arrayOf(LEGACY_PERMISSION) else emptyArray()

    /**
     * Where to send the user, or null when there is nowhere to send them.
     *
     * The per-app screen is asked for first and the whole list is the
     * fallback: some devices have no per-app screen and throw rather than
     * showing one, and landing on the full list beats landing nowhere.
     */
    fun settingsIntent(): Intent? {
        if (route != AccessRoute.ALL_FILES_SETTING) return null
        val perApp = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )
        return if (perApp.resolveActivity(context.packageManager) != null) {
            perApp
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    private companion object {
        const val LEGACY_PERMISSION = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    }
}
