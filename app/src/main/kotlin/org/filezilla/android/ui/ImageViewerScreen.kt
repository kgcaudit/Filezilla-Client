package org.filezilla.android.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.filezilla.android.R

/**
 * A comic reader over a set of images.
 *
 * The picture fills the screen with nothing on top of it -- a page of a comic
 * is the thing being read, and a filename and a page number laid over the art
 * are in the way. A tap in the middle brings the controls back and hides them
 * again; a tap on the side turns the page the way the book reads; a swipe does
 * the same. The place is kept as the pages turn, so a book reopens where it
 * was left.
 */
@Composable
fun ImageViewerScreen(viewer: MainViewModel.ImageViewer, model: MainViewModel) {
    // A comic gets one extra page past its last: the end card, which names the
    // next volume and goes on to it, or says the series is done.
    val pageCount = viewer.images.size + if (viewer.book) 1 else 0
    val pager = rememberPagerState(initialPage = viewer.index, pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    val rtl = model.readerRtl
    var chrome by rememberSaveable(viewer.comicKey) { mutableStateOf(false) }

    // While reading, the phone's own bars go away so the page has the whole
    // screen; bringing the menu up brings them back, and leaving the reader
    // restores them. A swipe from the edge still peeks at them meanwhile.
    // The bars carry light icons throughout, because the reader is black and
    // the phone's dark clock would be invisible on it.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val wasLightStatus = controller?.isAppearanceLightStatusBars ?: true
        val wasLightNav = controller?.isAppearanceLightNavigationBars ?: true
        controller?.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller?.isAppearanceLightStatusBars = wasLightStatus
            controller?.isAppearanceLightNavigationBars = wasLightNav
        }
    }
    LaunchedEffect(chrome, view) {
        val window = (view.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
        val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        if (chrome) controller.show(bars) else controller.hide(bars)
    }

    // The top strip the phone keeps for its status bar and camera, remembered
    // so the page sits below it even once the bar is hidden -- otherwise a
    // centre-punch camera would bite a hole out of the art. Latched at its
    // largest, since hiding the bar drops its own inset to zero.
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    val cutoutTop = WindowInsets.displayCutout.getTop(density)
    var reservedTopPx by rememberSaveable { mutableStateOf(0) }
    val reservedTop = maxOf(reservedTopPx, statusTop, cutoutTop)
    LaunchedEffect(reservedTop) { reservedTopPx = reservedTop }
    val reservedTopDp = with(density) { reservedTop.toDp() }

    // Kept so the book reopens here, and so the bar shows where it is.
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { model.setImageIndex(it) }
    }

    fun turn(forward: Boolean) {
        val to = (pager.currentPage + if (forward) 1 else -1).coerceIn(0, pageCount - 1)
        scope.launch { pager.animateScrollToPage(to) }
    }

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        // The picture fills the whole screen, under where the bars were; only
        // the menu, when it is up, keeps clear of them.
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pager,
                reverseLayout = rtl,
                // The next page (and the one before) are composed off-screen so
                // they decode ahead of time; a turn then lands on a page that
                // is already drawn instead of on a spinner.
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize().padding(top = reservedTopDp),
            ) { page ->
                if (page < viewer.images.size) {
                    ReaderPage(
                        ref = viewer.images[page],
                        model = model,
                        rtl = rtl,
                        onTurn = ::turn,
                        onToggleChrome = { chrome = !chrome },
                    )
                } else {
                    ReaderEndCard(
                        nextComic = viewer.nextComic,
                        onOpenNext = { viewer.nextComic?.let(model::openComicFile) },
                        onClose = model::closeImageViewer,
                    )
                }
            }

            AnimatedVisibility(visible = chrome, modifier = Modifier.align(Alignment.TopCenter)) {
                ReaderTopBar(
                    name = viewer.images.getOrNull(pager.currentPage)?.name.orEmpty(),
                    rtl = rtl,
                    onRtl = model::applyReaderRtl,
                    onClose = model::closeImageViewer,
                )
            }

            val onImage = pager.currentPage < viewer.images.size
            if (viewer.images.size > 1) {
                AnimatedVisibility(
                    visible = chrome && onImage,
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    ReaderBottomBar(
                        page = pager.currentPage.coerceAtMost(viewer.images.size - 1),
                        count = viewer.images.size,
                        rtl = rtl,
                        onSeek = { scope.launch { pager.scrollToPage(it) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    name: String,
    rtl: Boolean,
    onRtl: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close), tint = Color.White)
        }
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        Box {
            var open by remember { mutableStateOf(false) }
            IconButton(onClick = { open = true }) {
                Icon(Icons.Filled.Settings, stringResource(R.string.reader_settings), tint = Color.White)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reader_direction_rtl)) },
                    trailingIcon = { Switch(checked = rtl, onCheckedChange = { onRtl(it) }) },
                    onClick = { onRtl(!rtl) },
                )
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(page: Int, count: Int, rtl: Boolean, onSeek: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${page + 1} / $count",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
        // Dragged live for the number, and the page turned when let go, so a
        // long book is not decoded once for every value the finger crosses.
        var dragged by remember(page) { mutableFloatStateOf(page.toFloat()) }
        Slider(
            value = dragged,
            onValueChange = { dragged = it },
            onValueChangeFinished = { onSeek(dragged.toInt().coerceIn(0, count - 1)) },
            valueRange = 0f..(count - 1).toFloat(),
            // Turning pages leftward means the book runs the other way, so the
            // slider is mirrored to match: page one on the right, filling left
            // as it is read. The flip carries the touch with it, so a drag
            // still moves the thumb the way the finger goes.
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(scaleX = if (rtl) -1f else 1f),
        )
    }
}

