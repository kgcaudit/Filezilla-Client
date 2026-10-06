package org.filezilla.android.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * The top strip the phone keeps for its status bar and camera cutout, as a
 * padding a full-screen viewer sits below so a centre-punch camera does not
 * bite a hole out of the picture once the bar is hidden.
 *
 * Latched at the largest inset seen: hiding the status bar drops its own inset
 * to zero, so without the latch the page would jump up under the notch. A
 * plain [remember], not `rememberSaveable` -- the latch has to start over on a
 * rotation, or a tall portrait notch leaves an over-tall bar in landscape.
 * That is the bug the image viewer had while it kept its own saved copy of
 * this; the media viewer's correct version and the image viewer's are now one.
 */
@Composable
fun rememberReservedTopInset(): Dp {
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    val cutoutTop = WindowInsets.displayCutout.getTop(density)
    var reservedTopPx by remember { mutableIntStateOf(0) }
    val reservedTop = maxOf(reservedTopPx, statusTop, cutoutTop)
    LaunchedEffect(reservedTop) { reservedTopPx = reservedTop }
    return with(density) { reservedTop.toDp() }
}
