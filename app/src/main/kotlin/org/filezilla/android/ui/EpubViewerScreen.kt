package org.filezilla.android.ui

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.filezilla.android.R
import org.filezilla.android.archive.Archive
import org.filezilla.android.archive.ArchiveEntry
import org.filezilla.android.archive.Archives
import org.filezilla.android.viewer.EpubBook
import org.filezilla.android.viewer.EpubReader
import java.io.ByteArrayInputStream
import java.io.File

/** The made-up host every chapter and resource is served under, to the WebView. */
private const val HOST = "epub.local"
private const val BASE = "https://$HOST/"

/**
 * Reads an EPUB chapter by chapter with a WebView.
 *
 * An EPUB is XHTML and CSS in a zip, so the thing that already renders XHTML
 * -- a WebView -- does the rendering, and this feeds it the book. Each
 * chapter is loaded under a made-up host, and every request to that host is
 * answered from the zip: the chapter, and the images, styles and fonts it
 * pulls in behind it. Scripts are turned off, both because a reader does not
 * need them and because the book's pages are not ours to trust.
 *
 * Chapters are turned with the buttons along the foot; the book's own table
 * of contents, when it has one, opens from the bar. The reader reopens on the
 * chapter it was last left on.
 */
@Composable
fun EpubViewerScreen(viewer: MainViewModel.EpubViewer, model: MainViewModel) {
    val doc = remember(viewer.file) { EpubDoc(viewer.file) }
    // null while opening, true when read, false when it could not be read.
    var opened by remember(doc) { mutableStateOf<Boolean?>(null) }
    DisposableEffect(doc) { onDispose { doc.close() } }
    LaunchedEffect(doc) { opened = withContext(Dispatchers.IO) { doc.open() } }

    val book = doc.book
    val spine = book?.spine.orEmpty()

    var index by remember(viewer.file) { mutableStateOf(0) }
    var placed by remember(viewer.file) { mutableStateOf(false) }
    // Land on the last-read chapter once the spine is known, then follow the
    // reader and remember where it goes.
    LaunchedEffect(opened) {
        if (opened == true && !placed) {
            index = model.epubStartChapter(viewer.file).coerceIn(0, (spine.size - 1).coerceAtLeast(0))
            placed = true
        }
    }
    LaunchedEffect(index, placed) { if (placed) model.rememberEpubChapter(viewer.file, index) }

    var tocOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
      Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.systemBarsPadding()) {
            ViewerBar(name = book?.title?.ifBlank { null } ?: viewer.name, onClose = model::closeEpubViewer) {
                if (opened == true && book != null && book.toc.isNotEmpty()) {
                    IconButton(onClick = { tocOpen = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.epub_toc))
                    }
                }
            }

            when (opened) {
                null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                false -> ViewerMessage(stringResource(R.string.epub_open_failed))
                else -> {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        EpubWebView(doc = doc, chapterPath = spine.getOrNull(index), modifier = Modifier.fillMaxSize())
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { if (index > 0) index-- }, enabled = index > 0, shape = MaterialTheme.shapes.small) {
                            Text(stringResource(R.string.epub_prev))
                        }
                        Text(
                            stringResource(R.string.epub_chapter, index + 1, spine.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { if (index < spine.size - 1) index++ }, enabled = index < spine.size - 1, shape = MaterialTheme.shapes.small) {
                            Text(stringResource(R.string.epub_next))
                        }
                    }
                }
            }
        }
    }

    if (tocOpen && book != null) {
        BackHandler { tocOpen = false }
        EpubContents(
            toc = book.toc,
            onPick = { path ->
                spine.indexOf(path).takeIf { it >= 0 }?.let { index = it }
                tocOpen = false
            },
            onClose = { tocOpen = false },
        )
      }
    }
}

/** The book's contents, over the reader, a tap moving to the chapter picked. */
@Composable
private fun EpubContents(toc: List<EpubBook.TocEntry>, onPick: (String) -> Unit, onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.systemBarsPadding()) {
            ViewerBar(name = stringResource(R.string.epub_toc), onClose = onClose) {}
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(toc) { entry ->
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(entry.path) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EpubWebView(doc: EpubDoc, chapterPath: String?, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // Pinch to size the text, without the old on-screen zoom buttons.
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = serve(doc, request.url)
                }
            }
        },
        update = { web ->
            if (chapterPath != null && web.tag != chapterPath) {
                web.tag = chapterPath
                web.loadUrl(BASE + chapterPath.split('/').joinToString("/") { Uri.encode(it) })
            }
        },
        onRelease = { it.destroy() },
    )
}

/** Answers a WebView request from the open book, or null when it is not ours. */
private fun serve(doc: EpubDoc, uri: Uri): WebResourceResponse? {
    if (uri.host != HOST) return null
    val path = uri.path?.removePrefix("/").orEmpty()
    val bytes = doc.bytes(path) ?: return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    val mime = mimeOf(path)
    val encoding = if (mime.startsWith("text/")) "utf-8" else null
    return WebResourceResponse(mime, encoding, ByteArrayInputStream(bytes))
}

private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "xhtml", "html", "htm" -> "text/html"
    "css" -> "text/css"
    "js" -> "text/javascript"
    "xml", "ncx", "opf" -> "text/xml"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    else -> "application/octet-stream"
}

/**
 * Holds one open EPUB and hands out its entries' bytes.
 *
 * The zip is opened once and read from under one lock, since a WebView asks
 * for several resources at once and the archive is read one entry at a time.
 * Each answer is read whole into memory -- a chapter or an image, never the
 * book -- so the archive is not left open behind a stream the WebView drip-
 * feeds. Closing shuts the door under the same lock.
 */
private class EpubDoc(private val file: File) {
    private val lock = Any()
    private var archive: Archive? = null
    private var byPath: Map<String, ArchiveEntry> = emptyMap()
    private var closed = false

    var book: EpubBook? = null
        private set

    fun open(): Boolean = synchronized(lock) {
        if (closed) return false
        if (archive != null) return book != null
        return try {
            val opened = Archives.open(file)
            archive = opened
            byPath = opened.entries.associateBy { it.path }
            book = EpubReader.read(opened)
            book != null
        } catch (_: Exception) {
            false
        }
    }

    fun bytes(path: String): ByteArray? = synchronized(lock) {
        if (closed) return null
        val entry = byPath[path] ?: return null
        val open = archive ?: return null
        runCatching { open.open(entry).use { it.readBytes() } }.getOrNull()
    }

    fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        runCatching { archive?.close() }
        archive = null
    }
}
