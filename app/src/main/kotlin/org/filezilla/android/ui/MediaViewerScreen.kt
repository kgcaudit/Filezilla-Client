package org.filezilla.android.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.filezilla.android.R
import org.filezilla.android.data.AppPreferences
import org.filezilla.android.playback.PlaybackService
import org.filezilla.android.playback.SubtitleBundle
import org.filezilla.android.viewer.TextFiles

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

    // A sound gets the music player, a film gets the video player. The playlist
    // is one kind throughout (song with song, film with film), so the opened file
    // decides -- and the choice is made up here because a film hides the system
    // bars for its picture while a song leaves them, for the clock and battery.
    val isAudio = remember(viewer) {
        viewer.items.getOrNull(viewer.index)?.let { kindOf(it.name, false) == FileKind.AUDIO }
            ?: false
    }

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

    // While a film is up, the phone's bars go away so it has the whole screen;
    // leaving restores them. A song keeps the bars -- there is no picture to give
    // the screen to, and the clock is worth having while listening.
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view, isAudio) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        if (!isAudio) {
            controller?.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
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

    if (isAudio) {
        MusicPlayer(player = player, viewer = viewer, model = model, onClose = close)
    } else {
        MediaPlayer(player = player, viewer = viewer, model = model, onClose = close)
    }
}

/**
 * The music player -- the Now Playing screen for a sound.
 *
 * A film fills the black screen with a picture; a song has nothing to look at, so
 * this draws the album's own cover instead -- large and square, over a blurred
 * blow-up of the same cover -- and gives the transport a music player's shape:
 * shuffle, previous, play, next and repeat, with the folder's other songs a tap
 * away in a queue. The engine is the same service player the film uses, so the
 * song carries on in the background with its notification, and its place is kept
 * between openings the same way.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MusicPlayer(
    player: MediaController,
    viewer: MainViewModel.MediaViewer,
    model: MainViewModel,
    onClose: () -> Unit,
) {
    var index by remember { mutableIntStateOf(viewer.index) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var shuffle by remember { mutableStateOf(player.shuffleModeEnabled) }
    var repeatMode by remember { mutableIntStateOf(player.repeatMode) }
    var playbackSpeed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    // While a finger is on the seek bar the ticking read-out is held back, so the
    // thumb follows the finger rather than jumping back to where the song is.
    var scrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableLongStateOf(0L) }
    var showQueue by remember { mutableStateOf(false) }

    // Keep the place, mirror the player's state, and put a song the player runs on
    // from back to its start -- the same bookkeeping the film player does.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
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

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                shuffle = enabled
            }

            override fun onRepeatModeChanged(mode: Int) {
                repeatMode = mode
            }

            override fun onPlaybackParametersChanged(
                parameters: androidx.media3.common.PlaybackParameters,
            ) {
                playbackSpeed = parameters.speed
            }
        }
        player.addListener(listener)
        onDispose {
            savePlaybackPosition(player, viewer.items, model)
            player.removeListener(listener)
        }
    }

    // Load the queue and start where the opened song was left. audioMediaItem does
    // no directory scan, so the items are built in hand.
    LaunchedEffect(player, viewer.items, viewer.index) {
        loadQueue(
            player,
            viewer.items,
            viewer.index,
            model,
            onSameQueue = { index = player.currentMediaItemIndex },
        ) {
            viewer.items.map { audioMediaItem(it) }
        }
    }

    // The position ticks on a half-second while a song plays, for the seek bar and
    // the elapsed read-out; held back while a finger is scrubbing.
    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.duration.coerceAtLeast(0L)
            }
            kotlinx.coroutines.delay(500)
        }
    }

    val currentFile = viewer.items.getOrNull(index)
    // The song's tags and cover, read off the main thread and refreshed each time
    // the song changes. Null until read, and a song with no cover keeps it null --
    // a note glyph stands in.
    var tags by remember { mutableStateOf<MusicTags?>(null) }
    LaunchedEffect(currentFile?.path) {
        tags = null
        val file = currentFile ?: return@LaunchedEffect
        tags = withContext(Dispatchers.IO) { readMusicTags(file) }
    }

    // The song's time-synced lyrics, from an .lrc file beside it, read off the
    // main thread and refreshed with the song. Null when there is none, which is
    // what hides the lyrics pill.
    var lyrics by remember { mutableStateOf<List<LrcLine>?>(null) }
    var showLyrics by remember { mutableStateOf(false) }
    LaunchedEffect(currentFile?.path) {
        lyrics = null
        val file = currentFile ?: return@LaunchedEffect
        lyrics = withContext(Dispatchers.IO) { loadLyrics(file) }
    }

    val accent = Color(0xFFE8A183)
    val onDark = Color.White
    val dim = Color.White.copy(alpha = 0.6f)

    // The title (the tag's, or the filename) and the artist·album line, shown on
    // this screen and handed to the lyrics screen -- worked out once.
    val displayTitle = tags?.title?.takeIf { it.isNotBlank() }
        ?: currentFile?.nameWithoutExtension.orEmpty()
    val displaySubtitle = musicSubtitle(tags, stringResource(R.string.music_unknown_artist))

    BackHandler(onBack = onClose)

    Surface(Modifier.fillMaxSize(), color = Color(0xFF12100E)) {
        Box(Modifier.fillMaxSize()) {
            // The blurred cover behind everything, with a dark wash over it so the
            // white text and controls read against any album.
            tags?.background?.let { bg ->
                Image(
                    bitmap = bg,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Top bar: a way back, and the "now playing" label.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = onDark,
                        )
                    }
                    Text(
                        stringResource(R.string.music_now_playing),
                        style = MaterialTheme.typography.titleSmall,
                        color = dim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    SleepTimerButton(player = player, tint = onDark)
                }

                Spacer(Modifier.weight(1f))

                // The cover, large and square.
                Box(
                    Modifier
                        .fillMaxWidth(0.82f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val art = tags?.art
                    if (art != null) {
                        Image(
                            bitmap = art,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(96.dp),
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))

                // Title, then artist and album under it.
                Text(
                    displayTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = onDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    displaySubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(24.dp))

                // Seek bar and the elapsed / total read-out.
                val shown = if (scrubbing) scrubMs else positionMs
                val range = durationMs.coerceAtLeast(1L)
                Slider(
                    value = shown.coerceIn(0L, range).toFloat(),
                    onValueChange = { value ->
                        scrubbing = true
                        scrubMs = value.toLong()
                    },
                    onValueChangeFinished = {
                        player.seekTo(scrubMs.coerceIn(0L, durationMs))
                        positionMs = scrubMs
                        scrubbing = false
                    },
                    valueRange = 0f..range.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(clock(shown), style = MaterialTheme.typography.labelMedium, color = dim)
                    Text(clock(durationMs), style = MaterialTheme.typography.labelMedium, color = dim)
                }

                Spacer(Modifier.height(12.dp))

                // Transport: shuffle, previous, play/pause, next, repeat.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { player.shuffleModeEnabled = !player.shuffleModeEnabled }) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = stringResource(R.string.music_shuffle),
                            tint = if (shuffle) accent else dim,
                        )
                    }
                    IconButton(onClick = { player.seekToPrevious() }) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = stringResource(R.string.music_prev),
                            tint = onDark,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(accent)
                            .clickable { if (player.isPlaying) player.pause() else player.play() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isPlaying) {
                            Icon(
                                Icons.Filled.Pause,
                                contentDescription = stringResource(R.string.music_pause),
                                tint = Color(0xFF12100E),
                                modifier = Modifier.size(38.dp),
                            )
                        } else {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.music_play),
                                tint = Color(0xFF12100E),
                                modifier = Modifier.size(38.dp),
                            )
                        }
                    }
                    IconButton(onClick = { player.seekToNext() }) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = stringResource(R.string.music_next),
                            tint = onDark,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            player.repeatMode = when (player.repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                        },
                    ) {
                        when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> Icon(
                                Icons.Filled.RepeatOne,
                                contentDescription = stringResource(R.string.music_repeat_one),
                                tint = accent,
                            )
                            Player.REPEAT_MODE_ALL -> Icon(
                                Icons.Filled.RepeatOn,
                                contentDescription = stringResource(R.string.music_repeat_all),
                                tint = accent,
                            )
                            else -> Icon(
                                Icons.Filled.Repeat,
                                contentDescription = stringResource(R.string.music_repeat),
                                tint = dim,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Playlist and speed, two pills.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MusicPill(
                        text = stringResource(R.string.music_queue),
                        onClick = { showQueue = true },
                        modifier = Modifier.weight(1f),
                    )
                    if (!lyrics.isNullOrEmpty()) {
                        MusicPill(
                            text = stringResource(R.string.lyrics),
                            onClick = { showLyrics = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    MusicPill(
                        text = stringResource(R.string.music_speed, speedNumber(playbackSpeed)),
                        onClick = {
                            val at = PLAYBACK_SPEEDS.indexOfFirst {
                                kotlin.math.abs(it - playbackSpeed) < 0.01f
                            }.coerceAtLeast(0)
                            val next = PLAYBACK_SPEEDS[(at + 1) % PLAYBACK_SPEEDS.size]
                            player.setPlaybackSpeed(next)
                            playbackSpeed = next
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.weight(1f))
            }
        }
    }

    if (showQueue) {
        MusicQueueSheet(
            items = viewer.items,
            current = index,
            onPick = { picked ->
                player.seekTo(picked, 0L)
                player.play()
                showQueue = false
            },
            onDismiss = { showQueue = false },
        )
    }

    if (showLyrics) {
        LyricsScreen(
            lines = lyrics.orEmpty(),
            positionMs = if (scrubbing) scrubMs else positionMs,
            title = displayTitle,
            subtitle = displaySubtitle,
            background = tags?.background,
            onSeek = { player.seekTo(it) },
            onClose = { showLyrics = false },
        )
    }
}

/** A rounded, translucent pill button, for the playlist and speed under the transport. */
@Composable
private fun MusicPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/**
 * The queue: the folder's songs in order, the playing one marked, tap to jump.
 * A panel across the lower part of the screen over a dim backdrop; a tap outside
 * puts it away.
 */
