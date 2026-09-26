package org.filezilla.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small thumbnails for the file list -- a picture's own image, a film's first
 * clear frame, a song's cover -- decoded off the main thread and held in a
 * memory cache so a scroll does not decode the same file twice.
 *
 * Local files only: a server file would have to be fetched first, which a scroll
 * cannot do, so a remote row keeps its kind tile. Anything with no thumbnail, or
 * that fails to decode, is remembered as a miss so it is not tried again on every
 * pass.
 */
object Thumbnails {
    // Stored for a file that has no thumbnail, so a second look does not decode
    // it again only to fail again.
    private val MISS = Any()

    // A few megabytes of decoded thumbnails, sized by their real byte cost so the
    // cap is a memory budget rather than a count.
    private val cache = object : LruCache<String, Any>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Any): Int =
            if (value is Bitmap) value.byteCount else 1
    }

    /** Whether a thumbnail is worth trying for this kind. */
    fun handles(kind: FileKind): Boolean =
        kind == FileKind.IMAGE || kind == FileKind.VIDEO || kind == FileKind.AUDIO

    /**
     * The thumbnail for [file], about [sizePx] on its longest side, or null when
     * there is none. Blocks while it decodes, so it is called off the main thread.
     */
    fun load(file: File, kind: FileKind, sizePx: Int): Bitmap? {
        if (!handles(kind) || !file.isFile) return null
        val key = "${file.path}|${file.lastModified()}|$sizePx"
        cache.get(key)?.let { return it as? Bitmap }
        val bitmap = runCatching {
            when (kind) {
                FileKind.IMAGE -> decodeImage(file, sizePx)
                FileKind.VIDEO -> decodeVideoFrame(file, sizePx)
                FileKind.AUDIO -> decodeAlbumArt(file)
                else -> null
            }
        }.getOrNull()
        cache.put(key, bitmap ?: MISS)
        return bitmap
    }

    // Decoded at a sample rate that lands near the wanted size, so a full-size
    // photo is never held in memory just to draw it at 40dp.
    private fun decodeImage(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight).takeIf { it > 0 } ?: return null
        var sample = 1
        while (longest / (sample * 2) >= sizePx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    private fun decodeVideoFrame(file: File, sizePx: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                ThumbnailUtils.createVideoThumbnail(file, Size(sizePx, sizePx), null)
            }.getOrNull()
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                retriever.getFrameAtTime(-1)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }

    private fun decodeAlbumArt(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/**
 * A file row's icon as a thumbnail when the file is a local picture, film or song
 * with one, and the kind tile otherwise -- while it loads, if it has none, or for
 * a remote file the caller does not hand a thumbnail for.
 */
@Composable
fun EntryThumb(
    file: File,
    kind: FileKind,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    cornerRadius: Dp = 12.dp,
) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val thumb by produceState<ImageBitmap?>(null, file.path, file.lastModified(), sizePx) {
        value = withContext(Dispatchers.IO) { Thumbnails.load(file, kind, sizePx)?.asImageBitmap() }
    }
    val bitmap = thumb
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius)),
        )
    } else {
        FileTile(
            kind = kind,
            colour = colourFor(kind),
            contentDescription = contentDescription,
            modifier = modifier,
            size = size,
            cornerRadius = cornerRadius,
        )
    }
}
