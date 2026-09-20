package org.filezilla.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Below this a list is a couple of flicks, and a rail is clutter. */
const val FAST_SCROLL_FROM = 20

/** The lane the rail sits in, which the list leaves clear for it. */
val FAST_SCROLL_WIDTH = 28.dp

/** How tall one letter is allowed to be, which is what decides how many fit. */
private val LABEL_HEIGHT = 17.dp

/**
 * The index down the right edge of a long list.
 *
 * A folder with three thousand films in it has no shape from the inside: the
 * rows go past, nothing says how far there is left, and reaching the ㅅs is a
 * drag and a guess. The rail gives the list a length you can see and a place
 * to put your thumb -- ㄱ ㄴ ㄷ ㄹ, or A B C, or whatever the rows actually
 * begin with, since it is built from them rather than from a fixed alphabet.
 *
 * The letter under the list's top row is picked out, so the rail doubles as
 * the position indicator the scroll bar never was.
 *
 * A list with no order worth indexing -- a transfer queue, which is in the
 * order things were queued -- passes no [stops] and gets a plain thumb, which
 * still says where in the list you are and still drags.
 */
@Composable
fun FastScroller(
    state: LazyListState,
    rowCount: Int,
    stops: List<ScrollIndex.Stop>,
    modifier: Modifier = Modifier,
) {
    if (rowCount < FAST_SCROLL_FROM) return

    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var pointerY by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier.fillMaxHeight().width(FAST_SCROLL_WIDTH)) {
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val fits = with(androidx.compose.ui.platform.LocalDensity.current) {
            (heightPx / LABEL_HEIGHT.toPx()).toInt()
        }
        val rail = remember(stops, fits) { ScrollIndex.thin(stops, fits) }

        fun jumpTo(y: Float) {
            val fraction = (y / heightPx).coerceIn(0f, 1f)
            val row = if (rail.isEmpty()) {
                (fraction * (rowCount - 1)).roundToInt()
            } else {
                rail[((fraction * rail.size).toInt()).coerceIn(0, rail.size - 1)].row
            }
            scope.launch { state.scrollToItem(row.coerceIn(0, rowCount - 1)) }
        }

        val touch = Modifier
            .pointerInput(rail, rowCount) {
                detectVerticalDragGestures(
                    onDragStart = { at ->
                        dragging = true
                        pointerY = at.y
                        jumpTo(at.y)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    pointerY = change.position.y
                    jumpTo(change.position.y)
                }
            }
            // A tap is not a drag, and tapping a letter is the whole point of
            // having letters. Without this the rail only answers to dragging.
            .pointerInput(rail, rowCount) {
                detectTapGestures { at -> jumpTo(at.y) }
            }

        if (rail.isEmpty()) {
            PlainThumb(state = state, rowCount = rowCount, modifier = touch)
        } else {
            LetterRail(rail = rail, state = state, modifier = touch)
        }

        if (dragging) {
            val label = if (rail.isEmpty()) {
                null
            } else {
                rail[(((pointerY / heightPx).coerceIn(0f, 1f) * rail.size).toInt())
                    .coerceIn(0, rail.size - 1)].label
            }
            label?.let { text ->
                val lift = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.roundToPx() }
                val left = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.roundToPx() }
                Bubble(
                    label = text,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(x = -left, y = pointerY.roundToInt() - lift) },
                )
            }
        }
    }
}

/** The letters, evenly spread, with the one you are looking at picked out. */
@Composable
private fun LetterRail(
    rail: List<ScrollIndex.Stop>,
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    // The last letter at or above the top row: which part of the list is on
    // screen, said in the rail's own terms.
    val here = remember(rail, state.firstVisibleItemIndex) {
        rail.indexOfLast { it.row <= state.firstVisibleItemIndex }.coerceAtLeast(0)
    }

    Column(
        modifier = modifier.fillMaxHeight().fillMaxWidth(),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        rail.forEachIndexed { i, stop ->
            val current = i == here
            Text(
                stop.label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                color = if (current) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                },
            )
        }
    }
}

/** For a list whose order no letter describes. */
@Composable
private fun PlainThumb(state: LazyListState, rowCount: Int, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxHeight().fillMaxWidth()) {
        val travel = constraints.maxHeight - with(androidx.compose.ui.platform.LocalDensity.current) {
            THUMB_HEIGHT.toPx()
        }
        val fraction = if (rowCount <= 1) 0f else {
            (state.firstVisibleItemIndex.toFloat() / (rowCount - 1)).coerceIn(0f, 1f)
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, (fraction * travel).roundToInt()) }
                .width(5.dp)
                .height(THUMB_HEIGHT)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
        )
    }
}

private val THUMB_HEIGHT = 44.dp

/** What is under the finger, big enough to read past the finger. */
@Composable
private fun Bubble(label: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
