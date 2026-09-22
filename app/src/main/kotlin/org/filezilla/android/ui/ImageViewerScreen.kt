package org.filezilla.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import org.filezilla.android.R

/**
 * The app's own image viewer: one picture at a time, pinch to zoom, and a
 * swipe to the next.
 *
 * The swipe is the point of it. A folder of photos, or the pages of a comic
 * still inside its archive, become something to page through rather than a
 * row of taps back and forth to the list. Each page is decoded only as it is
 * reached and shrunk to the screen on the way, so a hundred-page cbz costs no
 * more memory than the few pages on either side of the one being read.
 */
@Composable
fun ImageViewerScreen(viewer: MainViewModel.ImageViewer, model: MainViewModel) {
    val pager = rememberPagerState(initialPage = viewer.index, pageCount = { viewer.images.size })

    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { model.setImageIndex(it) }
    }

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableImage(viewer.images[page], model)
            }

            // A thin bar over the picture: the way back, the name, the place
            // in the set. Dark so it reads on any image under it.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = model::closeImageViewer) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close), tint = Color.White)
                }
                Text(
                    viewer.images.getOrNull(pager.currentPage)?.name.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                if (viewer.images.size > 1) {
                    Text(
                        "${pager.currentPage + 1} / ${viewer.images.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(ref: MainViewModel.ImageRef, model: MainViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val reqWidth = constraints.maxWidth
        val reqHeight = constraints.maxHeight
        var bitmap by remember(ref) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(ref) { mutableStateOf(false) }

        LaunchedEffect(ref, reqWidth, reqHeight) {
            val decoded = model.loadImage(ref, reqWidth, reqHeight)
            if (decoded == null) failed = true else bitmap = decoded
        }

        var scale by remember(ref) { mutableStateOf(1f) }
        var offsetX by remember(ref) { mutableStateOf(0f) }
        var offsetY by remember(ref) { mutableStateOf(0f) }
        val transform = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 6f)
            // Pan only has somewhere to go once zoomed in; at rest the picture
            // stays put and the swipe belongs to the pager.
            if (scale > 1f) {
                offsetX += pan.x
                offsetY += pan.y
            } else {
                offsetX = 0f
                offsetY = 0f
            }
        }

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
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    )
                    .transformable(transform)
                    .pointerInput(ref) {
                        detectTapGestures(onDoubleTap = {
                            // Double-tap toggles between fit and a close look.
                            if (scale > 1f) {
                                scale = 1f; offsetX = 0f; offsetY = 0f
                            } else {
                                scale = 3f
                            }
                        })
                    },
            )
        }
    }
}
