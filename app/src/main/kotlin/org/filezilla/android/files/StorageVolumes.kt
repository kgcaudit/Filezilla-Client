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
 * How full a volume is.
 *
 * Shown beside a volume in the storage list, which is the one place a user
 * looks to decide where a download should go -- "SD card" alone does not
 * answer that, and "11.2 GB free of 128 GB" does.
 */
data class StorageCapacity(val freeBytes: Long, val totalBytes: Long) {

    /**
     * How much of the volume is in use, between 0 and 1.
     *
     * Clamped, and guarded against a total of zero. Neither is defensive
     * padding: a volume that cannot be measured reports zeroes, and a bar
     * drawn from a division by zero is a crash rather than a blank bar.
     */
    val usedFraction: Float
        get() {
            if (totalBytes <= 0) return 0f
            return ((totalBytes - freeBytes).toFloat() / totalBytes).coerceIn(0f, 1f)
        }
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
 * The folder above [path], or null when going up would leave the volumes.
 *
 * A local pane must not walk above the storage it is browsing. Left to plain
 * path arithmetic it does: "/storage/emulated/0" leads to "/storage/emulated"
 * and on to "/", none of which an app may list -- so the up button kept
 * working, three more times, and each press produced "this folder could not
 * be read" where the files had been.
 *
 * [roots] are the volumes, not the shortcuts: Downloads sits inside internal
 * storage and is somewhere to jump to, not a floor to stop at.
 */
fun localParent(path: String, roots: List<String>): String? {
    val parent = FilePath.parent(path) ?: return null
    return parent.takeIf { candidate -> roots.any { FilePath.isWithin(candidate, it) } }
}

/**
 * The mount path of a storage volume, or null when it cannot be had.
 *
 * getDirectory is the public way from API 30 on. Before that there is none, so
 * the mount path is read by reflection of getPath -- a method the platform has
 * carried on StorageVolume unchanged for years, and the fallback every file
 * manager uses to reach a removable volume on those versions.
 */
private fun volumePath(volume: android.os.storage.StorageVolume): String? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        volume.directory?.absolutePath
    } else {
        runCatching {
            android.os.storage.StorageVolume::class.java.getMethod("getPath").invoke(volume) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

/**
 * Where the panes can begin.
 *
 * The device's own storage, whatever removable volumes it has, and a shortcut
 * to Downloads -- which is where a file manager on a phone starts most of the
 * time, and which the Storage Access Framework cannot reach at all.
 */
class StorageVolumes(private val context: Context) {

    /** Just the volumes, which is what bounds how far up a pane may walk. */
    fun volumePaths(): List<String> =
        roots().filter { it.kind != StorageRoot.Kind.SHORTCUT }.map { it.path }

    @Volatile
    private var known: List<StorageRoot>? = null

    /**
     * The places a pane can start from, worked out once.
     *
     * Asking the platform costs a handful of filesystem calls, and this is
     * now read while drawing -- the tab's name and every crumb of the trail
     * come from it -- so it would otherwise run on each frame. Forgotten
     * whenever storage access is re-checked, which is when a card being put
     * in would show up.
     */
    fun roots(): List<StorageRoot> = known ?: findRoots().also { known = it }

    /** Drops what was worked out, so the next ask looks again. */
    fun forget() {
        known = null
    }

    private fun findRoots(): List<StorageRoot> {
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

        // A card in a USB reader, or a USB drive, plugged in through OTG. The
        // system makes no app-specific directory on it, so the loop above never
        // sees it -- but the StorageManager lists every mounted volume, so it is
        // found here instead. Only mounted, non-primary volumes are taken; the
        // primary one is the internal storage already above.
        val manager = runCatching {
            context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        }.getOrNull()
        for (volume in runCatching { manager?.storageVolumes }.getOrNull().orEmpty()) {
            if (volume.isPrimary) continue
            if (runCatching { volume.state }.getOrNull() != Environment.MEDIA_MOUNTED) continue
            val path = volumePath(volume) ?: continue
            if (!seen.add(path)) continue
            val label = runCatching { volume.getDescription(context) }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: File(path).name
            roots += StorageRoot(path, label, StorageRoot.Kind.SD_CARD)
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
     * How full the volume holding [path] is, or null when it cannot be read.
     *
     * Null rather than zeroes, so the list can leave the line out entirely
     * instead of claiming a volume is empty.
     */
    fun capacityOf(path: String): StorageCapacity? = runCatching {
        val stat = android.os.StatFs(path)
        StorageCapacity(
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
        )
    }.getOrNull()?.takeIf { it.totalBytes > 0 }

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
