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
     * Whether the person has been told that opening a server file is
     * looking at it, not working on it.
     *
     * Said once and then not again. Opening a text file, editing it and
     * saving is a thing somebody will do, and what they save is a copy in
     * this app's cache -- so the one moment it can be said usefully is
     * before the first file ever opens. Saying it every time would train
     * them to dismiss it, which is the same as not saying it.
     */
    var warnedThatViewingIsReadOnly: Boolean
        get() = prefs.getBoolean(KEY_READ_ONLY_WARNED, false)
        set(value) = prefs.edit().putBoolean(KEY_READ_ONLY_WARNED, value).apply()

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

    /**
     * Which way the comic reader turns its pages: right-to-left for manga,
     * left-to-right otherwise. One setting for the app, flipped in the reader
     * and remembered, because a reader who reads manga reads it that way every
     * time and re-choosing on every book is the friction a setting exists to
     * remove.
     */
    var readerRtl: Boolean
        get() = prefs.getBoolean(KEY_READER_RTL, false)
        set(value) = prefs.edit().putBoolean(KEY_READER_RTL, value).apply()

    /** Whether the reader pairs pages into spreads when the screen is wide. */
    var readerTwoPage: Boolean
        get() = prefs.getBoolean(KEY_READER_TWO_PAGE, true)
        set(value) = prefs.edit().putBoolean(KEY_READER_TWO_PAGE, value).apply()

    /** Whether a webtoon is read in a narrow centred column rather than full width. */
    var readerWebtoonNarrow: Boolean
        get() = prefs.getBoolean(KEY_READER_WEBTOON_NARROW, false)
        set(value) = prefs.edit().putBoolean(KEY_READER_WEBTOON_NARROW, value).apply()

    /** Whether the last compress made one archive per item rather than one for all. */
    var compressSeparate: Boolean
        get() = prefs.getBoolean(KEY_COMPRESS_SEPARATE, false)
        set(value) = prefs.edit().putBoolean(KEY_COMPRESS_SEPARATE, value).apply()

    /** Whether the last compress dropped the wrapping folder, contents at the root. */
    var compressFlat: Boolean
        get() = prefs.getBoolean(KEY_COMPRESS_FLAT, false)
        set(value) = prefs.edit().putBoolean(KEY_COMPRESS_FLAT, value).apply()

    /**
     * The narrow column's width as a percent of the screen, so a webtoon whose
     * lettering is small can be widened and one whose lettering is large pulled
     * in. Kept within a readable band rather than allowed to vanish or to fill
     * the screen, which would make the setting itself pointless.
     */
    var readerWebtoonWidthPercent: Int
        get() = prefs.getInt(KEY_READER_WEBTOON_WIDTH, DEFAULT_WEBTOON_WIDTH)
            .coerceIn(MIN_WEBTOON_WIDTH, MAX_WEBTOON_WIDTH)
        set(value) = prefs.edit()
            .putInt(KEY_READER_WEBTOON_WIDTH, value.coerceIn(MIN_WEBTOON_WIDTH, MAX_WEBTOON_WIDTH))
            .apply()

    /**
     * The page a comic was last left on, so it reopens where it was put down.
     *
     * Keyed by the book, capped the same way folder settings are: a reader
     * opens a great many comics over a phone's life, and one entry per book
     * kept for ever is a preferences file that only grows. The oldest book's
     * place is forgotten first.
     */
    fun comicPage(key: String): Int? =
        prefs.getInt(comicPageKey(key), -1).takeIf { it >= 0 }

    fun setComicPage(key: String, page: Int) {
        val edit = prefs.edit()
        rememberComic(edit, key)
        edit.putInt(comicPageKey(key), page)
        edit.apply()
    }

    /**
     * How a comic was read -- one long strip, or a page at a time -- so it, and
     * every volume of its series, opens the way it was left rather than being
     * set again each time. Null until a book has been read one way on purpose.
     */
    fun comicWebtoon(key: String): Boolean? =
        if (prefs.contains(comicWebtoonKey(key))) prefs.getBoolean(comicWebtoonKey(key), false) else null

    fun setComicWebtoon(key: String, webtoon: Boolean) {
        val edit = prefs.edit()
        rememberComic(edit, key)
        edit.putBoolean(comicWebtoonKey(key), webtoon)
        edit.apply()
    }

    /**
     * How far into a video or a sound it was left, in milliseconds, so it
     * reopens where it stopped. Capped the same way comics are; the oldest is
     * forgotten first. Zero (or none) means start from the beginning.
     */
    fun mediaPosition(key: String): Long =
        prefs.getLong(mediaPositionKey(key), 0L).coerceAtLeast(0L)

    fun setMediaPosition(key: String, positionMs: Long) {
        val edit = prefs.edit()
        rememberMedia(edit, key)
        edit.putLong(mediaPositionKey(key), positionMs.coerceAtLeast(0L))
        edit.apply()
    }

    /**
     * Which subtitle a file was last watched with, so it comes back the same
     * rather than defaulting every time. "off" means the reader turned
     * subtitles off; anything else is a token naming the chosen track (see the
     * player). Kept beside the position under the same budget, and forgotten
     * with it.
     */
    fun subtitleChoice(key: String): String? =
        prefs.getString(mediaSubtitleKey(key), null)?.ifEmpty { null }

    fun setSubtitleChoice(key: String, token: String) {
        val edit = prefs.edit()
        rememberMedia(edit, key)
        edit.putString(mediaSubtitleKey(key), token)
        edit.apply()
    }

    /**
     * Moves [key] to the front of the remembered-media list and drops the oldest
     * past the cap, forgetting its position and subtitle together so the two
     * never fall out of step over which files are still remembered.
     */
    private fun rememberMedia(edit: android.content.SharedPreferences.Editor, key: String) {
        val keys = (mediaKeys() - key).toMutableList()
        keys += key
        while (keys.size > MAX_REMEMBERED_MEDIA) {
            val dropped = keys.removeAt(0)
            edit.remove(mediaPositionKey(dropped))
            edit.remove(mediaSubtitleKey(dropped))
        }
        edit.putString(KEY_MEDIA_KEYS, keys.joinToString(KEY_SEPARATOR))
    }

    private fun mediaKeys(): List<String> =
        prefs.getString(KEY_MEDIA_KEYS, null)
            ?.split(KEY_SEPARATOR)
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private fun mediaPositionKey(key: String) = "$KEY_MEDIA_POSITION${comicHash(key)}"

    private fun mediaSubtitleKey(key: String) = "$KEY_MEDIA_SUBTITLE${comicHash(key)}"

    /**
     * How the player draws subtitles: a text height as a fraction of the screen,
     * and a colour. One setting for the whole app rather than per file, since a
     * reader who wants larger yellow captions wants them on everything. The
     * default height is media3's own, and the default colour white.
     */
    fun subtitleScale(): Float =
        prefs.getFloat(KEY_SUBTITLE_SCALE, DEFAULT_SUBTITLE_SCALE)
            .coerceIn(MIN_SUBTITLE_SCALE, MAX_SUBTITLE_SCALE)

    fun subtitleColor(): Int = prefs.getInt(KEY_SUBTITLE_COLOR, DEFAULT_SUBTITLE_COLOR)

    fun setSubtitleStyle(scale: Float, color: Int) {
        prefs.edit()
            .putFloat(KEY_SUBTITLE_SCALE, scale.coerceIn(MIN_SUBTITLE_SCALE, MAX_SUBTITLE_SCALE))
            .putInt(KEY_SUBTITLE_COLOR, color)
            .apply()
    }

    private fun comicKeys(): List<String> =
        prefs.getString(KEY_COMIC_KEYS, null)
            ?.split(KEY_SEPARATOR)
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /**
     * Moves [key] to the front of the remembered-books list and drops the
     * oldest past the cap, forgetting everything kept about a dropped book. One
     * list for both what page a book was on and how it was read, so the two
     * never fall out of step over which books are still remembered.
     */
    private fun rememberComic(edit: android.content.SharedPreferences.Editor, key: String) {
        val keys = (comicKeys() - key).toMutableList()
        keys += key
        while (keys.size > MAX_REMEMBERED_COMICS) {
            val dropped = keys.removeAt(0)
            edit.remove(comicPageKey(dropped))
            edit.remove(comicWebtoonKey(dropped))
        }
        edit.putString(KEY_COMIC_KEYS, keys.joinToString(KEY_SEPARATOR))
    }

    // Digested so a long file path does not become an unbounded preferences
    // key, and so a path with any character in it is still a valid key. A
    // 32-bit hashCode was tried and is a hazard: two books whose hashes collide
    // would share -- and overwrite -- each other's saved page, and the eviction
    // list would fall out of step with the stored values. A SHA-256 prefix does
    // not collide in any collection a phone will ever hold.
    private fun comicHash(key: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }

    private fun comicPageKey(key: String) = "$KEY_COMIC_PAGE${comicHash(key)}"

    private fun comicWebtoonKey(key: String) = "$KEY_COMIC_WEBTOON${comicHash(key)}"

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
        const val KEY_READ_ONLY_WARNED = "viewing_read_only_warned"
        const val KEY_MOVE_CLEANUP = "move_cleanup"
        const val KEY_FOLDER_OPTIONS = "folder_options_"
        const val KEY_FOLDER_OPTION_KEYS = "folder_options_keys"
        const val KEY_READER_RTL = "reader_rtl"
        const val KEY_READER_TWO_PAGE = "reader_two_page"
        const val KEY_READER_WEBTOON_NARROW = "reader_webtoon_narrow"
        const val KEY_READER_WEBTOON_WIDTH = "reader_webtoon_width"
        const val KEY_COMPRESS_SEPARATE = "compress_separate"
        const val KEY_COMPRESS_FLAT = "compress_flat"

        const val MIN_WEBTOON_WIDTH = 40
        const val MAX_WEBTOON_WIDTH = 100
        const val DEFAULT_WEBTOON_WIDTH = 68
        const val KEY_COMIC_PAGE = "comic_page_"
        const val KEY_COMIC_WEBTOON = "comic_webtoon_"
        const val KEY_COMIC_KEYS = "comic_page_keys"
        const val KEY_MEDIA_POSITION = "media_pos_"
        const val KEY_MEDIA_SUBTITLE = "media_sub_"
        const val KEY_MEDIA_KEYS = "media_pos_keys"
        const val KEY_SUBTITLE_SCALE = "subtitle_scale"
        const val KEY_SUBTITLE_COLOR = "subtitle_color"

        // media3's SubtitleView.DEFAULT_TEXT_SIZE_FRACTION, and the range the
        // player's own subtitle settings offer -- roughly half again as small
        // to twice as large. White by default, the caption colour everyone
        // expects.
        const val DEFAULT_SUBTITLE_SCALE = 0.0533f
        const val MIN_SUBTITLE_SCALE = 0.03f
        const val MAX_SUBTITLE_SCALE = 0.12f
        const val DEFAULT_SUBTITLE_COLOR = 0xFFFFFFFF.toInt()

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

        /** How many comics keep their place; the oldest is forgotten first. */
        internal const val MAX_REMEMBERED_COMICS = 500
        internal const val MAX_REMEMBERED_MEDIA = 300
    }
}