/**
 * The page past the last: on to the next volume, or the end of the series.
 */
@Composable
private fun ReaderEndCard(
    nextComic: java.io.File?,
    onOpenNext: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (nextComic != null) {
            Text(
                stringResource(R.string.reader_next_volume),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.7f),
            )
            Text(
                nextComic.nameWithoutExtension,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Button(onClick = onOpenNext) {
                Text(stringResource(R.string.reader_continue))
            }
        } else {
            Text(
                stringResource(R.string.reader_series_end),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
        TextButton(onClick = onClose) {
            Text(stringResource(R.string.action_close), color = Color.White)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ReaderPage(
    ref: MainViewModel.ImageRef,
    model: MainViewModel,
    rtl: Boolean,
    onTurn: (forward: Boolean) -> Unit,
    onToggleChrome: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val reqWidth = constraints.maxWidth
        val reqHeight = constraints.maxHeight
        var bitmap by remember(ref) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(ref) { mutableStateOf(false) }

        LaunchedEffect(ref, reqWidth, reqHeight) {
            val decoded = model.loadImage(ref, reqWidth, reqHeight)
            if (decoded == null) failed = true else bitmap = decoded
        }

        var scale by remember(ref) { mutableStateOf(1f) }
        var offsetX by remember(ref) { mutableStateOf(0f) }
        var offsetY by remember(ref) { mutableStateOf(0f) }
        val transform = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 6f)
            if (scale > 1f) {
                offsetX += pan.x
                offsetY += pan.y
            } else {
                offsetX = 0f
                offsetY = 0f
            }
        }

        when {
            failed -> Text(
                stringResource(R.string.viewer_image_failed),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            bitmap == null -> CircularProgressIndicator(color = Color.White)
            else -> androidx.compose.foundation.Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = ref.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    )
                    .transformable(state = transform, canPan = { scale > 1f })
                    .pointerInput(ref, rtl) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f; offsetX = 0f; offsetY = 0f
                                } else {
                                    scale = 3f
                                }
                            },
                            onTap = { at ->
                                // Zoomed in, a tap only brings the controls
                                // back; at rest, the sides turn the page the
                                // way the book reads and the middle toggles.
                                val third = size.width / 3f
                                when {
                                    scale > 1f -> onToggleChrome()
                                    at.x < third -> onTurn(rtl)
                                    at.x > size.width - third -> onTurn(!rtl)
                                    else -> onToggleChrome()
                                }
                            },
                        )
                    },
            )
        }
    }
}
