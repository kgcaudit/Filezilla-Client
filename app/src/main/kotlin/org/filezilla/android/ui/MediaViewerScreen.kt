package org.filezilla.android.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.Locale
import org.filezilla.android.R
import org.filezilla.android.data.AppPreferences
import org.filezilla.android.playback.PlaybackService

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

    // Playback lives in a service so it carries on once the app is in the
    // background; this connects to it from the front, and is null until it has.
    val player = rememberMediaController(context)

    // Ask once for the notification permission the background player needs to
    // show its controls. Playback works without it, only without a notification.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Leaving on purpose -- the back arrow or the system back -- stops the sound
    // and clears the notification. Leaving the app (home, screen off) does
    // neither, so that keeps playing in the background.
    val close: () -> Unit = {
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        model.closeMediaViewer()
    }

    // While the player is up, the phone's bars go away so a video has the whole
    // screen; leaving restores them.
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }

    BackHandler(onBack = close)

    if (player == null) {
        // Connecting: a black hold with a way back, so a slow connect is never
        // a dead screen.
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                IconButton(onClick = close, modifier = Modifier.statusBarsPadding()) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = Color.White,
                    )
                }
            }
        }
        return
    }

    MediaPlayer(player = player, viewer = viewer, model = model, onClose = close)
}

/**
 * Connects to the playback service and hands back its controller, or null while
 * the connection is still being made. Letting go of the controller on the way
 * out releases the front end without stopping the service's player, so the
 * sound carries on in the background.
 */
@Composable
private fun rememberMediaController(context: Context): MediaController? {
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            { runCatching { future.get() }.getOrNull()?.let { controller = it } },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            MediaController.releaseFuture(future)
            controller = null
        }
    }
    return controller
}

/**
 * The player's face: the picture and controls, the top bar, the seek gesture
 * and the subtitle settings, over a service controller that outlives it.
 */
