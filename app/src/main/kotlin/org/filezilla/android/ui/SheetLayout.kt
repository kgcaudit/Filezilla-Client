package org.filezilla.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