@Composable
private fun MusicQueueSheet(
    items: List<File>,
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = Color(0xFFE8A183)
    val backdrop = remember { MutableInteractionSource() }
    val panel = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = backdrop, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Color(0xFF1B1815))
                .clickable(interactionSource = panel, indication = null, onClick = {})
                .statusBarsPadding()
                .padding(vertical = 12.dp),
        ) {
            Text(
                stringResource(R.string.music_queue),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth()) {
                itemsIndexed(items) { i, file ->
                    val playing = i == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(i) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            (i + 1).toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (playing) accent else Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.width(28.dp),
                        )
                        Text(
                            file.nameWithoutExtension,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (playing) accent else Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The lyrics screen: the song's words, scrolling with it.
 *
 * The line that is due now is picked out -- larger, in the accent colour -- and
 * the list keeps it near the middle as the song runs, so the eye stays put. A
 * line tapped jumps the song to it, which turns the lyrics into a way to move
 * about the song. A song with no words shows a plain note.
 */
@Composable
private fun LyricsScreen(
    lines: List<LrcLine>,
    positionMs: Long,
    title: String,
    subtitle: String,
    background: ImageBitmap?,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val accent = Color(0xFFE8A183)
    val listState = rememberLazyListState()
    // The line due now is the last one whose time has passed; -1 before the first.
    val current = remember(lines, positionMs) { lines.indexOfLast { it.timeMs <= positionMs } }
    // Keep the current line near the middle rather than at the top.
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val centerPx = with(density) { (configuration.screenHeightDp.dp / 2).toPx() }.toInt()
    LaunchedEffect(current) {
        if (current >= 0) {
            runCatching { listState.animateScrollToItem(current, -centerPx + 160) }
        }
    }
    Surface(Modifier.fillMaxSize(), color = Color(0xFF12100E)) {
        Box(Modifier.fillMaxSize()) {
            background?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.66f)),
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = Color.White,
                        )
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 4.dp),
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (lines.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.lyrics_none),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 48.dp),
                    ) {
                        itemsIndexed(lines) { i, line ->
                            val on = i == current
                            Text(
                                line.text.ifBlank { "♪" },
                                style = if (on) {
                                    MaterialTheme.typography.titleLarge
                                } else {
                                    MaterialTheme.typography.titleMedium
                                },
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                color = if (on) accent else Color.White.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSeek(line.timeMs) }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One timed line of an LRC file: when it is sung, and the words. */
private data class LrcLine(val timeMs: Long, val text: String)

/**
 * Loads the lyrics beside [audio] -- a file of the same name with an .lrc
 * extension -- or null when there is none or it holds no timed lines. The bytes
 * are decoded the way the text viewer decodes a file, so a CP949 lyric sheet (the
 * common Korean case) reads rather than turning to mojibake.
 */
private fun loadLyrics(audio: File): List<LrcLine>? {
    val dir = audio.parentFile ?: return null
    val base = audio.nameWithoutExtension
    val lrc = File(dir, "$base.lrc").takeIf { it.isFile }
        ?: dir.listFiles()?.firstOrNull { file ->
            file.isFile && file.extension.equals("lrc", ignoreCase = true) &&
                file.nameWithoutExtension.equals(base, ignoreCase = true)
        }
        ?: return null
    return runCatching { parseLrc(TextFiles.decode(lrc.readBytes()).text) }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
}

// A timestamp tag, [mm:ss] or [mm:ss.xx] (or with a colon before the fraction),
// and the whole-file offset tag that shifts every line.
private val LRC_TIME = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
private val LRC_OFFSET = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)

/**
 * Parses LRC text into timed lines, sorted by time.
 *
 * A line may carry more than one timestamp -- a repeated chorus is written once
 * with each of its times -- so each becomes its own entry. The metadata tags
 * ([ar:], [ti:], ...) have no timestamp and fall away; the [offset:] tag shifts
 * every time, positive bringing the words earlier.
 */
private fun parseLrc(text: String): List<LrcLine> {
    var offset = 0L
    val out = mutableListOf<LrcLine>()
    for (raw in text.lineSequence()) {
        LRC_OFFSET.find(raw)?.let { offset = it.groupValues[1].toLongOrNull() ?: 0L }
        val stamps = LRC_TIME.findAll(raw).toList()
        if (stamps.isEmpty()) continue
        val words = raw.substring(stamps.last().range.last + 1).trim()
        for (stamp in stamps) {
            val minutes = stamp.groupValues[1].toLong()
            val seconds = stamp.groupValues[2].toLong()
            val fraction = stamp.groupValues[3]
            val fractionMs = when (fraction.length) {
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                3 -> fraction.toLong()
                else -> 0L
            }
            val time = minutes * 60_000L + seconds * 1_000L + fractionMs - offset
            out.add(LrcLine(time.coerceAtLeast(0L), words))
        }
    }
    return out.sortedBy { it.timeMs }
}

/** A song's tags and cover, as the music player shows them. */
private data class MusicTags(
    val title: String?,
    val artist: String?,
    val album: String?,
    val art: ImageBitmap?,
    val background: ImageBitmap?,
)

/**
 * Reads a song's title, artist, album and embedded cover from its file. The
 * cover, when there is one, is kept both full-size for the square and shrunk for
 * the blurred backdrop. Anything unreadable comes back null rather than throwing.
 */
private fun readMusicTags(file: File): MusicTags {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.path)
        val cover = retriever.embeddedPicture?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }
        MusicTags(
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            art = cover?.asImageBitmap(),
            background = cover?.let { blurredCover(it) }?.asImageBitmap(),
        )
    } catch (_: Exception) {
        MusicTags(null, null, null, null, null)
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * A soft, dark backdrop from a cover: shrunk to a few dozen pixels so it blows
 * back up blurred, which reads as a blur on every Android version rather than
 * only the newest (where Modifier.blur would work).
 */
private fun blurredCover(cover: Bitmap): Bitmap? = runCatching {
    val target = 40
    val ratio = cover.width.toFloat() / cover.height.coerceAtLeast(1)
    val w = if (ratio >= 1f) target else (target * ratio).roundToInt().coerceAtLeast(1)
    val h = if (ratio >= 1f) (target / ratio).roundToInt().coerceAtLeast(1) else target
    Bitmap.createScaledBitmap(cover, w, h, true)
}.getOrNull()

/** The line under a song's title: artist, and album when the file names one. */
private fun musicSubtitle(tags: MusicTags?, unknownArtist: String): String {
    val artist = tags?.artist?.takeIf { it.isNotBlank() } ?: unknownArtist
    val album = tags?.album?.takeIf { it.isNotBlank() }
    return if (album != null) "$artist · $album" else artist
}

/** A playable for a song: a plain media item, no subtitle sidecars to look for. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun audioMediaItem(file: File): MediaItem = buildMediaItem(Uri.fromFile(file), emptyList())

/**
 * Sets the player's queue to [items] and starts at [index] where that file was
 * last left -- unless the service is already on this very queue (as after a
 * rotation), when resetting it would jerk playback back to the start and
 * [onSameQueue] runs instead. The items are built by [build], which the caller
 * supplies so a song builds them in hand while a film builds them off the main
 * thread (its subtitle scan reads the directory).
 *
 * A controller strips a MediaItem's localConfiguration crossing to the service,
 * so the uri that survives is the one in the request metadata; the guard compares
 * against that, which is what lets it recognise the same queue rather than
 * restarting playback on every rotation.
 */
private suspend fun loadQueue(
    player: MediaController,
    items: List<File>,
    index: Int,
    model: MainViewModel,
    onSameQueue: () -> Unit,
    build: suspend () -> List<MediaItem>,
) {
    val wantUris = items.map { Uri.fromFile(it) }
    val haveUris = (0 until player.mediaItemCount).map {
        player.getMediaItemAt(it).requestMetadata.mediaUri
    }
    if (haveUris != wantUris) {
        val startFile = items.getOrNull(index) ?: return
        player.setMediaItems(build(), index, model.mediaPosition(startFile))
        player.prepare()
        player.playWhenReady = true
    } else {
        onSameQueue()
    }
}

/**
 * The sleep-timer button, for both players.
 *
 * The timer itself lives in the playback service, so it stops the sound even with
 * the app in the background and the screen off. This is only its face: a moon that
 * lights up while a timer is set, a sheet to choose the minutes, and a countdown
 * kept in step with the service -- asked once on opening, then run down here.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SleepTimerButton(player: MediaController, tint: Color) {
    // When the pause is due, on the same elapsed-time clock the service uses, and
    // the seconds left counted down from it. Held across a rotation so the moon
    // stays lit and the count does not restart.
    var dueElapsed by rememberSaveable { mutableLongStateOf(0L) }
    var remainingMs by remember { mutableLongStateOf(0L) }
    var showPicker by remember { mutableStateOf(false) }

    // On opening, ask the service how long is left, so a timer set on one screen
    // shows on the next and survives coming back from the background.
    LaunchedEffect(player) {
        val remaining = querySleepRemaining(player)
        dueElapsed = if (remaining > 0L) SystemClock.elapsedRealtime() + remaining else 0L
    }
    // Count down while a timer is set; clear it when it runs out.
    LaunchedEffect(dueElapsed) {
        if (dueElapsed <= 0L) {
            remainingMs = 0L
            return@LaunchedEffect
        }
        while (true) {
            val left = (dueElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            remainingMs = left
            if (left <= 0L) {
                dueElapsed = 0L
                break
            }
            kotlinx.coroutines.delay(1000)
        }
    }
    val active = dueElapsed > 0L

    IconButton(onClick = { showPicker = true }) {
        Icon(
            Icons.Filled.Bedtime,
            contentDescription = stringResource(R.string.sleep_timer),
            tint = if (active) Color(0xFFE8A183) else tint,
        )
    }

    if (showPicker) {
        SleepTimerSheet(
            remainingMs = if (active) remainingMs else 0L,
            onPick = { minutes ->
                sendSleep(player, minutes)
                dueElapsed = if (minutes > 0) {
                    SystemClock.elapsedRealtime() + minutes * 60_000L
                } else {
                    0L
                }
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

// The choices the sleep timer offers, in minutes; zero turns it off.
private val SLEEP_TIMER_OPTIONS = listOf(0, 15, 30, 45, 60)

/**
 * The sleep-timer choices, in a small dark panel in the middle of the screen: how
 * much is left if a timer is running, then off and the minute options. A tap
 * outside puts it away.
 */
@Composable
private fun SleepTimerSheet(
    remainingMs: Long,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = Color(0xFFE8A183)
    val backdrop = remember { MutableInteractionSource() }
    val panel = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = backdrop, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.72f)
                .widthIn(max = 360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1B1815))
                .clickable(interactionSource = panel, indication = null, onClick = {})
                .padding(vertical = 14.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.sleep_timer),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                if (remainingMs > 0L) {
                    Text(
                        stringResource(R.string.sleep_timer_left, clock(remainingMs)),
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                    )
                }
            }
            for (minutes in SLEEP_TIMER_OPTIONS) {
                val label = if (minutes == 0) {
                    stringResource(R.string.sleep_timer_off)
                } else {
                    stringResource(R.string.sleep_timer_minutes, minutes)
                }
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(minutes) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** Sets or cancels the service's sleep timer; a non-positive minutes cancels it. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun sendSleep(player: MediaController, minutes: Int) {
    val action = if (minutes > 0) PlaybackService.CMD_SLEEP_SET else PlaybackService.CMD_SLEEP_CANCEL
    val args = Bundle().apply {
        if (minutes > 0) putInt(PlaybackService.EXTRA_SLEEP_MINUTES, minutes)
    }
    player.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
}