// The seek gesture is a touch listener on the player view rather than a Compose
// overlay: an overlay on top of the view swallows the taps the player's own
// controls need, so the menu stopped coming up. The listener never consumes a
// tap -- it watches for a sideways drag and leaves everything else to the view.
@SuppressLint("ClickableViewAccessibility")
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MediaPlayer(
    player: MediaController,
    viewer: MainViewModel.MediaViewer,
    model: MainViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current

    // Which file is showing, and a counter bumped when the tracks change so the
    // subtitle sheet's list rebuilds.
    var index by remember { mutableIntStateOf(viewer.index) }
    var tracksVersion by remember { mutableIntStateOf(0) }

    // Keep the place. A file the player moves on from, or plays to the end, is
    // put back to the start; one left partway keeps its position, unless it is
    // within a second of the end, which reads as finished. The player belongs
    // to the service and is only let go of here, not released, so the sound can
    // carry on in the background.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                viewer.items.getOrNull(index)?.let { model.setMediaPosition(it, 0L) }
                index = player.currentMediaItemIndex
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    viewer.items.getOrNull(player.currentMediaItemIndex)
                        ?.let { model.setMediaPosition(it, 0L) }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
            }
        }
        player.addListener(listener)
        onDispose {
            if (player.mediaItemCount > 0) {
                val at = player.currentMediaItemIndex
                val position = player.currentPosition
                val duration = player.duration
                val save = if (duration > 0 && position >= duration - 1_000) 0L else position
                viewer.items.getOrNull(at)?.let { model.setMediaPosition(it, save) }
            }
            player.removeListener(listener)
        }
    }

    // Load the playlist and start where the opened file was left -- unless the
    // service is already on this very playlist, as after a rotation, when
    // resetting it would jerk playback back to the start. Each entry carries
    // whatever subtitle files were found beside it.
    LaunchedEffect(player, viewer.items, viewer.index) {
        val wantUris = viewer.items.map { Uri.fromFile(it) }
        val haveUris = (0 until player.mediaItemCount).map {
            player.getMediaItemAt(it).localConfiguration?.uri
        }
        if (haveUris != wantUris) {
            val start = model.mediaPosition(viewer.items[viewer.index])
            player.setMediaItems(viewer.items.map { mediaItemFor(it) }, viewer.index, start)
            player.prepare()
            player.playWhenReady = true
        } else {
            index = player.currentMediaItemIndex
        }
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

    // A finger dragged across the picture scrubs: the distance maps to time, a
    // full width being two minutes, and a read-out of where the release would
    // land shows while the drag is in hand. The drag is watched on the player
    // view itself (see the class comment) rather than an overlay, so it is fed
    // as a preview here and committed on release.
    var seekTarget by remember { mutableLongStateOf(-1L) }
    val onSeekPreview: (Long) -> Unit = { seekTarget = it }
    val onSeekCommit: () -> Unit = {
        val target = seekTarget
        if (target >= 0) player.seekTo(target)
        seekTarget = -1L
    }

    // The top bar -- filename, rotate, and subtitle buttons -- rides with the
    // player's own controls: it shows when they show and hides when they hide,
    // so a video plays under a clear screen and the chrome is one tap away.
    var controlsVisible by remember { mutableStateOf(true) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var showSubtitleSheet by remember { mutableStateOf(false) }

    // Subtitle look, kept for the whole app. Applied to the player's subtitle
    // view whenever it or the settings change, and written back so the next
    // video opens the same way.
    var subScale by rememberSaveable { mutableStateOf(model.subtitleScale()) }
    var subColor by rememberSaveable { mutableStateOf(model.subtitleColor()) }
    LaunchedEffect(playerViewRef, subScale, subColor) {
        val subtitleView = playerViewRef?.subtitleView ?: return@LaunchedEffect
        subtitleView.setApplyEmbeddedStyles(false)
        subtitleView.setApplyEmbeddedFontSizes(false)
        subtitleView.setFractionalTextSize(subScale)
        subtitleView.setStyle(
            CaptionStyleCompat(
                subColor,
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                android.graphics.Color.BLACK,
                null,
            ),
        )
    }
    LaunchedEffect(subScale, subColor) { model.setSubtitleStyle(subScale, subColor) }

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    val playerView = PlayerView(ctx)
                    playerView.player = player
                    playerView.useController = true
                    playerView.controllerShowTimeoutMs = 3_000
                    playerView.setShowNextButton(viewer.items.size > 1)
                    playerView.setShowPreviousButton(viewer.items.size > 1)
                    playerView.setBackgroundColor(android.graphics.Color.BLACK)
                    // The top bar follows the controls in and out.
                    playerView.setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == View.VISIBLE
                        },
                    )
                    playerViewRef = playerView

                    // Sideways-drag scrubbing, watched but never consumed: the
                    // listener always returns false, so a tap still reaches the
                    // view's own controls and a press still reaches a control's
                    // button. Only a horizontal drag drives the seek, and it is
                    // committed when the finger lifts.
                    var seeking = false
                    var base = 0L
                    val detector = GestureDetector(
                        ctx,
                        object : GestureDetector.SimpleOnGestureListener() {
                            override fun onDown(e: MotionEvent) = true

                            override fun onScroll(
                                e1: MotionEvent?,
                                e2: MotionEvent,
                                distanceX: Float,
                                distanceY: Float,
                            ): Boolean {
                                if (e1 == null) return false
                                val movedX = e2.x - e1.x
                                val movedY = e2.y - e1.y
                                if (kotlin.math.abs(movedX) <= kotlin.math.abs(movedY)) return false
                                val width = playerView.width.takeIf { it > 0 } ?: return false
                                val duration = player.duration.takeIf { it > 0 } ?: return false
                                if (!seeking) {
                                    seeking = true
                                    base = player.currentPosition
                                }
                                val delta = (movedX / width * 120_000f).toLong()
                                onSeekPreview((base + delta).coerceIn(0L, duration))
                                return true
                            }
                        },
                    )
                    playerView.setOnTouchListener { _, event ->
                        detector.onTouchEvent(event)
                        if (event.actionMasked == MotionEvent.ACTION_UP ||
                            event.actionMasked == MotionEvent.ACTION_CANCEL
                        ) {
                            if (seeking) {
                                onSeekCommit()
                                seeking = false
                            }
                        }
                        false
                    }
                    playerView
                },
                modifier = Modifier.fillMaxSize(),
            )
            // A way back, over the top-left, since the player's own controls
            // have no exit; the subtitle and turn buttons opposite it. Shown
            // only while the controls are, so the picture is otherwise clear.
            AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(top = reservedTopDp)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
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
                    IconButton(onClick = { showSubtitleSheet = true }) {
                        Icon(
                            Icons.Filled.Subtitles,
                            contentDescription = stringResource(R.string.action_subtitles),
                            tint = Color.White,
                        )
                    }
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
                        text = clock(seekTarget) + " / " + clock(player.duration.coerceAtLeast(0L)),
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

    if (showSubtitleSheet) {
        SubtitleSheet(
            player = player,
            tracksVersion = tracksVersion,
            scale = subScale,
            color = subColor,
            onScale = { subScale = it },
            onColor = { subColor = it },
            onDismiss = { showSubtitleSheet = false },
        )
    }
}

