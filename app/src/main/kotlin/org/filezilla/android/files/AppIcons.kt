package org.filezilla.android.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * The picture an app goes by on the home screen.
 *
 * The "open with" list named apps and drew nothing, and that is not how
 * anybody picks one: the name of a player is something you read, its icon is
 * something you recognise. A list of six words asks the reader to translate
 * each one back into the app they know.
 *
 * Drawn into a bitmap rather than handed over as a Drawable, because an
 * adaptive icon is a pair of layers with a mask and has no single bitmap to
 * borrow -- asking one for `bitmap` gets null, which is how these lists end
 * up with icons for the old apps and blanks for the new ones.
 */
object AppIcons {

    /** The size icons are drawn at, in pixels. Comfortably above 40dp at xxhdpi. */
    private const val SIZE_PX = 144

    /**
     * Kept, because a list of ten apps asks ten times and each one reads the
     * package manager and rasterises a drawable. Bounded, and small: this is
     * a list of a handful of apps, not a gallery.
     */
    private val kept = object : LinkedHashMap<String, ImageBitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap?>) = size > 64
    }

    /**
     * [packageName]'s icon, or null when it has none this app can draw.
     *
     * Null rather than a stand-in, so the caller decides what an app with no
     * icon looks like -- and an app that has been uninstalled since the
     * choice was made is exactly that case.
     */
    @Synchronized
    fun of(context: Context, packageName: String): ImageBitmap? {
        if (kept.containsKey(packageName)) return kept[packageName]
        val drawn = runCatching {
            rasterise(context.packageManager.getApplicationIcon(packageName))
        }.getOrNull()
        kept[packageName] = drawn
        return drawn
    }

    /**
     * Draws [drawable] into a bitmap of its own.
     *
     * Its own function, and internal, because this is the part that goes
     * quietly wrong: the obvious way to get a picture out of an icon is to
     * ask it for its `bitmap`, and an adaptive icon -- which is what every
     * app shipped in the last several years has -- has none to give. The
     * result is a list with icons for the old apps and blanks for the rest,
     * which looks like a loading bug rather than a wrong API.
     */
    @androidx.annotation.VisibleForTesting
    internal fun rasterise(drawable: Drawable, sizePx: Int = SIZE_PX): ImageBitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(bitmap))
        return bitmap.asImageBitmap()
    }
}
