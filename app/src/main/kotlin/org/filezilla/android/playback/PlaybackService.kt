package org.filezilla.android.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

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

    private companion object {
        const val SEEK_STEP_MS = 10_000L
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
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
