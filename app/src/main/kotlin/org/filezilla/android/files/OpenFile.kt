package org.filezilla.android.files

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
     *
     * [type] overrides that, which is how the wildcard retry works: a
     * `.srt` is an `application/x-subrip` and almost nothing declares it,
     * so being able to ask the same question less precisely is the
     * difference between a chooser and "no app can open this".
     *
     * [app] sends it straight to one app rather than to whoever is
     * offering -- the remembered choice.
     */
    fun intentFor(
        context: Context,
        file: File,
        type: String? = null,
        app: ComponentName? = null,
    ): Intent? {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }.getOrNull() ?: return null

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type ?: mimeTypeOf(file.name))
            if (app != null) component = app
            // The receiving app gets to read this one file and nothing else,
            // for as long as it is looking at it.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * One app that offers to open a file, as the sheet shows it.
     *
     * The label and icon come from the package manager rather than being
     * guessed from the package name, because the user picks by the name
     * and picture they already know from their home screen.
     */
    data class Candidate(
        val component: ComponentName,
        val label: String,
        /** The type this app was found under, which is what it is launched with. */
        val type: String,
    )

    /** Whoever will handle one MIME type. The seam the platform sits behind. */
    fun interface AppsOffering {
        fun forType(type: String): List<Candidate>
    }

    /**
     * The types to ask about, widening each time.
     *
     * This progression is the whole fix, so it is a value rather than a
     * loop body: Android calls a `.srt` an `application/x-subrip` and
     * almost nothing on a phone declares that exact string, while plenty
     * of apps declare the family and a good few declare a wildcard. Asking
     * once, precisely, is how a phone with four suitable apps answers
     * "none" -- which is what the user was looking at.
     */
    fun typesToAsk(fileName: String): List<String> {
        val exact = mimeTypeOf(fileName)
        return listOf(exact, exact.substringBefore('/') + "/" + "*", FALLBACK).distinct()
    }

    /**
     * Who can open a file called [fileName], most specific offer first.
     *
     * Pure, so the order and the de-duplication can be tested exactly;
     * [offering] is where the package manager goes. An app found under two
     * types is listed once, under the most specific of them, so the obvious
     * answer is the first row rather than an also-ran behind a wildcard.
     */
    fun candidatesFor(fileName: String, offering: AppsOffering): List<Candidate> {
        val found = LinkedHashMap<ComponentName, Candidate>()
        for (type in typesToAsk(fileName)) {
            for (candidate in offering.forType(type)) {
                found.getOrPut(candidate.component) { candidate.copy(type = type) }
            }
        }
        return found.values.toList()
    }

    /**
     * The same question, put to the phone.
     *
     * Needs the `<queries>` declaration in the manifest. Without it this
     * comes back empty on Android 11 and later however many apps are
     * installed, which looks exactly like having none -- and a file manager
     * that cannot name one app to open a file with is not much of one.
     */
    fun candidatesFor(context: Context, file: File): List<Candidate> =
        candidatesFor(file.name, appsOn(context))

    /** What the phone says it has, for one type. */
    private fun appsOn(context: Context) = AppsOffering { type ->
        val manager = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(
            android.net.Uri.parse("content://" + context.packageName + ".files/probe"),
            type,
        )
        runCatching { manager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY) }
            .getOrDefault(emptyList())
            // This app is not an answer to "what else opens this".
            .filterNot { it.activityInfo.packageName == context.packageName }
            .map {
                Candidate(
                    component = ComponentName(
                        it.activityInfo.packageName,
                        it.activityInfo.name,
                    ),
                    label = it.loadLabel(manager).toString(),
                    type = type,
                )
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
