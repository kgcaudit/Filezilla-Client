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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ScreenLockRotation
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import org.filezilla.android.R
import org.filezilla.android.data.AppPreferences
import org.filezilla.android.playback.PlaybackService
import org.filezilla.android.playback.SubtitleBundle

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
    // neither, so that keeps playing in the background. The place is saved
    // before the playlist is cleared, or leaving would lose it and the film
    // would reopen at the start.
    val close: () -> Unit = {
        player?.let {
            savePlaybackPosition(it, viewer.items, model)
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
                // Only a file the player ran on to the next from is put back to
                // the start; a playlist being set or cleared (opening, or leaving
                // and clearing) must not zero the place we just saved.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    viewer.items.getOrNull(index)?.let { model.setMediaPosition(it, 0L) }
                }
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
            savePlaybackPosition(player, viewer.items, model)
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
            player.setMediaItems(
                viewer.items.map { mediaItemFor(it, context.cacheDir) },
                viewer.index,
                start,
            )
            player.prepare()
            player.playWhenReady = true
        } else {
            index = player.currentMediaItemIndex
        }
    }

    // The screen's own turning, a plain on/off: on, it follows the sensor and
    // turns with the phone; off, it holds the way it is. Put back to the
    // phone's own preference on the way out.
    val activity = context as? android.app.Activity
    var autoRotate by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(autoRotate) {
        activity?.requestedOrientation = if (autoRotate) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // Hand the screen's brightness back to the system on the way out;
            // the left-edge drag may have pinned it.
            activity?.window?.let { window ->
                window.attributes = window.attributes.also {
                    it.screenBrightness =
                        android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    // While something is playing the screen is held awake, so a film is not
    // dimmed or slept through for want of a touch. The flag is cleared the
    // moment playback pauses, and on the way out.
    DisposableEffect(player, activity) {
        val window = activity?.window
        val keepAwake = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        fun sync() {
            if (player.isPlaying) window?.addFlags(keepAwake) else window?.clearFlags(keepAwake)
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = sync()
        }
        player.addListener(listener)
        sync()
        onDispose {
            player.removeListener(listener)
            window?.clearFlags(keepAwake)
        }
    }

    // The left of the picture is a brightness dial, the right a volume dial:
    // slide up or down on either. Brightness is the window's own, handed back to
    // the system on the way out; volume is the media stream's. Each shows a
    // read-out while the finger is down. Brightness starts from wherever the
    // system had it, volume from where the stream is.
    val audio = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    }
    var brightness by remember { mutableFloatStateOf(-1f) }
    var volume by remember { mutableFloatStateOf(-1f) }
    var brightnessHud by remember { mutableFloatStateOf(-1f) }
    var volumeHud by remember { mutableFloatStateOf(-1f) }
    val onBrightnessDelta: (Float) -> Unit = { fraction ->
        val start = if (brightness in 0f..1f) brightness else systemBrightness(context)
        val next = (start + fraction).coerceIn(0.01f, 1f)
        brightness = next
        activity?.window?.let { window ->
            window.attributes = window.attributes.also { it.screenBrightness = next }
        }
        brightnessHud = next
        volumeHud = -1f
    }
    val onVolumeDelta: (Float) -> Unit = { fraction ->
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val start = if (volume in 0f..1f) {
            volume
        } else {
            audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max
        }
        val next = (start + fraction).coerceIn(0f, 1f)
        volume = next
        audio.setStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            (next * max).roundToInt(),
            0,
        )
        volumeHud = next
        brightnessHud = -1f
    }
    val onGestureEnd: () -> Unit = {
        brightnessHud = -1f
        volumeHud = -1f
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
    // Starts hidden, since the controls do too -- they come up on a tap.
    var controlsVisible by remember { mutableStateOf(false) }
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

    // The subtitle tracks the player has, named for the picker: which number,
    // whether it comes from a file beside the film or from inside it, its
    // format and its language. Rebuilt whenever the tracks change.
    val undLabel = stringResource(R.string.subtitle_language_unknown)
    val textTracks = remember(tracksVersion, player, undLabel) {
        buildList {
            var number = 0
            for (group in player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }) {
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    val format = group.getTrackFormat(i)
                    number++
                    val external = isExternalSubtitle(format)
                    val language = format.language?.takeIf { it.isNotBlank() }
                    add(
                        TextTrack(
                            group = group,
                            trackIndex = i,
                            number = number,
                            external = external,
                            format = subtitleFormat(external, format),
                            language = trackLanguageName(language) ?: language ?: undLabel,
                            token = subtitleToken(external, format, number),
                            selected = group.isTrackSelected(i),
                        ),
                    )
                }
            }
        }
    }
    val subtitleOn = textTracks.any { it.selected }

    // Choosing a subtitle, and remembering the choice against this file so it
    // comes back the same next time. Turning the toggle on picks the first
    // track; off disables text.
    val currentFile = viewer.items.getOrNull(index)
    val onSelectTrack: (TextTrack) -> Unit = { track ->
        applyTextTrack(player, track)
        currentFile?.let { model.setSubtitleChoice(it, track.token) }
    }
    val onSubtitleToggle: (Boolean) -> Unit = { on ->
        if (on) {
            textTracks.firstOrNull()?.let { onSelectTrack(it) }
        } else {
            disableTextTracks(player)
            currentFile?.let { model.setSubtitleChoice(it, SUBTITLE_OFF_TOKEN) }
        }
    }

    // On opening a file, put back the subtitle it was last watched with -- once,
    // as soon as the tracks are known. With no saved choice, the text selection
    // is cleared to its default instead, so a previous film's "off" does not
    // carry over and keep this one's subtitle from showing (the player, and its
    // selection, are the service's and outlive one film).
    var subtitleAppliedFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentFile, tracksVersion) {
        val file = currentFile ?: return@LaunchedEffect
        if (subtitleAppliedFor == file.path) return@LaunchedEffect
        if (textTracks.isEmpty() && player.playbackState != Player.STATE_READY) return@LaunchedEffect
        when (val token = model.subtitleChoice(file)) {
            null -> {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .build()
            }
            SUBTITLE_OFF_TOKEN -> disableTextTracks(player)
            else -> textTracks.firstOrNull { it.token == token }?.let { applyTextTrack(player, it) }
        }
        subtitleAppliedFor = file.path
    }

    // Playback speed, cycled by the speed button, and the picture's fit --
    // letterboxed, cropped to fill, or stretched -- cycled by the aspect button.
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    LaunchedEffect(player, speed) { player.setPlaybackSpeed(speed) }
    var resizeMode by rememberSaveable { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    LaunchedEffect(playerViewRef, resizeMode) { playerViewRef?.resizeMode = resizeMode }

    // Two fingers pinched apart or together zoom the picture in or out, held
    // between its own size and four times it.
    var videoScale by rememberSaveable { mutableFloatStateOf(1f) }
    val onScaleDelta: (Float) -> Unit = { factor ->
        videoScale = (videoScale * factor).coerceIn(1f, 4f)
    }

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    // Inflated (not new PlayerView(ctx)) so it uses a TextureView,
                    // which the pinch-zoom can scale; a SurfaceView cannot.
                    val playerView = android.view.LayoutInflater.from(ctx)
                        .inflate(R.layout.media_player_view, null) as PlayerView
                    playerView.player = player
                    playerView.useController = true
                    // The controls come up on a tap, not on their own, so the
                    // picture is clear until asked. Showing and hiding is driven
                    // by the gesture listener below rather than by the view, so
                    // only a tap in the middle brings them up and a stray tap on
                    // an edge does not.
                    playerView.controllerAutoShow = false
                    playerView.controllerHideOnTouch = false
                    playerView.controllerShowTimeoutMs = 3_000
                    // The centre is play, flanked by a ten-second rewind and
                    // fast-forward rather than the previous and next file: the
                    // side buttons move within this film, not between films.
                    playerView.setShowNextButton(false)
                    playerView.setShowPreviousButton(false)
                    playerView.setShowRewindButton(true)
                    playerView.setShowFastForwardButton(true)
                    playerView.setBackgroundColor(android.graphics.Color.BLACK)
                    // The top bar follows the controls in and out.
                    playerView.setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == View.VISIBLE
                        },
                    )
                    playerViewRef = playerView

                    // All touches on the picture are ours, so the controls show
                    // and hide only as told. A tap in the middle half brings the
                    // controls up (or, if they are up, a tap anywhere puts them
                    // down); a tap on an edge does nothing, so resting a thumb
                    // there does not keep flashing the menu. A sideways drag
                    // scrubs. An up-or-down drag is a brightness dial on the left
                    // quarter and a volume dial on the right quarter -- the
                    // middle is left to the tap. The control buttons are child
                    // views and take their own presses before this runs.
                    var seeking = false
                    var base = 0L
                    val detector = GestureDetector(
                        ctx,
                        object : GestureDetector.SimpleOnGestureListener() {
                            override fun onDown(e: MotionEvent) = true

                            // A confirmed single tap (one that is not the start of
                            // a double tap) works the controls; a double tap plays
                            // or pauses. Single is confirmed rather than taken on
                            // the way up so the first tap of a double tap does not
                            // also flash the menu.
                            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                                if (playerView.isControllerFullyVisible) {
                                    playerView.hideController()
                                } else {
                                    val width = playerView.width
                                    if (width > 0 &&
                                        e.x > width * DIAL_EDGE_FRACTION &&
                                        e.x < width * (1f - DIAL_EDGE_FRACTION)
                                    ) {
                                        playerView.showController()
                                    }
                                }
                                return true
                            }

                            override fun onDoubleTap(e: MotionEvent): Boolean {
                                if (player.isPlaying) player.pause() else player.play()
                                return true
                            }

                            override fun onScroll(
                                e1: MotionEvent?,
                                e2: MotionEvent,
                                distanceX: Float,
                                distanceY: Float,
                            ): Boolean {
                                if (e1 == null) return false
                                val width = playerView.width.takeIf { it > 0 } ?: return false
                                val height = playerView.height.takeIf { it > 0 } ?: return false
                                // Which dial a drag is depends only on where it
                                // started, held for the whole drag: a drag begun
                                // in the left seventh is brightness, in the right
                                // seventh volume, and only one begun in the middle
                                // five-sevenths scrubs. So a brightness or volume
                                // drag that wanders a little sideways no longer
                                // jumps the playback position. distanceY is
                                // positive moving up, so up brightens and raises.
                                when {
                                    e1.x < width * DIAL_EDGE_FRACTION ->
                                        onBrightnessDelta(distanceY / height * DIAL_SENSITIVITY)
                                    e1.x > width * (1f - DIAL_EDGE_FRACTION) ->
                                        onVolumeDelta(distanceY / height * DIAL_SENSITIVITY)
                                    else -> {
                                        // Middle: scrub, but only on a clearly
                                        // sideways drag, so an up-or-down one here
                                        // does nothing.
                                        val movedX = e2.x - e1.x
                                        val movedY = e2.y - e1.y
                                        if (kotlin.math.abs(movedX) <= kotlin.math.abs(movedY)) {
                                            return false
                                        }
                                        val duration = player.duration.takeIf { it > 0 } ?: return false
                                        if (!seeking) {
                                            seeking = true
                                            base = player.currentPosition
                                        }
                                        val delta = (movedX / width * 120_000f).toLong()
                                        onSeekPreview((base + delta).coerceIn(0L, duration))
                                    }
                                }
                                return true
                            }
                        },
                    )
                    // Two-finger pinch to zoom, watched alongside the one-finger
                    // gestures. While a pinch is in hand the one-finger detector
                    // is left out, so a zoom is not also read as a scrub or dial.
                    val scaleDetector = android.view.ScaleGestureDetector(
                        ctx,
                        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                                onScaleDelta(d.scaleFactor)
                                return true
                            }
                        },
                    )
                    playerView.setOnTouchListener { _, event ->
                        scaleDetector.onTouchEvent(event)
                        if (!scaleDetector.isInProgress) detector.onTouchEvent(event)
                        if (event.actionMasked == MotionEvent.ACTION_UP ||
                            event.actionMasked == MotionEvent.ACTION_CANCEL
                        ) {
                            if (seeking) {
                                onSeekCommit()
                                seeking = false
                            }
                            onGestureEnd()
                        }
                        true
                    }
                    playerView
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = videoScale
                        scaleY = videoScale
                    },
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
                    // Speed: a tap steps through the usual rates.
                    Text(
                        speedLabel(speed),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { speed = nextSpeed(speed) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    IconButton(
                        onClick = {
                            resizeMode = when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT ->
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM ->
                                    AspectRatioFrameLayout.RESIZE_MODE_FILL
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        },
                    ) {
                        Icon(
                            Icons.Filled.AspectRatio,
                            contentDescription = stringResource(R.string.action_aspect),
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = { showSubtitleSheet = true }) {
                        Icon(
                            Icons.Filled.Subtitles,
                            contentDescription = stringResource(R.string.action_subtitles),
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = { autoRotate = !autoRotate }) {
                        if (autoRotate) {
                            Icon(
                                Icons.Filled.ScreenRotation,
                                contentDescription = stringResource(R.string.action_rotate),
                                tint = Color.White,
                            )
                        } else {
                            Icon(
                                Icons.Filled.ScreenLockRotation,
                                contentDescription = stringResource(R.string.action_rotate_lock),
                                tint = Color.White,
                            )
                        }
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
            // The brightness and volume read-outs: a bar up the side the dial is
            // on -- brightness on the left, volume on the right -- shown only
            // while that dial is in hand.
            if (brightnessHud >= 0) {
                EdgeLevelBar(
                    level = brightnessHud,
                    icon = Icons.Filled.LightMode,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
            if (volumeHud >= 0) {
                EdgeLevelBar(
                    level = volumeHud,
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }

    if (showSubtitleSheet) {
        SubtitleSheet(
            subtitleOn = subtitleOn,
            tracks = textTracks,
            onToggle = onSubtitleToggle,
            onSelectTrack = onSelectTrack,
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
 * A switch turns subtitles on or off; under it the tracks, each named by its
 * number, by whether it comes from a file beside the film or from inside it,
 * and by its format and language, the shown one marked. Below that, the other
 * subtitle files in the folder, and the size and colour that hold for every
 * film.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitleSheet(
    subtitleOn: Boolean,
    tracks: List<TextTrack>,
    onToggle: (Boolean) -> Unit,
    onSelectTrack: (TextTrack) -> Unit,
    scale: Float,
    color: Int,
    onScale: (Float) -> Unit,
    onColor: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Scrolls, so nothing is lost off the bottom when the sheet is short --
        // as it is in landscape, where the film left it cut off.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.subtitle_show),
                    style = MaterialTheme.typography.titleSmall,
                )
                Switch(checked = subtitleOn, onCheckedChange = onToggle)
            }

            tracks.forEach { track ->
                val source = stringResource(
                    if (track.external) R.string.subtitle_external else R.string.subtitle_internal,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelectTrack(track) }
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = track.selected,
                        onClick = { onSelectTrack(track) },
                        modifier = Modifier.size(22.dp),
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    ) {
                        Text(
                            stringResource(R.string.subtitle_track_label, source, track.number),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${track.format} · TEXT · ${track.language}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            // Size and colour share a row: the slider takes the width it can and
            // the swatches sit at the end, so the look controls cost one line,
            // not three.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.subtitle_size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Slider(
                    value = scale,
                    onValueChange = onScale,
                    valueRange = AppPreferences.MIN_SUBTITLE_SCALE..AppPreferences.MAX_SUBTITLE_SCALE,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.subtitle_color),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp),
                )
                for (swatch in SUBTITLE_COLORS) {
                    val chosen = swatch == color
                    Box(
                        Modifier
                            .size(28.dp)
                            .border(
                                width = if (chosen) 3.dp else 1.dp,
                                color = if (chosen) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                shape = CircleShape,
                            )
                            .padding(3.dp)
                            .background(Color(swatch), CircleShape)
                            .clickable { onColor(swatch) },
                    )
                }
            }
        }
    }
}

/**
 * A slim bar up one edge of the picture showing a level from empty to full,
 * with its dial's icon above it -- the brightness or volume read-out while a
 * finger is on it, in the manner of the phone's own volume slider.
 */
@Composable
private fun EdgeLevelBar(level: Float, icon: ImageVector, modifier: Modifier) {
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .width(6.dp)
                .height(150.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.3f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(level.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(50))
                    .background(Color.White),
            )
        }
    }
}

/**
 * A subtitle track as the picker shows it: its place in the list, whether it
 * came from a file or from inside the film, its format and language, whether it
 * is the one showing, and a token that names it in the saved-choice memory.
 */
private data class TextTrack(
    val group: Tracks.Group,
    val trackIndex: Int,
    val number: Int,
    val external: Boolean,
    val format: String,
    val language: String,
    val token: String,
    val selected: Boolean,
)

/** The value saved against a file whose subtitles the reader turned off. */
private const val SUBTITLE_OFF_TOKEN = "off"

/** Selects [track]'s text, turning subtitles on if they were off. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun applyTextTrack(player: Player, track: TextTrack) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex))
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .build()
}

/** Turns subtitles off. */
private fun disableTextTracks(player: Player) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .build()
}

/**
 * Whether a track is a subtitle the app attached from a file rather than one
 * carried inside the film. The mark it was given (an id) is the sure sign; a
 * label that is a subtitle filename is the fallback, for a media3 that dropped
 * the id in passing.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun isExternalSubtitle(format: androidx.media3.common.Format): Boolean {
    if (format.id?.startsWith(EXTERNAL_SUB_ID_PREFIX) == true) return true
    val labelExt = format.label?.substringAfterLast('.', "")?.lowercase()
    return labelExt != null && labelExt in SUBTITLE_EXTENSIONS
}

/**
 * The short format name for a track: for an external one, the file's own
 * extension (SRT, SMI, ASS); for one inside the film, its codec (SUBRIP, VTT).
 * When a subtitle has been transcoded to media3's cues, its own format is kept
 * in the codecs field, which is read here so the cue name never shows.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun subtitleFormat(external: Boolean, format: androidx.media3.common.Format): String {
    if (external) {
        val ext = format.label?.substringAfterLast('.', "")?.uppercase().orEmpty()
        if (ext.isNotEmpty()) return ext
    }
    val mime = if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) {
        format.codecs ?: format.sampleMimeType
    } else {
        format.sampleMimeType
    }
    return when (mime) {
        MimeTypes.APPLICATION_SUBRIP -> "SUBRIP"
        MimeTypes.TEXT_VTT -> "VTT"
        MimeTypes.TEXT_SSA -> "SSA"
        MimeTypes.APPLICATION_TTML -> "TTML"
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.APPLICATION_DVBSUBS -> "DVB"
        // The internal cues name is never shown: fall back to the label's
        // extension, then to a plain "SUB", when the real format is not known.
        MimeTypes.APPLICATION_MEDIA3_CUES, null ->
            format.label?.substringAfterLast('.', "")?.uppercase()?.takeIf { it.isNotEmpty() }
                ?: "SUB"
        else -> mime.substringAfterLast('/').uppercase()
    }
}

/** A stable key for a track, for remembering which one a file was watched with. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun subtitleToken(external: Boolean, format: androidx.media3.common.Format, number: Int): String =
    if (external) {
        format.id ?: "ext$number"
    } else {
        format.language?.takeIf { it.isNotBlank() } ?: format.label ?: format.id ?: "int$number"
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

/**
 * The system's current screen brightness as a 0..1 fraction, the starting
 * point for the brightness dial before it has moved the window's own. Falls
 * back to the middle if the setting cannot be read.
 */
private fun systemBrightness(context: Context): Float = runCatching {
    android.provider.Settings.System.getInt(
        context.contentResolver,
        android.provider.Settings.System.SCREEN_BRIGHTNESS,
    ) / 255f
}.getOrDefault(0.5f).coerceIn(0.01f, 1f)

/** A readable name for a subtitle track's language code, for the picker. */
private fun trackLanguageName(language: String?): String? = when (language?.lowercase()) {
    null -> null
    "ko", "kor" -> "한국어"
    "en", "eng" -> "English"
    "ja", "jpn" -> "日本語"
    "zh", "chi", "zho" -> "中文"
    else -> language.uppercase(Locale.ROOT)
}

// How wide the brightness and volume edges are, as a fraction of the width: a
// seventh each, so the picture divides 1:5:1 -- brightness, the tap-and-scrub
// middle, volume.
private const val DIAL_EDGE_FRACTION = 1f / 7f

// How fast the brightness and volume dials move: a full sweep of either takes
// about a third of the height, rather than the whole of it, which felt sluggish.
private const val DIAL_SENSITIVITY = 3f

// The playback rates the speed button steps through, slow to fast.
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** The rate after [current] in the cycle, wrapping back to the slowest. */
private fun nextSpeed(current: Float): Float {
    val i = SPEEDS.indexOfFirst { kotlin.math.abs(it - current) < 0.001f }
        .let { if (it < 0) SPEEDS.indexOf(1f) else it }
    return SPEEDS[(i + 1) % SPEEDS.size]
}

/** A rate as it is shown on the button: 1.0x, 1.5x, 0.5x. */
private fun speedLabel(speed: Float): String {
    val text = if (speed == speed.toLong().toFloat()) {
        String.format(Locale.ROOT, "%.1f", speed)
    } else {
        speed.toString()
    }
    return text + "x"
}

/**
 * Saves where the playing file is now, so it reopens there. A file within a
 * second of its end is saved at the start, since that reads as finished. Does
 * nothing once the playlist is empty -- there is nothing to place.
 */
private fun savePlaybackPosition(player: Player, items: List<File>, model: MainViewModel) {
    if (player.mediaItemCount == 0) return
    val at = player.currentMediaItemIndex
    val position = player.currentPosition
    val duration = player.duration
    val save = if (duration > 0 && position >= duration - 1_000) 0L else position
    items.getOrNull(at)?.let { model.setMediaPosition(it, save) }
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
 * selectable tracks, the first (or a Korean one) shown by default. A SAMI
 * (.smi) among them is turned into WebVTT in [cacheDir] first, since the player
 * has no SAMI reader of its own.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun mediaItemFor(file: File, cacheDir: File): MediaItem =
    buildMediaItem(Uri.fromFile(file), sidecarSubtitles(file, cacheDir))

/**
 * A MediaItem for [uri] with [subtitles], built to survive the trip to the
 * playback service. A controller keeps only a MediaItem's id and metadata
 * across that boundary, so the uri is put in the request metadata and the
 * subtitles in the metadata extras, and the service restores both (see
 * SubtitleBundle). Without this the service's player would get a film with no
 * sound file and no external subtitles.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun buildMediaItem(
    uri: Uri,
    subtitles: List<MediaItem.SubtitleConfiguration>,
): MediaItem = MediaItem.Builder()
    .setUri(uri)
    .setMediaId(uri.toString())
    .setMediaMetadata(
        MediaMetadata.Builder().setExtras(SubtitleBundle.encode(subtitles)).build(),
    )
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
    .setSubtitleConfigurations(subtitles)
    .build()

// The subtitle formats media3 reads on its own, by extension. SAMI (.smi,
// .sami) it cannot, and is converted to WebVTT before it reaches here.
private val SUBTITLE_MIME = mapOf(
    "srt" to MimeTypes.APPLICATION_SUBRIP,
    "vtt" to MimeTypes.TEXT_VTT,
    "webvtt" to MimeTypes.TEXT_VTT,
    "ass" to MimeTypes.TEXT_SSA,
    "ssa" to MimeTypes.TEXT_SSA,
    "ttml" to MimeTypes.APPLICATION_TTML,
    "dfxp" to MimeTypes.APPLICATION_TTML,
)

private val SUBTITLE_EXTENSIONS = SUBTITLE_MIME.keys + setOf("smi", "sami")

/**
 * Whether a subtitle's name is near enough the film's to be the film's. The two
 * are reduced to their letters and digits and one has to be a leading run of the
 * other, so the film's title -- the title with a language on the end, or a
 * slightly different release tag -- matches, while a different film in the same
 * folder does not. Looser than an exact match, since a subtitle downloaded on
 * its own rarely carries the film's whole release name.
 */
private fun subtitleNameMatches(videoBase: String, subtitleStem: String): Boolean {
    fun letters(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
    val a = letters(videoBase)
    val b = letters(subtitleStem)
    if (a.length < 4 || b.length < 4) return a == b
    val common = a.commonPrefixWith(b).length
    // Either one name is the leading run of the other (title, or title plus a
    // language), or the two agree on a good opening stretch -- the title and
    // year -- which the release tag then diverges from. Ten characters of
    // agreement clears a different film, whose title parts ways much sooner.
    return common >= minOf(a.length, b.length) || common >= 10
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun sidecarSubtitles(video: File, cacheDir: File): List<MediaItem.SubtitleConfiguration> {
    val dir = video.parentFile ?: return emptyList()
    val base = video.nameWithoutExtension.lowercase()
    val candidates = dir.listFiles()?.filter { it.isFile } ?: return emptyList()

    val found = candidates.mapNotNull { file ->
        val ext = file.extension.lowercase()
        if (ext !in SUBTITLE_EXTENSIONS) return@mapNotNull null
        val stem = file.nameWithoutExtension.lowercase()
        // The subtitle belongs to this film if its name is near enough the
        // film's -- the film's, the film's with a language tag, or a close
        // release name -- so a subtitle whose name is not word-for-word the
        // film's still attaches.
        if (!subtitleNameMatches(base, stem)) return@mapNotNull null
        // SAMI is rewritten to a .vtt the player can read; the rest are used as
        // they are. A .smi that will not convert is dropped rather than shown
        // blank.
        val (uri, mime) = if (ext == "smi" || ext == "sami") {
            val vtt = SamiSubtitles.toVttFile(cacheDir, file) ?: return@mapNotNull null
            Uri.fromFile(vtt) to MimeTypes.TEXT_VTT
        } else {
            Uri.fromFile(file) to (SUBTITLE_MIME[ext] ?: return@mapNotNull null)
        }
        // The language tag is what the subtitle's name adds after the film's,
        // when its name really does start with the film's; a merely near name
        // adds nothing to read a language from.
        val tag = if (stem.startsWith(base)) {
            stem.removePrefix(base).trimStart('.', '_', '-', ' ')
        } else {
            ""
        }
        // The track is named after its file, so the picker shows which external
        // subtitle it is rather than a bare "subtitle" that reads the same as
        // every other unnamed one.
        SidecarSub(uri, mime, languageOf(tag), file.name)
    }

    // Show one by default: a Korean track if there is one, else the first.
    val defaultIdx = found.indexOfFirst { it.language == "ko" }.let {
        if (it >= 0) it else if (found.isNotEmpty()) 0 else -1
    }
    return found.mapIndexed { i, sub ->
        MediaItem.SubtitleConfiguration.Builder(sub.uri)
            .setMimeType(sub.mime)
            .setLanguage(sub.language)
            .setLabel(sub.label)
            .setId(EXTERNAL_SUB_ID_PREFIX + sub.label)
            .setSelectionFlags(if (i == defaultIdx) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
    }
}

// A subtitle track the app added from a file, rather than one carried inside
// the film, is marked by an id starting with this, so the picker can say which
// is which and show the file's own extension as the format.
const val EXTERNAL_SUB_ID_PREFIX = "olo-ext:"

private data class SidecarSub(
    val uri: Uri,
    val mime: String,
    val language: String?,
    val label: String,
)

/** A rough language from a filename tag, for the track picker's label. */
private fun languageOf(tag: String): String? = when {
    tag.isEmpty() -> null
    tag.startsWith("ko") || tag.startsWith("kr") || tag.contains("kor") || tag.contains("한") -> "ko"
    tag.startsWith("en") || tag.contains("eng") -> "en"
    tag.startsWith("ja") || tag.startsWith("jp") || tag.contains("jpn") -> "ja"
    tag.startsWith("zh") || tag.contains("chi") || tag.contains("chs") || tag.contains("cht") -> "zh"
    else -> tag.take(8)
}
