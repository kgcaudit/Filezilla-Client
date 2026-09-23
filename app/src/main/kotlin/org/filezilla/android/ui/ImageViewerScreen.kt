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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.filezilla.android.R
import org.filezilla.android.viewer.Spreads
import org.filezilla.android.viewer.Webtoon

/**
 * A comic reader over a set of images, in one of two shapes.
 *
 * A page comic turns one screen at a time, left or right; a webtoon is one long
 * strip scrolled top to bottom. Which shape a book gets is decided from the
 * proportions of its first image -- a strip is far taller than it is wide -- and
 * can be switched by hand from the settings. Everything above the picture is the
 * same either way: the bars go dark and hide while reading, a tap brings them
 * back, and the place is kept so a book reopens where it was left.
 */
@Composable
fun ImageViewerScreen(viewer: MainViewModel.ImageViewer, model: MainViewModel) {
    var chrome by rememberSaveable(viewer.comicKey) { mutableStateOf(false) }

    // The shape: read from the first page's proportions, unless the reader was
    // told by hand which to use. A tall-and-thin first image reads as a strip.
    var autoWebtoon by rememberSaveable(viewer.comicKey) { mutableStateOf(false) }
    LaunchedEffect(viewer.images) {
        viewer.images.firstOrNull()?.let { model.imageSize(it) }
            ?.let { autoWebtoon = Webtoon.isWebtoon(it.width, it.height) }
    }
    var webtoonOverride by rememberSaveable(viewer.comicKey) { mutableStateOf<Boolean?>(null) }
    val webtoon = webtoonOverride ?: autoWebtoon

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

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            if (webtoon) {
                WebtoonReader(
                    viewer = viewer,
                    model = model,
                    reservedTopDp = reservedTopDp,
                    chrome = chrome,
                    onToggleChrome = { chrome = !chrome },
                    onWebtoon = { webtoonOverride = it },
                    narrow = model.readerWebtoonNarrow,
                    onNarrow = model::applyReaderWebtoonNarrow,
                    widthPercent = model.readerWebtoonWidthPercent,
                    onWidthPercent = model::applyReaderWebtoonWidthPercent,
                )
            } else {
                PagedReader(
                    viewer = viewer,
                    model = model,
                    reservedTopDp = reservedTopDp,
                    chrome = chrome,
                    onToggleChrome = { chrome = !chrome },
                    onWebtoon = { webtoonOverride = it },
                )
            }
        }
    }
}

/**
 * The page-at-a-time reader: a pager of spreads, one page or two.
 *
 * A tap in the middle brings the controls back and hides them again; a tap on
 * the side turns the page the way the book reads; a swipe does the same.
 */
