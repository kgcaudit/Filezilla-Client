package org.filezilla.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The "nothing here yet, and here is what to do about it" panel. */
@Composable
fun EmptyState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    /**
     * The thing that is missing, drawn above the words.
     *
     * An empty screen is the one place with room for it, and the icon says
     * which empty screen this is before the text is read -- a queue with
     * nothing in it and a log with nothing in it otherwise look identical.
     *
     * A filled tile, like every other icon in the app. It was a white chip
     * with a small picture inside, which is the arrangement the whole set
     * moved away from for being hard to see -- and this is the largest icon
     * anywhere in the app, so it was the most visible survivor of it.
     */
    @DrawableRes icon: Int? = null,
    colour: Color = MaterialTheme.colorScheme.secondary,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            TileIcon(
                glyph = icon,
                colour = colour,
                contentDescription = null,
                size = 76.dp,
                cornerRadius = 22.dp,
                modifier = Modifier.padding(bottom = 18.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