/** Asks the service how many milliseconds are left on the sleep timer, or zero. */
@androidx.annotation.OptIn(UnstableApi::class)
private suspend fun querySleepRemaining(player: MediaController): Long {
    val future =
        player.sendCustomCommand(SessionCommand(PlaybackService.CMD_SLEEP_QUERY, Bundle.EMPTY), Bundle.EMPTY)
    val result = withContext(Dispatchers.IO) { runCatching { future.get() }.getOrNull() }
    return result?.extras?.getLong(PlaybackService.EXTRA_SLEEP_REMAINING, 0L) ?: 0L
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
    // The player is the service's, so a speed set on one screen shows on the
    // next; kept in step through the listener below.
    var playbackSpeed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    // The controls are the app's own -- drawn in Compose over the picture, never
    // inside the player view -- so they are laid out and take touches at full
    // screen size whatever the zoom does to the picture. These drive them.
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableLongStateOf(0L) }

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

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackParametersChanged(
                parameters: androidx.media3.common.PlaybackParameters,
            ) {
                playbackSpeed = parameters.speed
            }
        }
        player.addListener(listener)
        onDispose {
            savePlaybackPosition(player, viewer.items, model)
            player.removeListener(listener)
        }
    }

    // The play position ticks on a half-second for the seek bar and the elapsed
    // read-out; held back while a finger is scrubbing so the thumb follows it.
    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.duration.coerceAtLeast(0L)
            }
            kotlinx.coroutines.delay(500)
        }
    }

    // Load the playlist and start where the opened film was left. Finding each
    // film's sidecar subtitles reads the directory and rewrites any SAMI, so the
    // items are built off the main thread.
    LaunchedEffect(player, viewer.items, viewer.index) {
        loadQueue(
            player,
            viewer.items,
            viewer.index,
            model,
            onSameQueue = { index = player.currentMediaItemIndex },
        ) {
            withContext(Dispatchers.IO) { viewer.items.map { mediaItemFor(it, context.cacheDir) } }
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
    // read-out while the finger is down. Brightness opens at full and holds where
    // it is set; volume follows the stream.
    val audio = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    }
    // Held across a rotation or a trip to the background -- which is what used to
    // drop the brightness back to the system's level. Brightness begins at full.
    var brightness by rememberSaveable { mutableFloatStateOf(1f) }
    var volume by rememberSaveable { mutableFloatStateOf(-1f) }
    var brightnessHud by remember { mutableFloatStateOf(-1f) }
    var volumeHud by remember { mutableFloatStateOf(-1f) }
    // Applied from here, not only on a drag, so the window takes the remembered
    // brightness on opening -- full, to start -- and takes it again after a
    // rotation or a return from the background, which reset the window otherwise.
    LaunchedEffect(activity, brightness) {
        activity?.window?.let { window ->
            window.attributes = window.attributes.also { it.screenBrightness = brightness }
        }
    }
    val onBrightnessDelta: (Float) -> Unit = { fraction ->
        val next = (brightness + fraction).coerceIn(0.01f, 1f)
        brightness = next
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
    // Latched with a plain remember, not saved across configuration change: the
    // largest inset seen this orientation is held (the status bar's drops to zero
    // once the bar hides), but a rotation starts the latch over, so a portrait
    // notch does not leave an over-tall bar in landscape.
    var reservedTopPx by remember { mutableIntStateOf(0) }
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

    // The chrome -- the top bar, the transport and the seek bar -- is the app's
    // own, drawn in Compose over the picture. It starts hidden so a film plays
    // under a clear screen and comes up on a tap. A counter, bumped on every
    // touch of it, restarts the hide timer so it does not vanish mid-use.
    var controlsVisible by remember { mutableStateOf(false) }
    var controlsTick by remember { mutableIntStateOf(0) }
    val showControls: () -> Unit = {
        controlsVisible = true
        controlsTick++
    }
    // While a film plays, the chrome hides itself a few seconds after the last
    // touch; paused, it stays, so the buttons are there to be read.
    LaunchedEffect(controlsVisible, isPlaying, controlsTick) {
        if (controlsVisible && isPlaying) {
            kotlinx.coroutines.delay(3500)
            controlsVisible = false
        }
    }
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
        mapTracksOfType(player, C.TRACK_TYPE_TEXT) { group, i, number ->
            val format = group.getTrackFormat(i)
            val external = isExternalSubtitle(format)
            val language = format.language?.takeIf { it.isNotBlank() }
            TextTrack(
                group = group,
                trackIndex = i,
                number = number,
                external = external,
                format = subtitleFormat(external, format),
                language = trackLanguageName(language) ?: language ?: undLabel,
                token = subtitleToken(external, format, number),
                selected = group.isTrackSelected(i),
            )
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

    // The audio tracks the film carries, for choosing between them when it has
    // more than one. Rebuilt with the tracks, the way the subtitles are.
    val audioTracks = remember(tracksVersion, player, undLabel) {
        mapTracksOfType(player, C.TRACK_TYPE_AUDIO) { group, i, number ->
            val format = group.getTrackFormat(i)
            val language = format.language?.takeIf { it.isNotBlank() }
            AudioTrack(
                group = group,
                trackIndex = i,
                number = number,
                language = trackLanguageName(language) ?: language ?: undLabel,
                detail = audioDetail(format),
                selected = group.isTrackSelected(i),
            )
        }
    }
    val onSelectAudio: (AudioTrack) -> Unit = { track -> applyAudioTrack(player, track) }
    val onSpeed: (Float) -> Unit = { speed ->
        player.setPlaybackSpeed(speed)
        playbackSpeed = speed
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

    // The picture's fit -- letterboxed, cropped to fill, or stretched -- cycled
    // by the aspect button.
    var resizeMode by rememberSaveable { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    LaunchedEffect(playerViewRef, resizeMode) { playerViewRef?.resizeMode = resizeMode }

    // Two fingers pinched apart or together zoom the picture in or out, held
    // between a fraction of its size and four times it. Zooming out below its own
    // size shrinks the picture to the middle with black around it -- which is how
    // a film that a camera notch cuts into is pulled clear of the notch.
    var videoScale by rememberSaveable { mutableFloatStateOf(1f) }
    val onScaleDelta: (Float) -> Unit = { factor ->
        videoScale = (videoScale * factor).coerceIn(MIN_VIDEO_SCALE, MAX_VIDEO_SCALE)
    }
    // The zoom scales the whole player view (the graphicsLayer below), which is
    // the only box that fills the screen -- the content frame sizes itself to the
    // film's letterbox, so scaling that would confine the zoom to no effect. The
    // player view now carries no controls of its own (they are the app's, in
    // Compose, over the top and never scaled), so scaling it moves only the
    // picture and there is nothing to carry off.
    // Whether the picture has been zoomed off its own size, so the "back to 1x"
    // chip is offered -- pinching to exactly 1x by hand is not something to ask of
    // anyone.
    val zoomed = kotlin.math.abs(videoScale - 1f) > 0.01f

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    // Inflated (not new PlayerView(ctx)) so it uses a TextureView,
                    // which the pinch-zoom can scale; a SurfaceView cannot.
                    val playerView = android.view.LayoutInflater.from(ctx)
                        .inflate(R.layout.media_player_view, null) as PlayerView
                    playerView.player = player
                    // The view draws the picture and the subtitles, nothing else:
                    // its own controls are turned off and the app draws its own in
                    // Compose over the top, so the zoom (which scales this view)
                    // never touches them and they never fall out of reach.
                    playerView.useController = false
                    playerView.setBackgroundColor(android.graphics.Color.BLACK)
                    playerViewRef = playerView
                    playerView
                },
                // Let go of the player when the view goes, so a released view is
                // not left referenced and still fed frames. The controller outlives
                // this (it is the service's) and is released separately.
                onRelease = { it.player = null },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = videoScale
                        scaleY = videoScale
                    },
            )
            // The gesture layer: a full-screen sheet over the picture that reads
            // every touch, so shrinking the picture never shrinks where a gesture
            // lands. It stands down while the controls are up, letting the built-in
            // seek bar and buttons take touches instead. See VideoGestures for the
            // arbitration -- one finger dials or scrubs, two fingers zoom, and the
            // two never leak into each other.
            if (!controlsVisible) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .videoGestures(
                            player = player,
                            onShowControls = showControls,
                            onBrightnessDelta = onBrightnessDelta,
                            onVolumeDelta = onVolumeDelta,
                            onSeekPreview = onSeekPreview,
                            onSeekCommit = onSeekCommit,
                            onScaleDelta = onScaleDelta,
                            onGestureEnd = onGestureEnd,
                        ),
                )
            }
            // The chrome, the app's own: a scrim that dims the picture and takes a
            // tap to put the chrome away, the top bar, the centre transport and the
            // seek bar. Drawn in Compose over the picture, so the zoom never moves
            // it and its buttons are always where they are drawn.
            if (controlsVisible) {
                // Every touch of the chrome restarts its hide timer.
                val onTouchChrome: () -> Unit = { controlsTick++ }
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { controlsVisible = false })
                        }
                        .background(Color.Black.copy(alpha = 0.28f)),
                )
                // Top bar: back, filename, sleep timer, aspect, rotate.
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
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
                    SleepTimerButton(player = player, tint = Color.White)
                    IconButton(
                        onClick = {
                            onTouchChrome()
                            // Changing the fit also puts a pinch zoom back to 1x,
                            // so the two ways of sizing the picture do not stack up
                            // into a state that is hard to read or undo.
                            videoScale = 1f
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
                    IconButton(onClick = {
                        onTouchChrome()
                        autoRotate = !autoRotate
                    }) {
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
                // Centre transport: ten seconds back, play/pause, ten seconds on.
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            onTouchChrome()
                            player.seekBack()
                        },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            Icons.Filled.Replay10,
                            contentDescription = stringResource(R.string.video_rewind),
                            tint = Color.White,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable {
                                onTouchChrome()
                                if (player.isPlaying) player.pause() else player.play()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isPlaying) {
                            Icon(
                                Icons.Filled.Pause,
                                contentDescription = stringResource(R.string.music_pause),
                                tint = Color.Black,
                                modifier = Modifier.size(40.dp),
                            )
                        } else {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.music_play),
                                tint = Color.Black,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            onTouchChrome()
                            player.seekForward()
                        },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            Icons.Filled.Forward10,
                            contentDescription = stringResource(R.string.video_forward),
                            tint = Color.White,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                // Bottom bar: elapsed, the seek bar, total, and the settings gear.
                val shownPos = if (scrubbing) scrubMs else positionMs
                val seekRange = durationMs.coerceAtLeast(1L)
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        clock(shownPos),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                    Slider(
                        value = shownPos.coerceIn(0L, seekRange).toFloat(),
                        onValueChange = { value ->
                            scrubbing = true
                            scrubMs = value.toLong()
                            controlsTick++
                        },
                        onValueChangeFinished = {
                            player.seekTo(scrubMs.coerceIn(0L, durationMs))
                            positionMs = scrubMs
                            scrubbing = false
                            controlsTick++
                        },
                        valueRange = 0f..seekRange.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                    )
                    Text(
                        clock(durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                    IconButton(onClick = {
                        onTouchChrome()
                        showSubtitleSheet = true
                    }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.action_settings),
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
            // A "back to 1x" chip: one tap from a shrunk or blown-up picture back
            // to normal, without hunting for exactly 1x by hand. It rides with the
            // controls -- shown only while they are, so a film watched shrunk (to
            // clear a notch) is not saddled with a chip the whole way through -- and
            // sits below the top bar's row so it never lands on the filename.
            if (zoomed && controlsVisible) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = reservedTopDp + 64.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { videoScale = 1f }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.video_zoom_reset),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }
        }
    }

    if (showSubtitleSheet) {
        PlayerSettingsSheet(
            subtitleOn = subtitleOn,
            tracks = textTracks,
            onToggle = onSubtitleToggle,
            onSelectTrack = onSelectTrack,
            audioTracks = audioTracks,
            onSelectAudio = onSelectAudio,
            speed = playbackSpeed,
            onSpeed = onSpeed,
            scale = subScale,
            color = subColor,
            onScale = { subScale = it },
            onColor = { subColor = it },
            onDismiss = { showSubtitleSheet = false },
        )
    }
}

