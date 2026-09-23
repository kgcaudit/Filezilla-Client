package org.filezilla.android.playback

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * The media player's engine, living in a service so it outlasts the screen.
 *
 * The player used to be built inside the viewer and released when the viewer
 * closed, which meant it stopped the moment the app went to the background and
 * never had a notification. Held here instead, the sound carries on when the
 * phone is put down and media3 posts the playback notification with its
 * controls for free. The viewer connects to this from the front with a
 * MediaController and hands it a playlist; nothing else talks to it.
 *
 * The player also takes and holds audio focus, and pauses when the headphones
 * are pulled out -- the two things a listener expects of anything that plays a
 * sound and neither of which it did before.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    // One thread for pulling a poster frame out of the film for the notification,
    // off the main thread; shut down with the service.
    private val thumbnailExecutor = Executors.newSingleThreadExecutor()

    private companion object {
        const val SEEK_STEP_MS = 10_000L
        // The long edge of the poster frame; small enough to stay light in a
        // notification, large enough not to look coarse.
        const val THUMBNAIL_MAX_EDGE = 640
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            // Subtitles are parsed the default way (during extraction), which
            // matters most because a subtitle that fails to load is then
            // non-fatal -- the film still plays. Turning it off made a bad
            // subtitle take the whole film down with it. The format name and
            // the external/internal mark are recovered in the picker instead
            // (see the media viewer), so nothing is lost by keeping the default.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // The side buttons jump ten seconds back and on, rather than to the
            // previous or next file: this is what makes the controls show a
            // rewind and a fast-forward.
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()
        session = MediaSession.Builder(this, player)
            .setCallback(RestoringCallback())
            .build()
        // The playback notification carries play/pause alone. media3's default
        // draws a skip-to-previous and a skip-to-next around it, but the side
        // buttons here move within one film, not between films, so between-file
        // skips have no place on the notification -- only the previous button was
        // showing anyway, and it did nothing a listener would expect.
        setMediaNotificationProvider(PlayPauseOnlyNotificationProvider(this))
        // The notification shows a still frame of the film as its artwork -- a
        // poster, not live video, which a notification cannot play. The frame is
        // pulled off the thread and attached to the current item's metadata once
        // per item; media3 then redraws the notification with it.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                attachArtwork(mediaItem)
            }
        })
        attachArtwork(player.currentMediaItem)
    }

    /**
     * Pulls a poster frame from [item]'s film on a background thread and attaches
     * it to the item's metadata, so the notification has a picture to show. Does
     * nothing when the item already has artwork, and, back on the main thread,
     * only applies the frame if that same item is still the current one -- so a
     * quick change of film does not stamp one film's frame onto another. The item
     * is rebuilt whole (uri and subtitles kept), only its artwork added.
     */
    private fun attachArtwork(item: MediaItem?) {
        item ?: return
        if (item.mediaMetadata.artworkData != null) return
        val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri ?: return
        val player = session?.player ?: return
        val index = player.currentMediaItemIndex
        thumbnailExecutor.execute {
            val bytes = frameBytes(uri) ?: return@execute
            ContextCompat.getMainExecutor(this).execute {
                val p = session?.player ?: return@execute
                if (p.currentMediaItemIndex == index &&
                    p.currentMediaItem === item &&
                    item.mediaMetadata.artworkData == null
                ) {
                    p.replaceMediaItem(
                        index,
                        item.buildUpon()
                            .setMediaMetadata(
                                item.mediaMetadata.buildUpon()
                                    .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                    .build(),
                            )
                            .build(),
                    )
                }
            }
        }
    }

    /** A representative frame of the film at [uri] as JPEG bytes, or null. */
    private fun frameBytes(uri: Uri): ByteArray? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val frame = retriever.getFrameAtTime(-1)
            if (frame == null) {
                null
            } else {
                val scaled = scaleDown(frame, THUMBNAIL_MAX_EDGE)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                if (scaled !== frame) scaled.recycle()
                frame.recycle()
                out.toByteArray()
            }
        } finally {
            retriever.release()
        }
    }.getOrNull()

    /** [bitmap] shrunk so its longer edge is at most [maxEdge], or itself if already smaller. */
    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= maxEdge || longEdge == 0) return bitmap
        val ratio = maxEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * media3's notification with only its play/pause button kept -- the
     * skip-to-previous and skip-to-next it adds are dropped.
     */
    @UnstableApi
    private class PlayPauseOnlyNotificationProvider(context: android.content.Context) :
        DefaultMediaNotificationProvider(context) {
        override fun getMediaButtons(
            session: MediaSession,
            playerCommands: Player.Commands,
            customLayout: ImmutableList<CommandButton>,
            showPauseButton: Boolean,
        ): ImmutableList<CommandButton> =
            ImmutableList.copyOf(
                super.getMediaButtons(session, playerCommands, customLayout, showPauseButton)
                    .filter { it.playerCommand == Player.COMMAND_PLAY_PAUSE },
            )
    }

    /**
     * Puts back what the controller drops in transit -- the uri, from the
     * request metadata, and the subtitle files, from the metadata extras -- so
     * the service's player receives a whole MediaItem rather than a hollow one.
     *
     * An item that still arrives whole (its uri and subtitles intact) is left
     * exactly as it is. Only when the subtitles went missing are they put back
     * from the extras, and only when the uri went missing is it put back from
     * the request metadata -- so a rebuild never wipes subtitles that were
     * already there, which is how they came to vanish before.
     */
    @UnstableApi
    private class RestoringCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val restored = mediaItems.map { item ->
                val local = item.localConfiguration
                if (local != null && local.subtitleConfigurations.isNotEmpty()) {
                    // Whole already -- uri and subtitles both present.
                    item
                } else {
                    val uri = local?.uri ?: item.requestMetadata.mediaUri
                    if (uri == null) {
                        item
                    } else {
                        item.buildUpon()
                            .setUri(uri)
                            .setSubtitleConfigurations(
                                SubtitleBundle.decode(item.mediaMetadata.extras),
                            )
                            .build()
                    }
                }
            }.toMutableList()
            return Futures.immediateFuture(restored)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Swiping the app away with nothing playing (or paused) should not leave a
    // silent service and its notification behind; a running one is left alone
    // so the sound survives the swipe.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        thumbnailExecutor.shutdown()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
