package org.filezilla.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.filezilla.android.R
import kotlin.math.roundToInt

/**
 * The round badge that shows a copy inside the phone running, in the corner the
 * new-thing button sits in.
 *
 * It takes that button's place rather than crowding a second circle in beside
 * it: a copy was the one transfer nothing watched -- the queue at the foot of
 * the screen is fed by the network transfer journal, so a local copy left the
 * count at zero and a bar sweeping left to right forever, which says "working"
 * and never "how far". This says how far. The same size and the same corner as
 * the button it replaces, so it reads as that button having become the work
 * rather than as something new appearing over it; while it is up, making a new
 * folder waits, which is a fair price for not stacking two controls in one
 * spot.
 *
 * Three faces: a sweep and an ellipsis while the total is still being summed, a
 * filling ring and a percentage once there is a total to measure against, and a
 * full ring with a tick for the moment at the end. The percentage is drawn in
 * [onSurface][androidx.compose.material3.ColorScheme.onSurface], not the brand
 * clay: clay on the pale container misses the contrast a small label needs.
 */
@Composable
fun CopyProgressBadge(
    progress: LocalPasteProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = progress.fraction
    val percent = fraction?.let { (it * 100).roundToInt() }
    val description = when {
        progress.done -> stringResource(R.string.copy_badge_done)
        percent != null -> stringResource(R.string.copy_badge_progress, percent)
        else -> stringResource(R.string.copy_badge_preparing)
    }
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
        modifier = modifier
            .size(56.dp)
            .semantics { this.contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            val ring = Modifier.size(44.dp)
            val arc = MaterialTheme.colorScheme.primary
            val track = MaterialTheme.colorScheme.outlineVariant
            when {
                progress.done -> {
                    CircularProgressIndicator(
                        progress = { 1f },
                        color = arc,
                        trackColor = track,
                        strokeWidth = 4.dp,
                        modifier = ring,
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_action_check),
                        contentDescription = null,
                        tint = arc,
                        modifier = Modifier.size(22.dp),
                    )
                }

                fraction == null -> {
                    CircularProgressIndicator(
                        color = arc,
                        trackColor = track,
                        strokeWidth = 4.dp,
                        modifier = ring,
                    )
                    PercentText("…")
                }

                else -> {
                    CircularProgressIndicator(
                        progress = { fraction },
                        color = arc,
                        trackColor = track,
                        strokeWidth = 4.dp,
                        modifier = ring,
                    )
                    PercentText("$percent%")
                }
            }
        }
    }
}

@Composable
private fun PercentText(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
    )
}

/**
 * What the badge opens when tapped: the file being copied, how many of how
 * many, how far in bytes, and a way to stop.
 *
 * Stop is an outline, not the red of a delete: stopping a copy undoes nothing
 * -- the files already copied stay -- so it should not wear the colour that
 * means "this cannot be taken back". Closing the sheet only puts it away; the
 * copy goes on, and the badge in the corner goes on saying so.
 */
@Composable
fun CopyProgressSheet(
    progress: LocalPasteProgress,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    OloDialog(
        title = stringResource(R.string.copy_progress_title),
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.action_close),
        content = {
            Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                if (progress.currentName.isNotEmpty()) {
                    Text(
                        progress.currentName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                progress.filesTotal?.let { total ->
                    Text(
                        stringResource(R.string.copy_progress_items, progress.filesDone, total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                progress.bytesTotal?.let { total ->
                    Text(
                        "${formatSize(progress.bytesDone)} / ${formatSize(total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val fraction = progress.fraction
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        action = {
            OutlinedButton(
                onClick = {
                    onStop()
                    onDismiss()
                },
                shape = MaterialTheme.shapes.small,
            ) {
                Text(stringResource(R.string.copy_progress_stop))
            }
        },
    )
}
