package org.filezilla.android.ui

import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.Locale
import org.filezilla.android.R

/**
 * The app's own player for a video or a sound.
 *
 * A folder of them opens as a playlist so one runs on to the next, and each
 * file remembers where it was left so it reopens there rather than at the
 * start. Video takes the whole black screen with the system bars out of the
 * way; a sound shows the same controls with nothing to look at behind them.
 *
 * A film's own subtitles, if they sit beside it as separate files, are picked
 * up and offered. The top bar keeps clear of a camera notch, a button turns
 * the screen on its side, and a finger dragged across scrubs forward or back.
 *
 * The engine is ExoPlayer, the controls are its own PlayerView -- both marked
 * unstable by the library, hence the opt-in -- so this file is thin: it wires a
 * playlist in, keeps the place, and lays a few things over the top.
 */
// media3 marks these APIs unstable through androidx's opt-in, not Kotlin's, so
// the annotation is androidx.annotation.OptIn rather than kotlin's @OptIn.
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MediaViewerScreen(viewer: MainViewModel.MediaViewer, model: MainViewModel) {
    val context = LocalContext.current
    val exo = remember { ExoPlayer.Builder(context).build() }

    // Load the playlist and start where the opened file was left. Each entry
    // carries whatever subtitle files were found beside it. Keyed on the list
    // so reopening a different folder rebuilds it.
    LaunchedEffect(viewer.items, viewer.index) {
        val start = model.mediaPosition(viewer.items[viewer.index])
        exo.setMediaItems(viewer.items.map { mediaItemFor(it) }, viewer.index, start)
        exo.prepare()
        exo.playWhenReady = true
    }

    // Keep the place. A file the player moves on from, or plays to the end, is
    // put back to the start; one left partway keeps its position, unless it is
    // within a second of the end, which reads as finished.
    var index by remember { mutableIntStateOf(viewer.index) }
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                viewer.items.getOrNull(index)?.let { model.setMediaPosition(it, 0L) }
                index = exo.currentMediaItemIndex
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    viewer.items.getOrNull(exo.currentMediaItemIndex)?.let { model.setMediaPosition(it, 0L) }
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            val at = exo.currentMediaItemIndex
            val position = exo.currentPosition
            val duration = exo.duration
            val save = if (duration > 0 && position >= duration - 1_000) 0L else position
            viewer.items.getOrNull(at)?.let { model.setMediaPosition(it, save) }
            exo.removeListener(listener)
            exo.release()
        }
    }

    // While playing, the phone's bars go away so a video has the whole screen;
    // leaving the player restores them.
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }

    // The screen's own turning, controlled by the rotate button, and put back
    // to the phone's own preference on the way out. Cycled upright -> on its
    // side -> follow the sensor, so a locked view can be forced either way and
    // then handed back to the accelerometer.
    val activity = context as? android.app.Activity
    var orientation by rememberSaveable { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }
    LaunchedEffect(orientation) { activity?.requestedOrientation = orientation }
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // The top bar sits below a camera notch, not under it. The status bar's
    // inset drops to zero once the bar is hidden, so the largest seen is
    // latched and the cutout is taken into account directly.
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    val cutoutTop = WindowInsets.displayCutout.getTop(density)
    var reservedTopPx by rememberSaveable { mutableStateOf(0) }
    val reservedTop = maxOf(reservedTopPx, statusTop, cutoutTop)
    LaunchedEffect(reservedTop) { reservedTopPx = reservedTop }
    val reservedTopDp = with(density) { reservedTop.toDp() }

    BackHandler { model.closeMediaViewer() }

    // A finger dragged across the picture scrubs: the distance maps to time,
    // a full width being two minutes, and a read-out of where the release
    // would land shows while the drag is in hand.
    var widthPx by remember { mutableIntStateOf(0) }
    var dragBase by remember { mutableLongStateOf(0L) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    var seekTarget by remember { mutableLongStateOf(-1L) }

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exo
                        useController = true
                        controllerShowTimeoutMs = 3_000
                        setShowNextButton(viewer.items.size > 1)
                        setShowPreviousButton(viewer.items.size > 1)
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // The scrub layer. It consumes only horizontal drags, so a tap
            // still falls through to the player's own controls and the buttons
            // laid over the top still take their presses.
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { widthPx = it.width }
                    .pointerInput(exo) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragBase = exo.currentPosition
                                dragAccum = 0f
                                seekTarget = dragBase
                            },
                            onDragEnd = {
                                if (seekTarget >= 0) exo.seekTo(seekTarget)
                                seekTarget = -1L
                            },
                            onDragCancel = { seekTarget = -1L },
                        ) { change, dragAmount ->
                            dragAccum += dragAmount
                            val width = widthPx.takeIf { it > 0 } ?: return@detectHorizontalDragGestures
                            val duration = exo.duration.takeIf { it > 0 } ?: return@detectHorizontalDragGestures
                            val delta = (dragAccum / width * 120_000f).toLong()
                            seekTarget = (dragBase + delta).coerceIn(0L, duration)
                            change.consume()
                        }
                    },
            )
            // A way back, over the top-left, since the player's own controls
            // have no exit; and a turn button opposite it.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(top = reservedTopDp)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = model::closeMediaViewer) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = Color.White,
                    )
                }
                Text(
                    viewer.items.getOrNull(index)?.name.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                )
                IconButton(
                    onClick = {
                        orientation = when (orientation) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ->
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT ->
                                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    },
                ) {
                    Icon(
                        Icons.Filled.ScreenRotation,
                        contentDescription = stringResource(R.string.action_rotate),
                        tint = Color.White,
                    )
                }
            }
            // Where the scrub would land, shown only while a drag is in hand.
            if (seekTarget >= 0) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = 96.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Text(
                        text = clock(seekTarget) + " / " + clock(exo.duration.coerceAtLeast(0L)),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier
                            .background(
                                Color.Black.copy(alpha = 0.6f),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** A duration as h:mm:ss, or m:ss under an hour. */
private fun clock(ms: Long): String {
    val total = (ms.coerceAtLeast(0L)) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}

/**
 * A playable, with any subtitle files found beside it attached.
 *
 * A film often ships its subtitles as a separate file in the same folder,
 * named for the film with a language tag on the end -- "movie.mp4" beside
 * "movie.ko.srt" and "movie.en.srt". Those are gathered and offered as
 * selectable tracks, the first (or a Korean one) shown by default.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun mediaItemFor(file: File): MediaItem {
    val subs = sidecarSubtitles(file)
    if (subs.isEmpty()) return MediaItem.fromUri(Uri.fromFile(file))
    return MediaItem.Builder()
        .setUri(Uri.fromFile(file))
        .setSubtitleConfigurations(subs)
        .build()
}

// The subtitle formats media3 can read on its own. SAMI (.smi), still common
// in Korea, has no built-in parser, so it is left out rather than attached and
// shown blank.
private val SUBTITLE_MIME = mapOf(
    "srt" to MimeTypes.APPLICATION_SUBRIP,
    "vtt" to MimeTypes.TEXT_VTT,
    "webvtt" to MimeTypes.TEXT_VTT,
    "ass" to MimeTypes.TEXT_SSA,
    "ssa" to MimeTypes.TEXT_SSA,
    "ttml" to MimeTypes.APPLICATION_TTML,
    "dfxp" to MimeTypes.APPLICATION_TTML,
)

@androidx.annotation.OptIn(UnstableApi::class)
private fun sidecarSubtitles(video: File): List<MediaItem.SubtitleConfiguration> {
    val dir = video.parentFile ?: return emptyList()
    val base = video.nameWithoutExtension.lowercase()
    val candidates = dir.listFiles()?.filter { it.isFile } ?: return emptyList()

    val found = candidates.mapNotNull { file ->
        val ext = file.extension.lowercase()
        val mime = SUBTITLE_MIME[ext] ?: return@mapNotNull null
        val stem = file.nameWithoutExtension.lowercase()
        // The subtitle belongs to this film if its name is the film's, or the
        // film's followed by a tag ("movie", "movie.ko", "movie_en").
        if (stem != base && !stem.startsWith("$base.") &&
            !stem.startsWith("${base}_") && !stem.startsWith("$base-")
        ) {
            return@mapNotNull null
        }
        val tag = stem.removePrefix(base).trimStart('.', '_', '-', ' ')
        file to (mime to languageOf(tag))
    }

    // Show one by default: a Korean track if there is one, else the first.
    val defaultIdx = found.indexOfFirst { it.second.second == "ko" }.let {
        if (it >= 0) it else if (found.isNotEmpty()) 0 else -1
    }
    return found.mapIndexed { i, (file, meta) ->
        val (mime, language) = meta
        MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(mime)
            .setLanguage(language)
            .setSelectionFlags(if (i == defaultIdx) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
    }
}

/** A rough language from a filename tag, for the track picker's label. */
private fun languageOf(tag: String): String? = when {
    tag.isEmpty() -> null
    tag.startsWith("ko") || tag.startsWith("kr") || tag.contains("kor") || tag.contains("한") -> "ko"
    tag.startsWith("en") || tag.contains("eng") -> "en"
    tag.startsWith("ja") || tag.startsWith("jp") || tag.contains("jpn") -> "ja"
    tag.startsWith("zh") || tag.contains("chi") || tag.contains("chs") || tag.contains("cht") -> "zh"
    else -> tag.take(8)
}