/**
 * The player's settings, in a small translucent panel in the middle of the
 * picture: subtitles, the audio track, playback speed, and the subtitle look.
 *
 * A panel about half the width, not a sheet across the bottom, so the film stays
 * visible around it rather than being covered; a tap outside puts it away.
 * Subtitles have a switch and, under it, the tracks -- each named by its number,
 * by whether it sits beside the film or inside it, and by its format and
 * language, the shown one marked. Audio lists the film's sound tracks, but only
 * for a film that carries more than one -- the double-audio case. Speed runs from
 * half to double. Size and colour hold for every film. The lists keep to media3's
 * own template: a heading, then radio rows.
 */
@Composable
private fun PlayerSettingsSheet(
    subtitleOn: Boolean,
    tracks: List<TextTrack>,
    onToggle: (Boolean) -> Unit,
    onSelectTrack: (TextTrack) -> Unit,
    audioTracks: List<AudioTrack>,
    onSelectAudio: (AudioTrack) -> Unit,
    speed: Float,
    onSpeed: (Float) -> Unit,
    scale: Float,
    color: Int,
    onScale: (Float) -> Unit,
    onColor: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // The panel takes the app's own theme -- ivory and clay in the light theme,
    // the warm dark in the dark one -- rather than a palette of its own, so it
    // matches the rest of the app. It is kept short of the screen and scrolls.
    val configuration = LocalConfiguration.current
    // Half the width in landscape -- where the film is wide and the panel should
    // stay out of it -- but most of the width in portrait, where half a phone is
    // too narrow to hold the speed pills without their text wrapping.
    val landscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val widthFraction = if (landscape) 0.5f else 0.9f
    // A fixed window, not one that grows and shrinks with its contents: a set
    // height for the orientation, the contents scrolling within it. So the panel
    // is the same size whatever film it opens over, and any spare room is even
    // padding rather than a panel that jumps in size.
    val panelHeight = (configuration.screenHeightDp * (if (landscape) 0.86f else 0.6f)).dp
    val backdrop = remember { MutableInteractionSource() }
    val panel = remember { MutableInteractionSource() }
    // The backdrop dims nothing of its own -- it is only a way to tap outside
    // and put the panel away -- so nothing but the panel is laid over the film.
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = backdrop, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(widthFraction)
                .widthIn(max = 560.dp)
                .height(panelHeight)
                .clip(RoundedCornerShape(16.dp))
                // A touch translucent so the film shows through, but mostly opaque
                // so the app's surface colour reads true in either theme.
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                    // Taps on the panel do their own work and never reach the
                    // backdrop, so touching it does not put it away.
                    .clickable(interactionSource = panel, indication = null, onClick = {})
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
                    .padding(top = 14.dp, bottom = 16.dp),
            ) {
            // Subtitles: the heading carries the on/off switch, then the tracks.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.section_subtitle),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Switch(checked = subtitleOn, onCheckedChange = onToggle)
            }
            tracks.forEach { track ->
                val source = stringResource(
                    if (track.external) R.string.subtitle_external else R.string.subtitle_internal,
                )
                TrackRow(
                    selected = track.selected,
                    onClick = { onSelectTrack(track) },
                    title = stringResource(R.string.subtitle_track_label, source, track.number),
                    detail = "${track.format} · ${track.language}",
                )
            }

            // Audio: only for a film with more than one track; a single one is
            // nothing to choose between.
            if (audioTracks.size > 1) {
                SettingsHeading(stringResource(R.string.section_audio))
                audioTracks.forEach { track ->
                    TrackRow(
                        selected = track.selected,
                        onClick = { onSelectAudio(track) },
                        title = stringResource(R.string.audio_track_label, track.number),
                        detail = "${track.detail} · ${track.language}",
                    )
                }
            }

            // Speed: pills from half to double, the playing one filled.
            SettingsHeading(stringResource(R.string.section_speed))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (option in PLAYBACK_SPEEDS) {
                    val chosen = kotlin.math.abs(option - speed) < 0.01f
                    val label = speedNumber(option) + "x"
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (chosen) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            )
                            .clickable { onSpeed(option) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            color = if (chosen) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
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

/** A heading over a group in the settings sheet, in the manner of media3's own. */
@Composable
private fun SettingsHeading(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

/** One track in a list: a radio, a title, and a quieter detail line under it. */
@Composable
private fun TrackRow(selected: Boolean, onClick: () -> Unit, title: String, detail: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.size(22.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                // Set explicitly: the panel is a Box, not a Material Surface, so
                // an unset text colour falls back to black and vanishes on the
                // dark panel -- which is why the track name could not be read.
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
 * Every supported track of [type] in the player's current selection, numbered
 * from one and turned into a row by [row]. Both the subtitle and the audio
 * pickers are built from this, so the walk over the track groups -- skipping the
 * unsupported ones and counting the rest -- is written once, not twice.
 */
private inline fun <T> mapTracksOfType(
    player: Player,
    type: Int,
    row: (group: Tracks.Group, trackIndex: Int, number: Int) -> T,
): List<T> = buildList {
    var number = 0
    for (group in player.currentTracks.groups.filter { it.type == type }) {
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            number++
            add(row(group, i, number))
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

/**
 * An audio track as the picker shows it: its place in the list, its language and
 * a short codec-and-channels detail, and whether it is the one playing. There to
 * choose between the audio tracks of a film that carries more than one.
 */
private data class AudioTrack(
    val group: Tracks.Group,
    val trackIndex: Int,
    val number: Int,
    val language: String,
    val detail: String,
    val selected: Boolean,
)

/** Selects [track]'s audio. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun applyAudioTrack(player: Player, track: AudioTrack) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex))
        .build()
}

/**
 * A short "codec · channels" line for an audio track -- AAC · STEREO, AC3 · 5.1
 * -- in the manner of media3's own track names, for telling two tracks apart.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun audioDetail(format: androidx.media3.common.Format): String {
    val codec = when (val mime = format.sampleMimeType) {
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "AC3"
        MimeTypes.AUDIO_E_AC3 -> "EAC3"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_OPUS -> "OPUS"
        MimeTypes.AUDIO_VORBIS -> "VORBIS"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        null -> null
        else -> mime.substringAfterLast('/').uppercase()
    }
    val channels = when (format.channelCount) {
        1 -> "MONO"
        2 -> "STEREO"
        6 -> "5.1"
        8 -> "7.1"
        androidx.media3.common.Format.NO_VALUE, 0 -> null
        else -> "${format.channelCount}ch"
    }
    return listOfNotNull(codec, channels).joinToString(" · ").ifEmpty { "AUDIO" }
}

// How far a pinch can size the picture: down to a fraction of its own size, so a
// film a camera notch cuts into can be shrunk clear of the notch, and up to four
// times it. One (its own size) sits between the two.
private const val MIN_VIDEO_SCALE = 0.4f
private const val MAX_VIDEO_SCALE = 4f

// The speeds a film can play at, normal in the middle.
private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** A speed as a label, dropping the ".0" on a whole one: "1", "1.5". */
private fun speedNumber(speed: Float): String =
    if (speed == speed.toLong().toFloat()) speed.toLong().toString() else speed.toString()

/**
 * A stable key for a track, for remembering which one a file was watched with.
 * The track's number is folded in so two internal tracks of the same language --
 * two English, say -- do not share a key and re-select each other's first match.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun subtitleToken(external: Boolean, format: androidx.media3.common.Format, number: Int): String =
    if (external) {
        format.id ?: "ext$number"
    } else {
        val name = format.language?.takeIf { it.isNotBlank() } ?: format.label ?: format.id ?: "int"
        "$name#$number"
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

// A one-finger drag is one of these for its whole length, fixed the moment it
// begins by the way it leans. Deciding once and holding it is what keeps a dial
// from turning into a scrub -- or the reverse -- when the finger wanders, and it
// is why a drag and the menu tap can never both fire.
private const val DRAG_NONE = 0
private const val DRAG_BRIGHTNESS = 1
private const val DRAG_VOLUME = 2
private const val DRAG_SEEK = 3

// How fast the brightness and volume dials move: a full sweep of either takes
// about a third of the height, rather than the whole of it, which felt sluggish.
private const val DIAL_SENSITIVITY = 3f

// A full sideways sweep scrubs two minutes.
private const val SEEK_SPAN_MS = 120_000f

/**
 * The video player's touch language, on one arbitrated pipeline over a full-screen
 * layer -- so shrinking the picture never shrinks where a gesture lands.
 *
 * The number of fingers decides the family and nothing crosses over. Two fingers
 * zoom; and once a second finger has touched down, the one-finger dials are held
 * off until every finger lifts, so releasing a pinch never lurches the brightness
 * or the scrub -- the interference that pinch-to-shrink brought. One finger, past a
 * small dead-zone, is a dial or a scrub, fixed the moment it crosses the threshold
 * and held to the end: a vertical drag is brightness on the left half and volume on
 * the right, a sideways drag scrubs. A touch that never crosses the threshold is
 * left unconsumed for [onShowControls]/play-pause -- the tap detector below.
 */
private fun Modifier.videoGestures(
    player: MediaController,
    onShowControls: () -> Unit,
    onBrightnessDelta: (Float) -> Unit,
    onVolumeDelta: (Float) -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    onScaleDelta: (Float) -> Unit,
    onGestureEnd: () -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var mode = DRAG_NONE
            var multiTouch = false
            val start = down.position
            var last = down.position
            var seekBase = 0L
            val slop = viewConfiguration.touchSlop
            val width = size.width.toFloat().coerceAtLeast(1f)
            val height = size.height.toFloat().coerceAtLeast(1f)
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    multiTouch = true
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) onScaleDelta(zoom)
                    event.changes.forEach { it.consume() }
                } else if (multiTouch) {
                    // One finger left after a pinch: swallow it so it starts no dial.
                    event.changes.forEach { it.consume() }
                } else {
                    val change = event.changes.firstOrNull { it.pressed }
                    if (change != null) {
                        val pos = change.position
                        if (mode == DRAG_NONE) {
                            val movedX = kotlin.math.abs(pos.x - start.x)
                            val movedY = kotlin.math.abs(pos.y - start.y)
                            if (movedX > slop || movedY > slop) {
                                mode = if (movedY >= movedX) {
                                    if (start.x < width / 2f) DRAG_BRIGHTNESS else DRAG_VOLUME
                                } else {
                                    DRAG_SEEK
                                }
                                if (mode == DRAG_SEEK) seekBase = player.currentPosition
                            }
                        }
                        when (mode) {
                            DRAG_BRIGHTNESS -> {
                                onBrightnessDelta((last.y - pos.y) / height * DIAL_SENSITIVITY)
                                change.consume()
                            }
                            DRAG_VOLUME -> {
                                onVolumeDelta((last.y - pos.y) / height * DIAL_SENSITIVITY)
                                change.consume()
                            }
                            DRAG_SEEK -> {
                                val duration = player.duration
                                if (duration > 0) {
                                    val delta = ((pos.x - start.x) / width * SEEK_SPAN_MS).toLong()
                                    onSeekPreview((seekBase + delta).coerceIn(0L, duration))
                                }
                                change.consume()
                            }
                            else -> Unit
                        }
                        last = pos
                    }
                }
                if (event.changes.all { !it.pressed }) break
            }
            if (mode == DRAG_SEEK) onSeekCommit()
            onGestureEnd()
        }
    }
    .pointerInput(Unit) {
        detectTapGestures(
            onTap = { onShowControls() },
            onDoubleTap = { if (player.isPlaying) player.pause() else player.play() },
        )
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
private const val EXTERNAL_SUB_ID_PREFIX = "olo-ext:"

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
