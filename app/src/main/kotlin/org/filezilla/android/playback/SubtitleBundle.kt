package org.filezilla.android.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi

/**
 * Subtitle tracks, carried through the media session by hand.
 *
 * A MediaController hands the session only the bones of a MediaItem -- its id
 * and metadata -- and drops the rest, the uri and the subtitle files among it,
 * on the way across. The uri is put back from the request metadata; the
 * subtitles have nowhere of their own to ride, so they are packed into the
 * metadata's extras here and unpacked in the service. Without this the player
 * would get a film with no sound file and no subtitles -- and, before it, only
 * a film's own built-in tracks ever showed, never a subtitle sitting beside it.
 */
@UnstableApi
object SubtitleBundle {

    private const val KEY_URIS = "olo_sub_uris"
    private const val KEY_MIMES = "olo_sub_mimes"
    private const val KEY_LANGS = "olo_sub_langs"
    private const val KEY_FLAGS = "olo_sub_flags"
    private const val KEY_LABELS = "olo_sub_labels"
    private const val KEY_IDS = "olo_sub_ids"

    /** Packs a subtitle list into a bundle for a MediaItem's metadata extras. */
    fun encode(subtitles: List<MediaItem.SubtitleConfiguration>): Bundle {
        val bundle = Bundle()
        bundle.putStringArray(KEY_URIS, subtitles.map { it.uri.toString() }.toTypedArray())
        bundle.putStringArray(KEY_MIMES, subtitles.map { it.mimeType.orEmpty() }.toTypedArray())
        bundle.putStringArray(KEY_LANGS, subtitles.map { it.language.orEmpty() }.toTypedArray())
        bundle.putIntArray(KEY_FLAGS, subtitles.map { it.selectionFlags }.toIntArray())
        bundle.putStringArray(KEY_LABELS, subtitles.map { it.label.orEmpty() }.toTypedArray())
        bundle.putStringArray(KEY_IDS, subtitles.map { it.id.orEmpty() }.toTypedArray())
        return bundle
    }

    /** Rebuilds the subtitle list from a MediaItem's metadata extras. */
    fun decode(extras: Bundle?): List<MediaItem.SubtitleConfiguration> {
        val uris = extras?.getStringArray(KEY_URIS) ?: return emptyList()
        val mimes = extras.getStringArray(KEY_MIMES) ?: emptyArray()
        val langs = extras.getStringArray(KEY_LANGS) ?: emptyArray()
        val flags = extras.getIntArray(KEY_FLAGS) ?: IntArray(0)
        val labels = extras.getStringArray(KEY_LABELS) ?: emptyArray()
        val ids = extras.getStringArray(KEY_IDS) ?: emptyArray()
        return uris.mapIndexed { i, uri ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri))
                .setMimeType(mimes.getOrNull(i)?.ifEmpty { null })
                .setLanguage(langs.getOrNull(i)?.ifEmpty { null })
                .setSelectionFlags(flags.getOrElse(i) { 0 })
                .setLabel(labels.getOrNull(i)?.ifEmpty { null })
                .setId(ids.getOrNull(i)?.ifEmpty { null })
                .build()
        }
    }
}