@Composable
private fun PagedReader(
    viewer: MainViewModel.ImageViewer,
    model: MainViewModel,
    reservedTopDp: Dp,
    chrome: Boolean,
    onToggleChrome: () -> Unit,
    onWebtoon: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val rtl = model.readerRtl

    // Two pages side by side, but only for a comic on a wide screen -- a phone
    // held sideways, or a tablet -- and only when the setting allows it. A
    // portrait phone, or a loose folder of pictures, stays one page.
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val twoPage = viewer.book && model.readerTwoPage && landscape
    val spreads = remember(viewer.images.size, twoPage) { Spreads.of(viewer.images.size, twoPage) }

    // A comic gets one extra spread past its last: the end card, which names
    // the next volume and goes on to it, or says the series is done.
    val spreadCount = spreads.size + if (viewer.book) 1 else 0
    // Keyed on the pairing so a change of it -- the setting, or a turn to
    // landscape -- reopens the pager on the spread holding the same page,
    // rather than at some stale index into a list that just changed length.
    val pager = androidx.compose.runtime.key(twoPage) {
        rememberPagerState(
            initialPage = Spreads.spreadOf(viewer.index, twoPage),
            pageCount = { spreadCount },
        )
    }

    // Kept by page, not by spread, so the place survives the pairing changing:
    // the first page of the spread on screen is what is remembered.
    LaunchedEffect(pager, twoPage) {
        snapshotFlow { pager.currentPage }.collect { model.setImageIndex(Spreads.firstPage(it, twoPage)) }
    }

    fun turn(forward: Boolean) {
        val to = (pager.currentPage + if (forward) 1 else -1).coerceIn(0, spreadCount - 1)
        scope.launch { pager.animateScrollToPage(to) }
    }

    // The picture fills the whole screen, under where the bars were; only the
    // menu, when it is up, keeps clear of them.
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            reverseLayout = rtl,
            // The next page (and the one before) are composed off-screen so
            // they decode ahead of time; a turn then lands on a page that is
            // already drawn instead of on a spinner.
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize().padding(top = reservedTopDp),
        ) { spread ->
            if (spread < spreads.size) {
                val pages = spreads[spread]
                // A lone page sits on its book side rather than centred: the
                // cover on the right for a leftward-read book (the left for
                // manga), and a lone last page on the opposite side, so a
                // spread of one still reads as half of an open book.
                val align = when {
                    !twoPage || pages.size == 2 -> SpreadAlign.FILL
                    spread == 0 -> if (rtl) SpreadAlign.LEFT else SpreadAlign.RIGHT
                    else -> if (rtl) SpreadAlign.RIGHT else SpreadAlign.LEFT
                }
                ReaderSpread(
                    refs = pages.map { viewer.images[it] },
                    align = align,
                    model = model,
                    rtl = rtl,
                    onTurn = ::turn,
                    onToggleChrome = onToggleChrome,
                )
            } else {
                ReaderEndCard(
                    nextComic = viewer.nextComic,
                    onOpenNext = { viewer.nextComic?.let(model::openComicFile) },
                    onClose = model::closeImageViewer,
                )
            }
        }

        val onImage = pager.currentPage < spreads.size
        val shownPages = spreads.getOrNull(pager.currentPage).orEmpty()

        AnimatedVisibility(visible = chrome, modifier = Modifier.align(Alignment.TopCenter)) {
            ReaderTopBar(
                name = shownPages.firstOrNull()?.let { viewer.images[it].name }.orEmpty(),
                webtoon = false,
                rtl = rtl,
                twoPage = model.readerTwoPage,
                narrow = false,
                widthPercent = 100,
                onWebtoon = onWebtoon,
                onRtl = model::applyReaderRtl,
                onTwoPage = model::applyReaderTwoPage,
                onNarrow = {},
                onWidthPercent = {},
                onClose = model::closeImageViewer,
            )
        }

        if (viewer.images.size > 1) {
            AnimatedVisibility(
                visible = chrome && onImage,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ReaderBottomBar(
                    firstPage = shownPages.firstOrNull() ?: 0,
                    lastPage = shownPages.lastOrNull() ?: 0,
                    count = viewer.images.size,
                    rtl = rtl,
                    onSeek = { page -> scope.launch { pager.scrollToPage(Spreads.spreadOf(page, twoPage)) } },
                )
            }
        }
    }
}

/**
 * The strip reader: every page cut into bands and stacked in one long scroll.
 *
 * Only the bands on screen are decoded, so a strip twenty thousand pixels tall
 * scrolls without ever holding more than a screenful in memory. Each page's
 * size is read as the scroll reaches it, in order, so a long series does not
 * pay for all of it up front. A tap anywhere brings the controls back.
 */
