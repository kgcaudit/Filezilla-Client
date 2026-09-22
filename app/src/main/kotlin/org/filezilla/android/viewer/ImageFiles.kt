package org.filezilla.android.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        return runCatching { BitmapFactory.decodeFile(file.path, options) }.getOrNull()
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
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
    }

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

    // The longest a decoded side may be, kept under the smallest GPU texture
    // limit worth supporting, and the most pixels one may hold, so three of
    // them prefetched still sit well within a phone's memory.
    private const val MAX_DIMENSION = 4096
    private const val MAX_PIXELS = 8_000_000L
}
