package org.filezilla.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.filezilla.android.R

/**
 * A failure the user can do something about.
 *
 * Three levels, in the order someone reads them: what happened, what to do,
 * and then -- small, monospaced, last -- what the machine actually said. The
 * raw text is kept rather than hidden because on an awkward server it is the
 * only thing that explains anything, and it is put last because for the
 * common mistakes it explains nothing.
 *
 * Drawn on the app's own raised surface rather than in the error colour.
 * Filling the card meant a hundred thousand pixels of pink on a cream page
 * for something as ordinary as Wi-Fi being off -- an alarm sounded at the
 * same volume whatever went wrong, and a card that plainly came from a
 * different palette. The severity is where it costs no area and reads
 * first anyway: the mark and the title. What is left is an outline, so the
 * card is still found at a glance without shouting.
 */
@Composable
fun ErrorPanel(
    failure: ConnectionFailure,
    onRetry: () -> Unit,
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
        ),
        border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.38f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The same filled tile every row in the app wears, in the
                // error colour. It was a flat amber disc, which was a third
                // palette arguing with the card and the page behind it.
                TileIcon(
                    glyph = R.drawable.ic_tile_alert,
                    colour = scheme.error,
                    glyphTint = scheme.onError,
                    contentDescription = null,
                    size = 30.dp,
                    cornerRadius = 9.dp,
                )
                Text(
                    stringResource(failure.title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.error,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }

            Text(
                stringResource(failure.advice),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (failure.detailArg.isNotBlank()) {
                Text(
                    stringResource(R.string.fail_detail_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    stringResource(failure.detailFormat, failure.detailArg),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpenLog) {
                    Text(
                        stringResource(R.string.action_open_log),
                        color = scheme.onSurfaceVariant,
                    )
                }
                // The one to press, so it wears what everything pressable in
                // this app wears. Retrying is not the dangerous thing here.
                TextButton(onClick = onRetry) {
                    Text(
                        stringResource(R.string.action_retry),
                        color = scheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