/**
 * The subtitle settings, in a sheet up from the bottom.
 *
 * Two things, in the one place: which subtitle to show -- off, or any of the
 * tracks the film carries or was found beside it -- and how it looks, its size
 * on a slider and its colour among a few. The look is the app's throughout; the
 * choice of track is this film's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SubtitleSheet(
    player: Player,
    tracksVersion: Int,
    scale: Float,
    color: Int,
    onScale: (Float) -> Unit,
    onColor: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                stringResource(R.string.action_subtitles),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))

            Text(
                stringResource(R.string.subtitle_track),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            val textGroups = remember(tracksVersion, player) {
                player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            }
            val anySelected = textGroups.any { group ->
                (0 until group.length).any { group.isTrackSelected(it) }
            }
            SubtitleChoice(
                label = stringResource(R.string.subtitle_off),
                selected = !anySelected,
            ) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
            val fallback = stringResource(R.string.subtitle_default_name)
            textGroups.forEach { group ->
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    val format = group.getTrackFormat(i)
                    val label = format.label ?: trackLanguageName(format.language) ?: fallback
                    SubtitleChoice(label = label, selected = group.isTrackSelected(i)) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .build()
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.subtitle_size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Slider(
                value = scale,
                onValueChange = onScale,
                valueRange = AppPreferences.MIN_SUBTITLE_SCALE..AppPreferences.MAX_SUBTITLE_SCALE,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.subtitle_color),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (swatch in SUBTITLE_COLORS) {
                    val chosen = swatch == color
                    Box(
                        Modifier
                            .size(36.dp)
                            .border(
                                width = if (chosen) 3.dp else 1.dp,
                                color = if (chosen) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                shape = CircleShape,
                            )
                            .padding(4.dp)
                            .background(Color(swatch), CircleShape)
                            .clickable { onColor(swatch) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

// The colours the subtitle can be, white first: the caption colours people
// reach for, on a dark film.
private val SUBTITLE_COLORS = listOf(
    0xFFFFFFFF.toInt(),
    0xFFFFEB3B.toInt(),
    0xFF00E5FF.toInt(),
    0xFF76FF03.toInt(),
    0xFFFF5252.toInt(),
)

/** A readable name for a subtitle track's language code, for the picker. */
private fun trackLanguageName(language: String?): String? = when (language?.lowercase()) {
    null -> null
    "ko", "kor" -> "한국어"
    "en", "eng" -> "English"
    "ja", "jpn" -> "日本語"
    "zh", "chi", "zho" -> "中文"
    else -> language.uppercase(Locale.ROOT)
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
