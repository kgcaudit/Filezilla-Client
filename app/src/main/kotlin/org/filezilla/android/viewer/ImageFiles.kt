package org.filezilla.android.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.util.Size
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Turning an image file into a bitmap the screen can hold.
 *
 * A phone camera makes files far larger than any phone screen can show, and
 * decoding one at full size is how an image viewer runs the app out of
 * memory. So the size is read first, from the header alone, and the decode is
 * asked to halve the picture until it is no bigger than it needs to be for the
 * space it will fill -- which is the difference between a photo that opens and
 * one that crashes the app opening it.
 */
object ImageFiles {

    private val IMAGE_EXTENSIONS: Set<String> =
        "jpg jpeg png gif webp bmp heic heif".split(" ").toSet()

    /** Whether a name is one the image viewer shows. */
    fun looksImage(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension.isNotEmpty() && extension in IMAGE_EXTENSIONS
    }

    /**
     * Decodes [file], no larger than [reqWidth] by [reqHeight] asks for.
     *
     * Two passes: the first reads only the header for the real dimensions,
     * the second decodes for real at the largest power-of-two shrink that
     * still fills the target. Returns null for what is not an image after
     * all, rather than throwing, because a mis-named file is a thing that
     * happens and is not worth a crash.
     */
    fun decode(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
        }
        val bitmap = runCatching { BitmapFactory.decodeFile(file.path, options) }.getOrNull()
        return bitmap?.let { oriented(it, orientationOf(file)) }
    }

    /**
     * The same, straight from bytes already in hand.
     *
     * A comic's page comes out of its archive as bytes; decoding them here
     * rather than writing them to a file and reading them back saves a page a
     * round trip to disk each time it is turned to. Both passes read the one
     * in-memory array, so the header sniff costs nothing extra.
     */
    fun decode(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
        }
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull()
        return bitmap?.let { oriented(it, orientationOf(bytes)) }
    }

    /**
     * A picture as it was taken, from its EXIF orientation tag.
     *
     * A phone camera writes the picture the way the sensor read it and records
     * the turn to make it upright in a tag, rather than turning the pixels. A
     * plain decode ignores the tag, so a photo taken in portrait comes out on its
     * side. This turns (or flips) the decoded bitmap to match the tag; a picture
     * with no tag, or an upright one, is returned unchanged. The pre-turn bitmap
     * is freed, since it is this object's own and nothing else holds it.
     */
    fun oriented(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val turned = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull() ?: return bitmap
        if (turned != bitmap) bitmap.recycle()
        return turned
    }

    /** The EXIF orientation tag of [file], or normal when there is none. */
    fun orientationOf(file: File): Int = runCatching {
        ExifInterface(file.path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** The EXIF orientation tag read from image [bytes], or normal when there is none. */
    private fun orientationOf(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /**
     * The largest power of two by which [width]x[height] can be halved and
     * still cover [reqWidth]x[reqHeight] -- then shrunk further, whatever the
     * target, until the result is small enough to hold and to draw.
     *
     * The second part is the one that matters for safety. Covering the target
     * alone leaves an image of an extreme shape at nearly full size: a webtoon
     * strip a couple of thousand pixels wide but twenty thousand tall is barely
     * wider than the screen, so the cover rule never shrinks it, and it decodes
     * to a bitmap far past the hundred-megabyte ceiling a Canvas can draw and
     * the few thousand pixels a GPU texture can be -- which does not fail
     * quietly, it takes the app down. So a hard cap on the longest side and on
     * the total pixels applies on top, regardless of the target or its absence.
     */
    fun sampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var sample = 1
        if (reqWidth > 0 && reqHeight > 0) {
            var halfW = width / 2
            var halfH = height / 2
            while (halfW >= reqWidth && halfH >= reqHeight) {
                sample *= 2
                halfW /= 2
                halfH /= 2
            }
        }
        while (
            width / sample > MAX_DIMENSION ||
            height / sample > MAX_DIMENSION ||
            (width.toLong() / sample) * (height.toLong() / sample) > MAX_PIXELS
        ) {
            sample *= 2
        }
        return sample
    }

    /**
     * The real pixel size of the image in [bytes], from its header alone.
     *
     * A webtoon strip is planned before any of it is decoded -- how tall it is
     * decides how many bands to cut it into -- so its size is needed without
     * paying to decode the whole thing. Null if the bytes are not an image.
     */
    fun sizeOf(bytes: ByteArray): Size? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return Size(bounds.outWidth, bounds.outHeight)
    }

    /**
     * The image size from a [stream], reading only as far as the header.
     *
     * For measuring a long strip's pages: a header sniff reads a few hundred
     * bytes, so a whole archive of pages can be sized in one pass over it
     * rather than by extracting each entry in full just to learn its shape.
     */
    fun sizeOf(stream: java.io.InputStream): Size? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return Size(bounds.outWidth, bounds.outHeight)
    }

    /**
     * Decodes just the rows [top, bottom) of the image in [bytes], shrunk by
     * [sample], as one band of a webtoon strip.
     *
     * A strip is far too tall to hold whole, so only the slice on screen is
     * ever turned into a bitmap. The region decoder reads the one band out of
     * the source without touching the rest -- which is what lets a strip
     * twenty thousand pixels tall scroll on a phone. Null rather than a throw
     * for bytes that will not decode.
     */
    @Suppress("DEPRECATION")
    fun decodeRegion(bytes: ByteArray, top: Int, bottom: Int, sample: Int): Bitmap? {
        val decoder = runCatching {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        }.getOrNull() ?: return null
        return try {
            val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            val rect = Rect(0, top, decoder.width, bottom.coerceAtMost(decoder.height))
            runCatching { decoder.decodeRegion(rect, options) }.getOrNull()
        } finally {
            decoder.recycle()
        }
    }

    // The longest a decoded side may be, kept under the smallest GPU texture
    // limit worth supporting, and the most pixels one may hold, so three of
    // them prefetched still sit well within a phone's memory.
    private const val MAX_DIMENSION = 4096
    private const val MAX_PIXELS = 8_000_000L
}
