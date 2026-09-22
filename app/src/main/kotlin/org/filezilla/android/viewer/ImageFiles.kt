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
     * still cover [reqWidth]x[reqHeight].
     *
     * A target of zero -- a size not known yet -- means decode at full size,
     * which is what a caller that has not been laid out should get rather than
     * a picture shrunk to nothing.
     */
    fun sampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        if (reqWidth <= 0 || reqHeight <= 0) return 1
        var sample = 1
        var halfW = width / 2
        var halfH = height / 2
        while (halfW >= reqWidth && halfH >= reqHeight) {
            sample *= 2
            halfW /= 2
            halfH /= 2
        }
        return sample
    }
}
