package org.filezilla.android.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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

    // Whether the file now playing is a sound rather than a film. It decides
    // which controls the session offers: a film moves ten seconds at a time and
    // withholds the between-file skip (see onConnect), a song is a music player
    // and keeps skip-to-previous and skip-to-next so the notification and the
    // lock screen carry them. Starts on the film's side -- the stricter one --
    // until a loaded item says otherwise.
    private var currentIsAudio = false

    private companion object {
        const val SEEK_STEP_MS = 10_000L

        // The sound extensions, mirroring the file list's own (FileKind): what
        // opens as a song rather than a film, and so gets the music controls.
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus")
    }

    /** Whether [item] is a sound, read from its file extension. */
    private fun isAudioItem(item: MediaItem?): Boolean {
        val uri = item?.localConfiguration?.uri ?: item?.requestMetadata?.mediaUri ?: return false
        val ext = (uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    // The full set, and the film's set with the between-file skips taken out.
    @UnstableApi
    private fun fullPlayerCommands(): Player.Commands =
        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS

    @UnstableApi
    private fun videoPlayerCommands(): Player.Commands =
        fullPlayerCommands().buildUpon()
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_NEXT)
            .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .build()

    /**
     * Grants or withholds the between-file skip to every connected controller --
     * the app's own, and the internal one the notification draws from -- to match
     * the kind of the item now playing. Called whenever the playing item changes,
     * so opening a song turns the skip on and opening a film turns it off.
     */
    @UnstableApi
    private fun applyCommandsFor(item: MediaItem?) {
        currentIsAudio = isAudioItem(item)
        val session = session ?: return
        val commands = if (currentIsAudio) fullPlayerCommands() else videoPlayerCommands()
        for (controller in session.connectedControllers) {
            session.setAvailableCommands(
                controller,
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
                commands,
            )
        }
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
        // The controls a song and a film offer differ, so the session watches
        // which is playing and moves the between-file skip on and off to suit.
        player.addListener(object : Player.Listener {
            @UnstableApi
            override fun onEvents(target: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_TIMELINE_CHANGED,
                    )
                ) {
                    applyCommandsFor(target.currentMediaItem)
                }
            }
        })
        // The playback notification carries play/pause alone. media3's default
        // draws a skip-to-previous and a skip-to-next around it, but the side
        // buttons here move within one film, not between films, so between-file
        // skips have no place on the notification -- only the previous button was
        // showing anyway, and it did nothing a listener would expect.
        setMediaNotificationProvider(PlayPauseOnlyNotificationProvider(this))
    }

    /**
     * media3's notification, kept to the buttons that fit what is playing.
     *
     * A film carries play/pause alone: its side buttons move ten seconds, not
     * between files, so a skip has no place on the notification. A song is a
     * music player and keeps its skip-to-previous and skip-to-next around the
     * play button. The two are told apart by the commands the session grants
     * (see applyCommandsFor) -- a film has no skip command, so the skip buttons
     * are never generated for it and the filter has nothing to drop; a song has
     * them, and they are kept.
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
                    .filter {
                        it.playerCommand == Player.COMMAND_PLAY_PAUSE ||
                            it.playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS ||
                            it.playerCommand == Player.COMMAND_SEEK_TO_NEXT
                    },
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
    private inner class RestoringCallback : MediaSession.Callback {
        // The system's own media controls -- the lock screen and the notification
        // panel's player on Samsung and Android 13+ -- draw their buttons from the
        // commands the session advertises, not from the notification layout. So
        // the skip-to-previous and skip-to-next commands are withheld here: the
        // side controls move ten seconds within one film, not between files, and a
        // between-file skip has no place there. Rewind and fast-forward are their
        // own commands (seek back and forward) and are untouched, and a film still
        // runs on to the next on its own -- that is the player's doing, not a
        // command a controller sends.
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // The song grants the skip, the film withholds it; a controller that
            // connects before anything is loaded takes the film's stricter set,
            // and applyCommandsFor moves it the moment an item begins.
            val playerCommands = if (currentIsAudio) fullPlayerCommands() else videoPlayerCommands()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

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
