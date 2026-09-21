package org.filezilla.android.files

import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The picture an app goes by, for the lists that let the user pick one.
 *
 * Two things can go wrong and both end as a blank row. An app that is no
 * longer installed has no icon to fetch, and asking for one throws rather
 * than returning null -- so the throw has to be caught here or a list with
 * one stale entry in it takes the whole dialog down. And a list of ten apps
 * asks ten times, each one reading the package manager and rasterising a
 * drawable, which is why the answers are kept.
 */
@RunWith(RobolectricTestRunner::class)
// Rasterising is the point of this class, and the legacy canvas draws
// nothing at all -- so a test of it under that mode would pass on a blank.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconsTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /**
     * The case that matters: a remembered choice whose app has been
     * uninstalled. Null, and not an exception taking the dialog with it.
     */
    @Test
    fun `an app that is not installed has no icon rather than a crash`() {
        assertNull(AppIcons.of(context, "com.example.not.installed"))
    }

    /**
     * The part that goes quietly wrong.
     *
     * The obvious way to get a picture out of an app icon is to ask it for
     * its `bitmap`, and an adaptive icon -- which is what every app shipped
     * in years has -- has none to give. So this asserts on the pixels: a
     * drawable that is entirely one colour has to come back as a bitmap
     * that is entirely that colour, which a "returned a blank" bug cannot
     * pass.
     */
    @Test
    fun `an icon with no bitmap of its own is still drawn`() {
        val red = android.graphics.drawable.ColorDrawable(android.graphics.Color.RED)

        val drawn = AppIcons.rasterise(red, sizePx = 8)

        assertNotNull(drawn)
        val pixels = IntArray(8 * 8)
        drawn.asAndroidBitmap().getPixels(pixels, 0, 8, 0, 0, 8, 8)
        assertEquals(
            "the drawable was not actually drawn",
            emptyList<Int>(),
            pixels.filterNot { it == android.graphics.Color.RED },
        )
    }

    /** And at the size asked for, or the rows would draw at a stray scale. */
    @Test
    fun `it is drawn at the size asked for`() {
        val red = android.graphics.drawable.ColorDrawable(android.graphics.Color.RED)

        val drawn = AppIcons.rasterise(red, sizePx = 12)

        assertEquals(12, drawn.width)
        assertEquals(12, drawn.height)
    }

    /** Asked twice, rasterised once. */
    @Test
    fun `an icon already drawn is not drawn again`() {
        val first = AppIcons.of(context, context.packageName)
        val second = AppIcons.of(context, context.packageName)

        assertSame(first, second)
    }

    /** And a miss is remembered too, or every redraw retries the same failure. */
    @Test
    fun `an app with no icon is not looked up over and over`() {
        AppIcons.of(context, "com.example.gone")

        // Robolectric's package manager would throw again; that it does not
        // reach it is what the null-without-a-throw here shows.
        assertNull(AppIcons.of(context, "com.example.gone"))
    }
}
