package org.filezilla.android.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handing files on the phone to another app -- a chat, a mail, a cloud.
 *
 * The bottom bar could cut, copy, rename and delete, which are all things
 * done *inside* this app, and offered no way to send a file out of it. A file
 * manager whose files cannot leave is half a file manager.
 *
 * Only for files that are already on the phone. A file on a server is not
 * here: sharing one would mean downloading it first, which is a transfer, and
 * a transfer is something the user should start knowingly rather than
 * discover because a share sheet took a minute to open.
 */
object ShareFiles {

    /**
     * An intent that offers [files] to another app, or null when none of them
     * can be handed over.
     *
     * Folders are dropped rather than refused: picking a folder along with
     * six photos is an ordinary thing to do, and the useful answer is to
     * share the six.
     */
    fun intentFor(context: Context, files: List<File>): Intent? {
        val uris = files
            .filter { it.isFile }
            .mapNotNull { file ->
                runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                }.getOrNull()
            }
        if (uris.isEmpty()) return null

        val type = commonTypeOf(files.filter { it.isFile }.map { it.name })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris.single())
                setType(type)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
                setType(type)
            }
        }
        // The receiving app gets to read these files and nothing else, for as
        // long as it is holding them.
        return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * The type that describes all of [names] at once.
     *
     * Exactly when they agree, the family when only the family agrees, and a
     * wildcard when not even that. It matters because the share sheet builds
     * its list of apps from this: a gallery offers itself for `image/*` and
     * not for `*/*`, so calling three photos a wildcard buries the app the
     * user was reaching for under everything that accepts anything.
     */
    fun commonTypeOf(names: List<String>): String {
        val types = names.map { OpenFile.mimeTypeOf(it) }.distinct()
        if (types.isEmpty()) return OpenFile.FALLBACK
        types.singleOrNull()?.let { return it }
        // A name the platform did not recognise is already a wildcard, and it
        // drags everything with it: there is no family to agree on.
        if (OpenFile.FALLBACK in types) return OpenFile.FALLBACK
        val families = types.map { it.substringBefore('/') }.distinct()
        return families.singleOrNull()?.let { "$it/*" } ?: OpenFile.FALLBACK
    }
}
