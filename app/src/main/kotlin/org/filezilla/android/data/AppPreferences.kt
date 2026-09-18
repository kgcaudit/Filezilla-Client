package org.filezilla.android.data

import android.content.Context
import android.net.Uri
import org.filezilla.android.transfer.NetworkPolicy
import org.filezilla.android.ui.BrowseOptions
import org.filezilla.android.ui.SortKey
import org.filezilla.android.ui.ViewMode

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

    /**
     * Whether transfers are held back for an unmetered connection.
     *
     * Off by default: turning it on for someone who has no Wi-Fi would stop
     * their transfers with no obvious cause. The user asks for it.
     */
    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    /** The same setting as the queue reads it. */
    val networkPolicy: NetworkPolicy
        get() = if (wifiOnly) NetworkPolicy.UNMETERED else NetworkPolicy.ANY

    /**
     * How this person likes a directory shown. Remembered, because re-picking
     * the sort every time the app opens is exactly the kind of small friction
     * that makes a tool feel unfinished.
     */
    var browseOptions: BrowseOptions
        get() = BrowseOptions(
            sortKey = enumOrDefault(prefs.getString(KEY_SORT, null), SortKey.NAME),
            ascending = prefs.getBoolean(KEY_ASCENDING, true),
            foldersFirst = prefs.getBoolean(KEY_FOLDERS_FIRST, true),
            showHidden = prefs.getBoolean(KEY_SHOW_HIDDEN, false),
            viewMode = enumOrDefault(prefs.getString(KEY_VIEW, null), ViewMode.LIST),
        )
        set(value) {
            prefs.edit()
                .putString(KEY_SORT, value.sortKey.name)
                .putBoolean(KEY_ASCENDING, value.ascending)
                .putBoolean(KEY_FOLDERS_FIRST, value.foldersFirst)
                .putBoolean(KEY_SHOW_HIDDEN, value.showHidden)
                .putString(KEY_VIEW, value.viewMode.name)
                .apply()
        }

    /** A stored name that no longer exists falls back rather than throwing. */
    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private companion object {
        const val KEY_DOWNLOAD_FOLDER = "download_folder"
        const val KEY_SORT = "browse_sort"
        const val KEY_ASCENDING = "browse_ascending"
        const val KEY_FOLDERS_FIRST = "browse_folders_first"
        const val KEY_SHOW_HIDDEN = "browse_show_hidden"
        const val KEY_VIEW = "browse_view"
        const val KEY_WIFI_ONLY = "wifi_only"
    }
}
