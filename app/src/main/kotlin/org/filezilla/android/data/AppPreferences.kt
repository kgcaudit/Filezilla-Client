package org.filezilla.android.data

import android.content.Context
import org.filezilla.android.transfer.NetworkPolicy
import org.filezilla.android.ui.BrowseOptions
import org.filezilla.android.ui.SortKey
import org.filezilla.android.ui.ViewMode

/**
 * The handful of settings that are not worth a database table.
 *
 * Nothing about where downloads land is here any more: that is the other
 * pane, which says where it is itself. It was a Storage Access Framework
 * grant remembered across runs, and a folder nobody could see.
 */
/**
 * One folder a finished move may have emptied.
 *
 * [siteId] is null for the phone. Kept as a site id rather than a whole site
 * because this outlives the paste, the screen, and possibly the process, and
 * a site that has been edited or deleted in between should be looked up
 * afresh -- or found to be gone, which is itself an answer.
 */
data class MovedFolder(val siteId: String?, val path: String) {

    fun encode(): String = "${siteId.orEmpty()}$SEPARATOR$path"

    companion object {
        /** A NUL, which no path on either side of a transfer can contain. */
        private const val SEPARATOR = '\u0000'

        fun decode(stored: String): MovedFolder? {
            val cut = stored.indexOf(SEPARATOR)
            if (cut < 0) return null
            val path = stored.substring(cut + 1)
            if (path.isEmpty()) return null
            return MovedFolder(stored.take(cut).ifEmpty { null }, path)
        }
    }
}

