package org.filezilla.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.android.files.AccessRoute

/**
 * What stands in for the pane while the app cannot read storage.
 *
 * Says what it wants and why before sending anyone to Settings, because on
 * Android 11 and up this is a toggle the user has to find themselves -- there
 * is no dialog to fall back on, and no second chance to explain.
 */
@Composable
fun StorageGate(route: AccessRoute, onGrant: () -> Unit, modifier: Modifier = Modifier) {
    val unavailable = route == AccessRoute.UNAVAILABLE
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FlatIcon(
            icon = R.drawable.ic_flat_folder,
            contentDescription = null,
            chipSize = 64.dp,
            cornerRadius = 18.dp,
            modifier = Modifier.size(64.dp),
        )
        Text(
            stringResource(
                if (unavailable) R.string.storage_unavailable_title else R.string.storage_permission_title,
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(
                if (unavailable) R.string.storage_unavailable_detail else R.string.storage_permission_detail,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // No button on Android 10: there is nowhere to send them, and a
        // button that leads nowhere is worse than none.
        if (!unavailable) {
            Button(onClick = onGrant) {
                Text(stringResource(R.string.storage_permission_action))
            }
        }
    }
}
