package org.filezilla.android.data

import android.content.Context
import android.net.Uri

/**
 * The handful of settings that are not worth a database table.
 *
 * The download folder is the one that matters: it is a Storage Access
 * Framework grant, and it has to outlive the activity that asked for it or a
 * transfer finishing tomorrow would have nowhere to put its file.
 */
class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("filezilla", Context.MODE_PRIVATE)

    var downloadFolder: Uri?
        get() = prefs.getString(KEY_DOWNLOAD_FOLDER, null)?.let(Uri::parse)
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_FOLDER, value?.toString()).apply()

    private companion object {
        const val KEY_DOWNLOAD_FOLDER = "download_folder"
    }
}
