package org.filezilla.android.files

import android.content.ComponentName
import android.content.Context

/**
 * Which app opens which kind of file, as the user decided.
 *
 * Tapping a file used to build an `ACTION_VIEW` and hope. That works for a
 * photo and fails for most of what a file manager actually holds: the
 * platform calls a `.srt` an `application/x-subrip` and almost nothing
 * declares it, a `.7z` and a `.zip` usually nothing at all, and the
 * wildcard the app fell back on matches very little on a modern phone --
 * so a tap produced "no app can open this" for files the user had several
 * apps for.
 *
 * The answer is not a better guess at the type. It is to let the user say,
 * once per extension, and then remember -- which is also what they asked
 * for. Remembered against the extension rather than the MIME type, because
 * the extension is what the user sees and what they are choosing about:
 * they are not deciding about `application/x-subrip`, they are deciding
 * about subtitles.
 */
class FileAssociations(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("file-associations", Context.MODE_PRIVATE)

    /** The app chosen for [extension], or null if none has been. */
    fun appFor(extension: String): ComponentName? {
        val stored = prefs.getString(key(extension), null) ?: return null
        return ComponentName.unflattenFromString(stored)
    }

    /** The app chosen for the kind of file [name] is, if any. */
    fun appForFile(name: String): ComponentName? =
        extensionOf(name)?.let { appFor(it) }

    fun remember(extension: String, app: ComponentName) {
        prefs.edit().putString(key(extension), app.flattenToString()).apply()
    }

    fun forget(extension: String) {
        prefs.edit().remove(key(extension)).apply()
    }

    /**
     * Every choice made, by extension.
     *
     * Sorted so the management screen reads the same way twice running --
     * a list that reorders itself between visits is one nobody trusts.
     */
    fun all(): List<Pair<String, ComponentName>> = prefs.all.keys
        .mapNotNull { stored ->
            val extension = stored.removePrefix(PREFIX).takeIf { it != stored } ?: return@mapNotNull null
            appFor(extension)?.let { extension to it }
        }
        .sortedBy { it.first }

    private fun key(extension: String) = PREFIX + extension.lowercase()

    companion object {
        private const val PREFIX = "ext."

        /**
         * The extension of [name], lowercased, or null when it has none.
         *
         * The dot has to be inside the name: ".bashrc" is a file called
         * .bashrc, not a "bashrc" file, and the same rule the type lookup
         * uses has to apply here or the two would disagree about what a
         * dotfile is.
         */
        fun extensionOf(name: String): String? {
            val cut = name.lastIndexOf('.')
            if (cut <= 0) return null
            return name.substring(cut + 1).lowercase().takeIf { it.isNotEmpty() }
        }
    }
}