@Composable
private fun WebtoonReader(
    viewer: MainViewModel.ImageViewer,
    model: MainViewModel,
    reservedTopDp: Dp,
    chrome: Boolean,
    onToggleChrome: () -> Unit,
    onWebtoon: (Boolean) -> Unit,
    narrow: Boolean,
    onNarrow: (Boolean) -> Unit,
    widthPercent: Int,
    onWidthPercent: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val images = viewer.images

    // Every page's pixel size, measured once in a single pass over the source
    // so a long strip is not extracted page by page just to be laid out. Null
    // until that pass finishes; a zero stands for a page that would not read.
    var sizes by remember(images) { mutableStateOf<List<android.util.Size>?>(null) }
    LaunchedEffect(images) {
        sizes = model.imageSizes(images).map { it ?: android.util.Size(0, 0) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The strip runs the whole screen width, or a narrow centred column of
        // it -- narrower panels put more of the strip on screen at once, and
        // shrink the lettering baked into the art, which reads more like a
        // webtoon than one screen-filling panel at a time. The width is set by
        // hand, since how small the lettering is differs from strip to strip.
        val screenWidth = constraints.maxWidth
        val columnWidth =
            if (narrow) (screenWidth * widthPercent / 100).coerceAtLeast(1) else screenWidth
        val columnWidthDp = with(LocalDensity.current) { columnWidth.toDp() }

        val known = sizes
        val allKnown = known != null
        val bands = remember(known, columnWidth) {
            Webtoon.plan(known.orEmpty().map { it.width to it.height }, columnWidth)
        }

        val listState = rememberLazyListState()

        // Reopen where the book was left. Waits until the bands reach that page,
        // since they are laid as sizes load, and runs once.
        var restored by rememberSaveable(viewer.comicKey) { mutableStateOf(viewer.index == 0) }
        LaunchedEffect(bands.size) {
            if (!restored) {
                val target = bands.indexOfFirst { it.page >= viewer.index }
                if (target >= 0) {
                    listState.scrollToItem(target)
                    restored = true
                }
            }
        }
        // The page at the top of the screen is the one whose place is kept.
        LaunchedEffect(listState, bands) {
            snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
                if (restored) bands.getOrNull(index)?.let { model.setImageIndex(it.page) }
            }
        }

        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = reservedTopDp)
                // A tap toggles the controls; drags still scroll, since tap
                // detection lets anything that moves pass through to the list.
                .pointerInput(Unit) { detectTapGestures(onTap = { onToggleChrome() }) },
        ) {
            items(bands.size, key = { "${bands[it].page}:${bands[it].srcTop}" }) { i ->
                val band = bands[i]
                BandImage(
                    band = band,
                    ref = images[band.page],
                    columnWidthPx = columnWidth,
                    columnWidthDp = columnWidthDp,
                    model = model,
                )
            }
            when {
                !allKnown -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
                viewer.book -> item(key = "end") {
                    ReaderEndCard(
                        nextComic = viewer.nextComic,
                        onOpenNext = { viewer.nextComic?.let(model::openComicFile) },
                        onClose = model::closeImageViewer,
                    )
                }
            }
        }

        val currentPage = bands.getOrNull(listState.firstVisibleItemIndex)?.page ?: 0

        AnimatedVisibility(visible = chrome, modifier = Modifier.align(Alignment.TopCenter)) {
            ReaderTopBar(
                name = images.getOrNull(currentPage)?.name.orEmpty(),
                webtoon = true,
                rtl = false,
                twoPage = model.readerTwoPage,
                narrow = narrow,
                widthPercent = widthPercent,
                onWebtoon = onWebtoon,
                onRtl = model::applyReaderRtl,
                onTwoPage = model::applyReaderTwoPage,
                onNarrow = onNarrow,
                onWidthPercent = onWidthPercent,
                onClose = model::closeImageViewer,
            )
        }

        if (images.size > 1) {
            AnimatedVisibility(visible = chrome, modifier = Modifier.align(Alignment.BottomCenter)) {
                ReaderBottomBar(
                    firstPage = currentPage,
                    lastPage = currentPage,
                    count = images.size,
                    rtl = false,
                    onSeek = { page ->
                        scope.launch {
                            val target = bands.indexOfFirst { it.page >= page }
                            if (target >= 0) listState.scrollToItem(target)
                        }
                    },
                )
            }
        }
    }
}