class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("filezilla", Context.MODE_PRIVATE)

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
     * The folder a pane was last looking at, and the server it was on.
     *
     * Per pane rather than one for the app: the two sides are used for
     * different things, and restoring both to the same folder would undo the
     * arrangement that makes the screen worth having. Remembered for the same
     * reason the sort is -- a file manager that opens on the same folder
     * every time makes the user walk back down to where they were, every
     * time. Null until they have been somewhere, which is what lets the
     * default apply on a first run and not afterwards.
     */
    fun panePath(pane: String, source: String): String? =
        prefs.getString(panePathKey(pane, source), null)

    fun setPanePath(pane: String, source: String, path: String?) =
        prefs.edit().putString(panePathKey(pane, source), path).apply()

    /** The saved server a pane was on, by id, or null when it was the phone. */
    fun paneSiteId(pane: String): String? = prefs.getString("$KEY_PANE_SITE$pane", null)

    fun setPaneSiteId(pane: String, siteId: String?) =
        prefs.edit().putString("$KEY_PANE_SITE$pane", siteId).apply()

    /** True when the pane showed the phone rather than a server. */
    fun paneIsLocal(pane: String): Boolean = prefs.getBoolean("$KEY_PANE_LOCAL$pane", false)

    fun setPaneIsLocal(pane: String, local: Boolean) =
        prefs.edit().putBoolean("$KEY_PANE_LOCAL$pane", local).apply()

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

    /**
     * The view options one folder was given of its own, or null.
     *
     * A photo folder wants a grid sorted by date and a folder of subtitles
     * wants a list sorted by name, and one setting for the whole app makes
     * the user change it on the way in and change it back on the way out.
     * So a folder may keep its own, and everything else goes on using the
     * one setting.
     *
     * Kept apart from [browseOptions] rather than replacing it: with no
     * override the global one still applies, which is what "이 폴더만" off
     * has to mean.
     */
    fun optionsForFolder(key: String): BrowseOptions? {
        val stored = prefs.getString(folderOptionsKey(key), null) ?: return null
        return decodeOptions(stored)
    }

    /** Null forgets the folder's own settings and lets the global ones apply. */
    fun setOptionsForFolder(key: String, options: BrowseOptions?) {
        val edit = prefs.edit()
        // Oldest first, and a list rather than a set because that is the
        // whole point: a set has no order, so "drop the oldest" would drop
        // whichever one the hash happened to put first. Written as one
        // delimited string for the same reason -- putStringSet gives the
        // order back scrambled.
        val keys = (folderOptionKeys() - key).toMutableList()
        if (options == null) {
            edit.remove(folderOptionsKey(key))
        } else {
            keys += key
            // A file manager visits thousands of folders, and a preferences
            // file that grows by one entry per folder visited for ever is a
            // leak with a slow fuse.
            while (keys.size > MAX_REMEMBERED_FOLDERS) {
                edit.remove(folderOptionsKey(keys.removeAt(0)))
            }
            edit.putString(folderOptionsKey(key), encodeOptions(options))
        }
        edit.putString(KEY_FOLDER_OPTION_KEYS, keys.joinToString(KEY_SEPARATOR))
        edit.apply()
    }

    private fun folderOptionKeys(): List<String> =
        prefs.getString(KEY_FOLDER_OPTION_KEYS, null)
            ?.split(KEY_SEPARATOR)
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private fun folderOptionsKey(key: String) = "$KEY_FOLDER_OPTIONS$key"

    private fun encodeOptions(options: BrowseOptions): String = listOf(
        options.sortKey.name,
        options.ascending.toString(),
        options.foldersFirst.toString(),
        options.showHidden.toString(),
        options.viewMode.name,
    ).joinToString("|")

    private fun decodeOptions(stored: String): BrowseOptions? {
        val parts = stored.split("|")
        if (parts.size != 5) return null
        return BrowseOptions(
            sortKey = enumOrDefault(parts[0], SortKey.NAME),
            ascending = parts[1].toBooleanStrictOrNull() ?: true,
            foldersFirst = parts[2].toBooleanStrictOrNull() ?: true,
            showHidden = parts[3].toBooleanStrictOrNull() ?: false,
            viewMode = enumOrDefault(parts[4], ViewMode.LIST),
        )
    }

    /**
     * Folders a move has still to clear away, once its queue has drained.
     *
     * Written when the paste is made and read when the queue finishes, which
     * may be minutes later and may be after the app has been killed and
     * restarted -- so it cannot be a field. Nothing in it can cost a file:
     * the sweep it feeds removes only folders that are completely empty.
     *
     * Each entry is a site id and a path, joined by a character no path
     * contains. An empty site id means the phone.
     */
    var foldersToClearAfterMove: Set<MovedFolder>
        get() = prefs.getStringSet(KEY_MOVE_CLEANUP, emptySet())
            .orEmpty()
            .mapNotNull(MovedFolder::decode)
            .toSet()
        set(value) = prefs.edit()
            .putStringSet(KEY_MOVE_CLEANUP, value.map { it.encode() }.toSet())
            .apply()

    /** A stored name that no longer exists falls back rather than throwing. */
    private fun panePathKey(pane: String, source: String) = "$KEY_PANE_PATH$pane/$source"

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    internal companion object {
        const val KEY_DOWNLOAD_FOLDER = "download_folder"
        const val KEY_PANE_PATH = "pane_path_"
        const val KEY_PANE_SITE = "pane_site_"
        const val KEY_PANE_LOCAL = "pane_local_"
        const val KEY_SORT = "browse_sort"
        const val KEY_ASCENDING = "browse_ascending"
        const val KEY_FOLDERS_FIRST = "browse_folders_first"
        const val KEY_SHOW_HIDDEN = "browse_show_hidden"
        const val KEY_VIEW = "browse_view"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_MOVE_CLEANUP = "move_cleanup"
        const val KEY_FOLDER_OPTIONS = "folder_options_"
        const val KEY_FOLDER_OPTION_KEYS = "folder_options_keys"

        /** A newline, which no path and no site id contains. */
        const val KEY_SEPARATOR = "\n"

        /**
         * How many folders may keep their own settings.
         *
         * Enough that the folders somebody actually arranges all keep
         * theirs, and small enough that walking a disk does not fill the
         * preferences file. The oldest goes when the limit is reached.
         *
         * Internal rather than private so the test can fill the budget
         * without writing the number down a second time.
         */
        internal const val MAX_REMEMBERED_FOLDERS = 200
    }
}
