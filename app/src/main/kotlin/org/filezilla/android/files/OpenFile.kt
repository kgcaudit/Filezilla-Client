package org.filezilla.android.files

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handing a file on the phone to whatever opens it.
 *
 * Tapping a file used to start a download, on both sides. On a server that is
 * right; on the phone the file is already here, and with no download folder
 * chosen the tap opened the system's folder picker instead -- an answer to a
 * question nobody had asked.
 */
object OpenFile {

    /**
     * An intent that opens [file], or null when nothing could.
     *
     * The type comes from the platform's own extension table rather than a
     * list kept here: it knows mkv, srt and several hundred others, and a
     * type this app has never heard of is the common case.
     */
    fun intentFor(context: Context, file: File): Intent? {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }.getOrNull() ?: return null

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeTypeOf(file.name))
            // The receiving app gets to read this one file and nothing else,
            // for as long as it is looking at it.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** The type a name implies, or a generic one when the platform has no idea. */
    fun mimeTypeOf(name: String): String {
        // The dot has to be inside the name, not the start of it: ".bashrc"
        // is a file called .bashrc, not a "bashrc" file, and splitting on the
        // last dot alone hands the platform the whole name as an extension.
        val cut = name.lastIndexOf('.')
        if (cut <= 0) return FALLBACK
        val extension = name.substring(cut + 1).lowercase()
        if (extension.isEmpty()) return FALLBACK
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: FALLBACK
    }

    /**
     * What to say when the extension means nothing to the platform.
     *
     * A wildcard rather than application/octet-stream, which is the more honest
     * answer and the less useful one: almost nothing declares it can view an
     * octet-stream, so the phone answers a tap with "no app can open this"
     * even when several apps would happily have tried. A wildcard puts the
     * chooser up instead and lets the user say which app this kind of file
     * belongs to -- and the chooser is where the phone remembers that answer.
     */
    const val FALLBACK = "*/*"
}
