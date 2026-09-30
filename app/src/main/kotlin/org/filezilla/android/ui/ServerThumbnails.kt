package org.filezilla.android.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.filezilla.android.data.SiteEntity
import java.io.File
import java.util.UUID

/**
 * Thumbnails for pictures and songs on a server.
 *
 * A server file has nothing local to decode, so its row keeps the kind tile
 * -- which for a folder of photos means every row is the same green square.
 * This fetches a small preview into a scratch file, decodes it the same way
 * the local thumbnails are decoded, caches the result, and throws the scratch
 * file away. The cost is a download per picture, so three things hold it in
 * check: it is tried only for the file kinds that carry a picture and only up
 * to [MAX_FETCH_BYTES], no more than [MAX_CONCURRENT] run at once, and it is
 * held behind the unmetered-only switch by default so a folder of photos is
 * not a surprise run of downloads on mobile data.
 *
 * Video is left out on purpose (a frame needs most of the file fetched and
 * then decoded), as is anything the [enabled] switch turns off.
 *
 * The fetch is a seam: the app wires it to [org.filezilla.android.transfer.TransferManager.fetchForViewing],
 * and a test hands one that writes a picture straight into the scratch file,
 * so the decode-and-cache pipeline can be driven without a server.
 */
class ServerThumbnails(
    private val cacheDir: File,
    /** The preference switch: whether server thumbnails are wanted at all. */
    private val enabled: () -> Boolean,
    /** Whether the connection right now allows a fetch (the Wi-Fi-only rule). */
    private val allowedNow: () -> Boolean,
    /**
     * Fetches [remotePath] on [site] into the scratch file; may be cancelled.
     *
     * [maxBytes] null means the whole file (a picture or song). A limit means
     * fetch only that much and stop -- the video prefix, enough for a frame in
     * a faststart file without paying to download a 128 MB clip.
     */
    private val fetch: suspend (site: SiteEntity, remotePath: String, into: File, maxBytes: Long?) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // Stored for a file with no picture, so a second look does not fetch and
    // decode it again only to find nothing again.
    private val miss = Any()

    private val cache = object : LruCache<String, Any>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Any): Int =
            if (value is ImageBitmap) value.width * value.height * 4 else 1
    }

    // Caps how many previews come down at once: a screen of thirty photos must
    // not open thirty logins, which a server would answer with 421 rather than
    // more speed.
    private val fetches = Semaphore(MAX_CONCURRENT)

    /** Whether a server row of this kind and size is worth a thumbnail. */
    fun eligible(kind: FileKind, sizeBytes: Long): Boolean =
        serverThumbEligible(enabled(), kind, sizeBytes)

    /**
     * The thumbnail for a server file, or null when there is none, it is too
     * big, thumbnails are off, or the connection is not one they may use.
     *
     * Cancellable: when the row leaves the screen the coroutine driving this is
     * cancelled, which interrupts the fetch rather than paying to finish a
     * download nobody is waiting for.
     */
    suspend fun load(
        site: SiteEntity,
        remotePath: String,
        modifiedToken: String,
        sizeBytes: Long,
        kind: FileKind,
        sizePx: Int,
    ): ImageBitmap? {
        if (!eligible(kind, sizeBytes)) return null
        val key = "${site.host}:${site.port}:${site.user}|$remotePath|$modifiedToken|$sizePx"
        cache.get(key)?.let { return it as? ImageBitmap }
        if (!allowedNow()) return null
        return fetches.withPermit {
            // Another row may have fetched the same file while this one waited
            // for a permit; and the network may have changed while it waited.
            cache.get(key)?.let { return@withPermit it as? ImageBitmap }
            if (!allowedNow()) return@withPermit null
            cacheDir.mkdirs()
            val scratch = File(cacheDir, "srvthumb-${UUID.randomUUID()}.tmp")
            // A video is fetched only up to its prefix; a picture or song whole.
            val maxBytes = if (kind == FileKind.VIDEO) VIDEO_PREFIX_BYTES else null
            try {
                try {
                    fetch(site, remotePath, scratch, maxBytes)
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    // A fetch that failed -- offline, a refused login, a file
                    // that has gone -- is not a permanent miss: leaving it
                    // uncached lets a later look try again rather than showing
                    // the kind tile for good.
                    return@withPermit null
                }
                val bitmap = withContext(dispatcher) {
                    Thumbnails.decodeUncached(scratch, kind, sizePx)?.asImageBitmap()
                }
                cache.put(key, bitmap ?: miss)
                bitmap
            } finally {
                scratch.delete()
            }
        }
    }

    companion object {
        /** How many previews may be fetched at once; see FileZilla's own default of two. */
        const val MAX_CONCURRENT = 2

        /**
         * The biggest file a preview is fetched for.
         *
         * Big enough for a phone photo, small enough that opening a folder of
         * them is not a surprise download: a preview is the whole file over
         * FTP, so the cap is the whole of the data-cost protection the size
         * gives.
         */
        const val MAX_FETCH_BYTES = 16L * 1024 * 1024

        /**
         * How much of a video is fetched for a frame.
         *
         * Only the prefix, not the whole clip: a faststart file (moov at the
         * front, which phones and web exports write) has its first frames near
         * the start, so a few megabytes is usually enough. A file whose moov is
         * at the end simply will not decode from this and keeps its kind tile --
         * best-effort, but at a bounded cost rather than a 128 MB download.
         */
        const val VIDEO_PREFIX_BYTES = 8L * 1024 * 1024

        // A few megabytes of decoded previews, sized by their real cost.
        private const val CACHE_BYTES = 12 * 1024 * 1024
    }
}

