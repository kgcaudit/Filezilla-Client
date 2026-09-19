package org.filezilla.android.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.filezilla.android.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.filezilla.android.transfer.LogLine
import org.filezilla.ftp.protocol.LogLevel

/**
 * FileZilla's message log, verbatim.
 *
 * Every command and reply, in a monospaced font, because when a transfer
 * misbehaves against some unusual server this trace is the only thing that
 * explains why -- and the awkward servers are the ones this app is for.
 */
@Composable
fun LogScreen(lines: List<LogLine>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.log_empty_title),
            detail = stringResource(R.string.log_empty_detail),
            icon = R.drawable.ic_flat_log,
            modifier = modifier,
        )
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        // Follow the tail: the interesting line is almost always the last one.
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        itemsIndexed(lines) { _, line ->
            Text(
                text = "${formatLogTime(line.atMillis)} ${prefixFor(line.level)} ${line.message}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = colourFor(line.level),
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            )
        }
    }
}

private fun prefixFor(level: LogLevel): String = when (level) {
    LogLevel.COMMAND -> ">"
    LogLevel.REPLY -> "<"
    LogLevel.ERROR -> "!"
    LogLevel.STATUS -> " "
    LogLevel.DEBUG -> "#"
}

@Composable
private fun colourFor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
    LogLevel.COMMAND -> MaterialTheme.colorScheme.primary
    LogLevel.DEBUG -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.onSurface
}
