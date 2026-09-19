package org.filezilla.android.files

import android.content.Context
import android.os.Environment
import org.filezilla.android.R
import java.io.File

/** A place a pane can start from: a whole volume, or a folder worth a shortcut. */
data class StorageRoot(
    val path: String,
    val label: String,
    val kind: Kind,
) {
    enum class Kind { INTERNAL, SD_CARD, SHORTCUT }
}

/**
 * The volume root an app-specific directory sits on.
 *
 * Android hands out `/storage/XXXX-XXXX/Android/data/<package>/files` and
 * offers no supported way to ask for the volume root itself below API 30, so
 * the root is the part in front of `/Android/`. String work, and tested,
 * because the alternative is trimming a fixed number of segments and getting
 * a different answer on the next manufacturer's layout.
 *
 * @return the volume root, or null when the path is not of that shape.
 */
fun volumeRootOf(appSpecificDir: String): String? {
    val marker = "/Android/"
    val cut = appSpecificDir.indexOf(marker)
    if (cut <= 0) return null
    return appSpecificDir.substring(0, cut)
}

/**
 * Where the panes can begin.
 *
 * The device's own storage, whatever removable volumes it has, and a shortcut
 * to Downloads -- which is where a file manager on a phone starts most of the
 * time, and which the Storage Access Framework cannot reach at all.
 */
class StorageVolumes(private val context: Context) {

    fun roots(): List<StorageRoot> {
        val roots = mutableListOf<StorageRoot>()

        val internal = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        if (internal != null) {
            roots += StorageRoot(
                internal.absolutePath,
                context.getString(R.string.storage_internal),
                StorageRoot.Kind.INTERNAL,
            )
        }

        // Every volume the app has a directory on, minus the primary one,
        // which is already above. There is no other way to learn about an SD
        // card before API 30 that does not depend on a vendor path.
        val seen = mutableSetOf(internal?.absolutePath)
        for (dir in runCatching { context.getExternalFilesDirs(null) }.getOrNull().orEmpty()) {
            val root = dir?.absolutePath?.let(::volumeRootOf) ?: continue
            if (!seen.add(root)) continue
            roots += StorageRoot(root, File(root).name, StorageRoot.Kind.SD_CARD)
        }

        val downloads = runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()
        if (downloads != null) {
            roots += StorageRoot(
                downloads.absolutePath,
                context.getString(R.string.storage_downloads),
                StorageRoot.Kind.SHORTCUT,
            )
        }

        return roots
    }

    /**
     * Where the local pane opens when it has nothing remembered.
     *
     * Downloads, because that is where a phone puts what the user fetched and
     * what they are most likely to be looking for -- and, until this, the one
     * folder the app could not reach.
     */
    fun defaultPath(): String =
        runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        }.getOrNull()
            ?: runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrNull()
            ?: FilePath.ROOT
}
