package org.filezilla.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * How long the pull indicator stays up once it is up.
 *
 * Long enough to be seen as an answer to the pull, short enough that a fast
 * listing does not feel padded.
 */
const val REFRESH_SHOWN_FOR = 450L

/**
 * Whether the pull-to-refresh indicator should be spinning.
 *
 * Driven by the pull rather than by whether the pane is loading, which is
 * the whole point. `PullToRefreshBox` animates its indicator home in an
 * effect keyed on `isRefreshing`: when that flag goes true it settles the
 * indicator at the spinning position, and when it goes false it animates it
 * away. If the flag never becomes true the key never changes, so nothing
 * ever runs the animation away -- and the indicator sits wherever the finger
 * left it, not spinning, for the rest of the session.
 *
 * Which is what happened, twice. Listing a folder on the phone is a readdir
 * over a warm cache; the view model sets loading, does the work and clears
 * loading between two frames, so the screen only ever recomposes with
 * loading already false. The previous attempt at this held the loading flag
 * true for a minimum once it had been seen true -- and it was never seen
 * true, so it held nothing.
 *
 * [pulls] is a count, not a flag: two pulls in a row have to read as two
 * events, and a flag that is already true says nothing happened.
 */
@Composable
fun refreshIndicatorShown(
    pulls: Long,
    loading: Boolean,
    minimumMillis: Long = REFRESH_SHOWN_FOR,
): Boolean {
    var shown by remember { mutableStateOf(false) }
    val busy by rememberUpdatedState(loading)

    LaunchedEffect(pulls) {
        // Nothing has been pulled yet; the screen opening is not a refresh.
        if (pulls == 0L) return@LaunchedEffect

        shown = true
        val since = System.currentTimeMillis()

        // Wait for the listing, if there is one to wait for. When the work
        // was over before this ran -- the common case on the phone's own
        // storage -- this returns at once and only the minimum is left.
        snapshotFlow { busy }.first { !it }

        val left = minimumMillis - (System.currentTimeMillis() - since)
        if (left > 0) delay(left)
        shown = false
    }
    return shown
}