/**
 * Whether a server row of this kind and size is worth a thumbnail.
 *
 * Top-level and tested: the cap is the only thing standing between "show the
 * photos in this folder" and "download every file in it", so getting it wrong
 * in the lenient direction spends the user's data. A size of -1 is a server
 * that did not say, which is allowed -- most report a size, and a picture is
 * usually small.
 */
fun serverThumbEligible(enabled: Boolean, kind: FileKind, sizeBytes: Long): Boolean {
    if (!enabled) return false
    return when (kind) {
        // A whole picture or song is fetched, so its size is the ceiling.
        FileKind.IMAGE, FileKind.AUDIO -> sizeBytes < 0 || sizeBytes <= ServerThumbnails.MAX_FETCH_BYTES
        // Only the first few megabytes of a video are fetched (enough for a
        // frame in a faststart file), so the file's own size is not the limit.
        FileKind.VIDEO -> true
        else -> false
    }
}

/**
 * Whether the connection now allows a thumbnail fetch.
 *
 * The Wi-Fi-only rule, apart from the platform so it can be read at a glance
 * and tested: off, anything goes; on, only an unmetered connection.
 */
fun serverThumbAllowed(wifiOnly: Boolean, unmetered: Boolean): Boolean = !wifiOnly || unmetered

/** Where a server thumbnail is in its life: still coming, or here (or absent). */
sealed interface ServerThumbState {
    object Loading : ServerThumbState
    data class Ready(val bitmap: ImageBitmap?) : ServerThumbState
}

/**
 * Fetches and decodes a server thumbnail for the current composition, keyed so
 * a scroll does not restart one that is already in hand and a changed file
 * starts a fresh one. Cancels with the composition -- a row scrolled away
 * stops fetching.
 */
@Composable
fun rememberServerThumb(
    thumbs: ServerThumbnails,
    site: SiteEntity,
    remotePath: String,
    modifiedToken: String,
    sizeBytes: Long,
    kind: FileKind,
    sizePx: Int,
): ServerThumbState {
    val state by produceState<ServerThumbState>(
        ServerThumbState.Loading, remotePath, modifiedToken, sizePx,
    ) {
        value = ServerThumbState.Ready(
            thumbs.load(site, remotePath, modifiedToken, sizeBytes, kind, sizePx),
        )
    }
    return state
}

/**
 * A file row's icon as a server thumbnail: a loading tile while it comes, the
 * decoded picture once it is here, and the kind tile if there is none.
 */
@Composable
fun ServerThumb(
    thumbs: ServerThumbnails,
    site: SiteEntity,
    remotePath: String,
    modifiedToken: String,
    sizeBytes: Long,
    kind: FileKind,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    cornerRadius: Dp = 12.dp,
) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    when (val state = rememberServerThumb(thumbs, site, remotePath, modifiedToken, sizeBytes, kind, px)) {
        ServerThumbState.Loading -> ServerLoadingTile(modifier, size, cornerRadius)
        is ServerThumbState.Ready -> {
            val bitmap = state.bitmap
            if (bitmap != null) {
                ThumbImage(bitmap, kind, contentDescription, modifier, size, cornerRadius)
            } else {
                FileTile(kind, colourFor(kind), contentDescription, modifier, size, cornerRadius)
            }
        }
    }
}

/**
 * The neutral placeholder shown while a server thumbnail is on its way.
 *
 * A surface-coloured tile with a spinner, deliberately not the kind tile: the
 * kind tile is what a file with no picture ends up showing, and a loading
 * state that looked the same as the final one would say the fetch had finished
 * when it had not.
 */
@Composable
fun ServerLoadingTile(modifier: Modifier = Modifier, size: Dp = 40.dp, cornerRadius: Dp = 12.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(size * 0.5f),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * A decoded thumbnail at a fixed tile size, with a small play badge in the
 * corner when it is a video.
 *
 * The badge is what tells a video thumbnail from a picture at a glance -- in a
 * grid or a list a frame otherwise reads as a still. Shared by the local
 * ([EntryThumb]) and server ([ServerThumb]) paths so both mark video the same.
 */
@Composable
fun ThumbImage(
    bitmap: ImageBitmap,
    kind: FileKind,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    cornerRadius: Dp = 12.dp,
) {
    Box(modifier = modifier.size(size).clip(RoundedCornerShape(cornerRadius))) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (kind == FileKind.VIDEO) {
            Box(Modifier.fillMaxSize().padding(2.dp), contentAlignment = Alignment.BottomEnd) {
                VideoPlayBadge(size * 0.4f)
            }
        }
    }
}

/**
 * A thumbnail filling a gallery square, with a centred play button when it is a
 * video. A gallery cell has no name beside it, so the button is what says a
 * square is a film rather than a photo.
 */
@Composable
fun GalleryThumbImage(bitmap: ImageBitmap, kind: FileKind, contentDescription: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (kind == FileKind.VIDEO) VideoPlayBadgeCentre()
    }
}

/** A small round play badge for a corner: a white ▶ on a dark disc. */
@Composable
fun VideoPlayBadge(size: Dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        // Decorative: the row or the file name already says what this is, so no
        // description -- which also keeps it out of the one-glyph-one-verb list.
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.fillMaxSize(0.7f),
        )
    }
}

/** The gallery's centred play button, sized to the cell. */
@Composable
private fun VideoPlayBadgeCentre() {
    Box(
        modifier = Modifier.fillMaxWidth(0.30f).aspectRatio(1f).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.fillMaxSize(0.66f),
        )
    }
}
