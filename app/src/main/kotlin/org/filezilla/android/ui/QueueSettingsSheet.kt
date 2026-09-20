package org.filezilla.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.android.ui.theme.tiles

/**
 * What the transfer list is allowed to do, in the same drawer the rest of
 * the app's choices live in.
 *
 * It was a dropdown menu with exactly one item in it, and that item carried
 * a title, a sentence and a switch. A menu sizes itself to its widest entry
 * and pins itself under the button that opened it, so a two-line setting
 * came out as a wide slab floating in the top-right corner -- not a panel
 * the app would have drawn anywhere else, and the one thing on the screen
 * that looked borrowed.
 *
 * A sheet is where this app already puts a list of places and choices; the
 * row is the row the storage drawer uses. One setting does not need a
 * screen of its own, but it does need to look like it came from here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSettingsSheet(
    wifiOnly: Boolean,
    onWifiOnly: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        QueueSettings(wifiOnly = wifiOnly, onWifiOnly = onWifiOnly)
    }
}

/**
 * The settings themselves, apart from the sheet.
 *
 * Separate for the same reason the storage drawer's list is: a sheet draws
 * in a window of its own, and a test that renders a screen never sees
 * inside one.
 */
@Composable
fun QueueSettings(wifiOnly: Boolean, onWifiOnly: (Boolean) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            stringResource(R.string.queue_settings),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onWifiOnly(!wifiOnly) }
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TileIcon(
                glyph = R.drawable.ic_tile_transfers,
                colour = MaterialTheme.tiles.code,
                contentDescription = null,
                size = 38.dp,
                cornerRadius = 11.dp,
            )
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp, end = 12.dp)) {
                Text(
                    stringResource(R.string.setting_wifi_only),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.setting_wifi_only_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(checked = wifiOnly, onCheckedChange = onWifiOnly)
        }
    }
}
