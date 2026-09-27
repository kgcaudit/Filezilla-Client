package org.filezilla.android.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.filezilla.android.R
import java.io.File

/** How far a single page may be pinched in. */
private const val MAX_ZOOM = 4f

/**
 * Reads a PDF page by page with Android's own [PdfRenderer].
 *
 * One page at a time, turned by swiping sideways, with the current page
 * pinch-zoomable. It renders a page to a bitmap only while that page is on or
 * beside the screen, so a long document costs the memory of a few pages, not
 * all of them; the current page is re-rendered at higher resolution when
 * zoomed in, so the text stays sharp rather than turning to a blur of a
 * screen-sized bitmap scaled up.
 *
 * The file is always one on disk -- a phone file, or a server file already
 * fetched to the cache -- because PdfRenderer reads a file descriptor.
 */
@Composable
fun PdfViewerScreen(viewer: MainViewModel.PdfViewer, model: MainViewModel) {
    val doc = remember(viewer.file) { PdfDoc(viewer.file) }
    // null while opening, -1 when the file could not be read, else the count.
    var pageCount by remember(doc) { mutableStateOf<Int?>(null) }
    DisposableEffect(doc) { onDispose { doc.close() } }
    LaunchedEffect(doc) {
        val opened = withContext(Dispatchers.IO) { doc.open() }
        pageCount = if (opened) doc.pageCount else -1
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.systemBarsPadding()) {
            val count = pageCount
            val pagerState = rememberPagerState { (count ?: 0).coerceAtLeast(0) }
            // The zoom of the page in front, so the pager stops turning pages
            // while one is being panned around.
            var frontZoom by remember { mutableFloatStateOf(1f) }
            LaunchedEffect(pagerState.currentPage) { frontZoom = 1f }

            ViewerBar(name = viewer.name, onClose = model::closePdfViewer) {
                if (count != null && count > 0) {
                    Text(
                        stringResource(R.string.pdf_page, pagerState.currentPage + 1, count),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            when {
                count == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                count <= 0 -> ViewerMessage(stringResource(R.string.pdf_open_failed))
                else -> HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = frontZoom <= 1.01f,
                    modifier = Modifier.fillMaxSize(),
                ) { index ->
                    PdfPage(
                        doc = doc,
                        index = index,
                        isCurrent = index == pagerState.currentPage,
                        onZoom = { frontZoom = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDoc, index: Int, isCurrent: Boolean, onZoom: (Float) -> Unit) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }
    var bitmap by remember(index) { mutableStateOf<ImageBitmap?>(null) }

    // A page that is no longer in front goes back to fitting the screen, so
    // swiping away from a zoomed page and back does not return to it half
    // panned off the edge.
    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    LaunchedEffect(scale, isCurrent) { if (isCurrent) onZoom(scale) }

    // Rendered at twice the fit width once zoomed past a little, so pinching
    // in reveals real detail rather than magnifying screen-width pixels. Only
    // the page in front pays for that; the rest stay at fit width.
    val renderScale = if (isCurrent && scale > 1.3f) 2 else 1
    LaunchedEffect(boxSize.width, renderScale) {
        val width = boxSize.width
        if (width <= 0) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) { doc.render(index, width * renderScale)?.asImageBitmap() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(isCurrent) {
                if (!isCurrent) return@pointerInput
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                    scale = next
                    offset = if (next <= 1f) {
                        Offset.Zero
                    } else {
                        // Kept within the grown page rather than letting it be
                        // dragged off into empty space.
                        val maxX = (next - 1f) * boxSize.width / 2f
                        val maxY = (next - 1f) * boxSize.height / 2f
                        Offset(
                            (offset.x + pan.x).coerceIn(-maxX, maxX),
                            (offset.y + pan.y).coerceIn(-maxY, maxY),
                        )
                    }
                }
            }
            .pointerInput(isCurrent) {
                detectTapGestures(
                    onDoubleTap = {
                        if (!isCurrent) return@detectTapGestures
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2f
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp == null) {
            CircularProgressIndicator()
        } else {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

/**
 * Owns one open PDF and hands out page bitmaps.
 *
 * [PdfRenderer] opens one page at a time and is not safe to touch from two
 * threads at once, so every call is serialised on one lock. Closing sets a
 * flag under that same lock, so a render that races the reader being shut
 * finds the door closed and returns nothing rather than throwing.
 */
private class PdfDoc(private val file: File) {
    private val lock = Any()
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var closed = false

    var pageCount = 0
        private set

    fun open(): Boolean = synchronized(lock) {
        if (closed) return false
        if (renderer != null) return true
        return try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val opened = PdfRenderer(fd)
            descriptor = fd
            renderer = opened
            pageCount = opened.pageCount
            true
        } catch (_: Exception) {
            false
        }
    }

    fun render(index: Int, widthPx: Int): Bitmap? = synchronized(lock) {
        val active = renderer ?: return null
        if (closed || index < 0 || index >= active.pageCount) return null
        val width = widthPx.coerceAtLeast(1)
        return try {
            val page = active.openPage(index)
            try {
                val height = (width.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // A PDF page can be transparent; on a dark theme that would show
                // the surface through the text, so it is painted onto white.
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                page.close()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
    }
}