/** One band of a strip: sized to its slice of the page, decoded when reached. */
@Composable
private fun BandImage(
    band: Webtoon.Band,
    ref: MainViewModel.ImageRef,
    columnWidthPx: Int,
    columnWidthDp: Dp,
    model: MainViewModel,
) {
    // The band's height on screen is its source rows scaled to the width it
    // fills, so the box stands at the right height before the pixels arrive and
    // the scroll does not jump as bands decode.
    val heightDp = with(LocalDensity.current) {
        if (band.imageWidth <= 0) 0.dp
        else ((band.srcBottom - band.srcTop).toFloat() * columnWidthPx / band.imageWidth).toDp()
    }
    var bitmap by remember(band) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(band) {
        bitmap = model.loadBand(ref, band.srcTop, band.srcBottom, band.sample)
    }
    Box(Modifier.width(columnWidthDp).height(heightDp), contentAlignment = Alignment.Center) {
        bitmap?.let {
            androidx.compose.foundation.Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                // The box already holds the band's own shape, so the bitmap
                // fills it exactly rather than being fitted inside it.
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ReaderTopBar(
    name: String,
    webtoon: Boolean,
    rtl: Boolean,
    twoPage: Boolean,
    narrow: Boolean,
    widthPercent: Int,
    onWebtoon: (Boolean) -> Unit,
    onRtl: (Boolean) -> Unit,
    onTwoPage: (Boolean) -> Unit,
    onNarrow: (Boolean) -> Unit,
    onWidthPercent: (Int) -> Unit,
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
                    text = { Text(stringResource(R.string.reader_webtoon)) },
                    trailingIcon = { Switch(checked = webtoon, onCheckedChange = { onWebtoon(it) }) },
                    onClick = { onWebtoon(!webtoon) },
                )
                // The strip reader reads down a column, so its one setting is
                // how wide that column is; the paging direction and two-up
                // settings belong to the page reader and only show there.
                if (webtoon) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_webtoon_narrow)) },
                        trailingIcon = { Switch(checked = narrow, onCheckedChange = { onNarrow(it) }) },
                        onClick = { onNarrow(!narrow) },
                    )
                    // A slider for the exact width, so a strip with small
                    // lettering can be widened and one with large pulled in.
                    // Shown only once the narrow column is on, since at full
                    // width there is nothing to slide.
                    if (narrow) {
                        // Dragged live for the number and the preview, but only
                        // written down when let go -- otherwise every pixel of
                        // the drag rewrites the setting and replans the strip.
                        var dragged by remember(widthPercent) { mutableFloatStateOf(widthPercent.toFloat()) }
                        Column(Modifier.width(240.dp).padding(horizontal = 16.dp)) {
                            Text(
                                stringResource(R.string.reader_webtoon_width, dragged.toInt()),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Slider(
                                value = dragged,
                                onValueChange = { dragged = it },
                                onValueChangeFinished = { onWidthPercent(dragged.toInt()) },
                                valueRange = 40f..100f,
                            )
                        }
                    }
                }
                if (!webtoon) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_direction_rtl)) },
                        trailingIcon = { Switch(checked = rtl, onCheckedChange = { onRtl(it) }) },
                        onClick = { onRtl(!rtl) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_two_page)) },
                        trailingIcon = { Switch(checked = twoPage, onCheckedChange = { onTwoPage(it) }) },
                        onClick = { onTwoPage(!twoPage) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(firstPage: Int, lastPage: Int, count: Int, rtl: Boolean, onSeek: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            // A spread shows a range, "12-13 / 200"; a single page one number.
            if (lastPage > firstPage) "${firstPage + 1}-${lastPage + 1} / $count" else "${firstPage + 1} / $count",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
        // Dragged live for the number, and the page turned when let go, so a
        // long book is not decoded once for every value the finger crosses.
        var dragged by remember(firstPage) { mutableFloatStateOf(firstPage.toFloat()) }
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

/**
 * One spread: a single page, or two side by side. The pair reads in the
 * book's direction -- for a leftward book the earlier page is on the right --
 * and zooms, pans and takes taps as one, so a two-page spread behaves like the
 * one open page it is meant to look like.
 */
/** How a spread's page(s) sit across the screen. */
private enum class SpreadAlign { FILL, LEFT, RIGHT }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ReaderSpread(
    refs: List<MainViewModel.ImageRef>,
    align: SpreadAlign,
    model: MainViewModel,
    rtl: Boolean,
    onTurn: (forward: Boolean) -> Unit,
    onToggleChrome: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // A page decodes to the width it will fill: a full page across, or a
        // half when it shares the screen or sits on one side of it.
        val reqWidth = when {
            align != SpreadAlign.FILL -> constraints.maxWidth / 2
            refs.isEmpty() -> constraints.maxWidth
            else -> constraints.maxWidth / refs.size
        }
        val reqHeight = constraints.maxHeight

        var scale by remember(refs) { mutableStateOf(1f) }
        var offsetX by remember(refs) { mutableStateOf(0f) }
        var offsetY by remember(refs) { mutableStateOf(0f) }
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

        Row(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
                .transformable(state = transform, canPan = { scale > 1f })
                .pointerInput(refs, rtl) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f; offsetX = 0f; offsetY = 0f
                            } else {
                                scale = 3f
                            }
                        },
                        onTap = { at ->
                            // Zoomed in, a tap only brings the controls back; at
                            // rest, the sides turn the page the way the book
                            // reads and the middle toggles.
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val cell = Modifier.weight(1f).fillMaxHeight()
            when (align) {
                // A leftward book reads its right page first, so a pair is laid
                // the other way round within the spread.
                SpreadAlign.FILL -> {
                    val ordered = if (rtl) refs.reversed() else refs
                    for (ref in ordered) PageImage(ref, model, reqWidth, reqHeight, cell)
                }
                // A lone page on one half, the other half left black.
                SpreadAlign.LEFT -> {
                    PageImage(refs.first(), model, reqWidth, reqHeight, cell)
                    Spacer(Modifier.weight(1f))
                }
                SpreadAlign.RIGHT -> {
                    Spacer(Modifier.weight(1f))
                    PageImage(refs.first(), model, reqWidth, reqHeight, cell)
                }
            }
        }
    }
}

@Composable
private fun PageImage(
    ref: MainViewModel.ImageRef,
    model: MainViewModel,
    reqWidth: Int,
    reqHeight: Int,
    modifier: Modifier,
) {
    var bitmap by remember(ref) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(ref) { mutableStateOf(false) }
    LaunchedEffect(ref, reqWidth, reqHeight) {
        val decoded = model.loadImage(ref, reqWidth, reqHeight)
        if (decoded == null) failed = true else bitmap = decoded
    }
    Box(modifier, contentAlignment = Alignment.Center) {
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
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
