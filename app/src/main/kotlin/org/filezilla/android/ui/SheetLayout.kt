package org.filezilla.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.filezilla.android.R

/**
 * The width a bottom sheet's contents are held to.
 *
 * A modal sheet fills the screen's width, and on the fold's main display that
 * is far wider than a column of places or settings wants to be: the rows strand
 * at the left edge and the sheet reads half-empty. So the contents sit in a
 * centred column no wider than this. A phone is narrower than it, so there the
 * column simply fills the width and nothing changes.
 */
val SHEET_CONTENT_MAX_WIDTH = 560.dp

/**
 * Centres a sheet's contents and holds them to [SHEET_CONTENT_MAX_WIDTH] on a
 * wide screen. On a phone the inner column fills the width as before.
 */
@Composable
fun SheetContent(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = SHEET_CONTENT_MAX_WIDTH).fillMaxWidth()) {
            content()
        }
    }
}

/**
 * The top of a bottom sheet: what the sheet is, and the way out of it.
 *
 * Every sheet in the app is opened by a button, and a sheet that rises from
 * the foot of the screen with no heading reads as the screen itself having
 * slid away rather than as a panel over it -- most of all when it is long
 * enough to reach the top. A sheet names itself after the button that opened
 * it, in one weight and one place, and carries a close beside the name so
 * leaving does not depend on knowing the drag handle can be flung down. The
 * optional [action] sits before the close, for the "+" a section heading
 * cannot carry on the title row.
 */
@Composable
fun SheetHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
        IconButton(onClick = onClose) {
            Icon(
                painterResource(R.drawable.ic_action_close),
                contentDescription = stringResource(R.string.action_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
