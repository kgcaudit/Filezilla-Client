package org.filezilla.android.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.filezilla.android.R
import androidx.compose.ui.unit.dp
import org.filezilla.android.data.SiteEntity
import org.filezilla.ftp.protocol.FtpSecurity

@Composable
fun SitesScreen(
    sites: List<SiteEntity>,
    editing: SiteDraft?,
    onEdit: (SiteEntity?) -> Unit,
    onSave: (SiteDraft) -> Unit,
    onDelete: (SiteEntity) -> Unit,
    onConnect: (SiteEntity) -> Unit,
    onMove: (SiteEntity, Move) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sites.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.sites_empty_title),
            detail = stringResource(R.string.sites_empty_detail),
            icon = R.drawable.ic_tile_server,
            modifier = modifier,
        )
    } else {
        LazyColumn(
            modifier = modifier,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            items(sites, key = { it.id }) { site ->
                val index = sites.indexOf(site)
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onConnect(site) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // A filled icon chip, the way a file manager marks a
                        // row: colour carries the "this is a server" before
                        // any text is read.
                        TileIcon(
                            glyph = R.drawable.ic_tile_server,
                            colour = MaterialTheme.colorScheme.secondary,
                            contentDescription = null,
                            size = 44.dp,
                            cornerRadius = 13.dp,
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                site.name.ifBlank { site.host },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${site.user}@${site.host}:${site.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // Whether the login travels in the clear is the
                            // one thing worth knowing about a server before
                            // tapping it, and it was a line of small blue
                            // text that read like a protocol note. A shield
                            // or a warning says which of the two it is
                            // without being read.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val plain = site.securityEnum == FtpSecurity.PLAIN
                                Icon(
                                    painter = painterResource(
                                        if (plain) R.drawable.ic_flat_warning
                                        else R.drawable.ic_flat_secure,
                                    ),
                                    contentDescription = null,
                                    // Unbounded, or Material flattens the
                                    // artwork to one colour and the amber and
                                    // the green become the same mark.
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    securityLabel(site.securityEnum),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                        IconButton(onClick = { onEdit(site) }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.sites_edit, site.name),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDelete(site) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.sites_delete, site.name),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Only when there is somewhere to move to. One server
                        // cannot be reordered, and a pair of dead arrows on
                        // every row would be two more things to read past on
                        // a screen whose job is to be scanned.
                        if (sites.size > 1) {
                            MoveButtons(
                                name = site.name.ifBlank { site.host },
                                canMoveUp = index > 0,
                                canMoveDown = index < sites.lastIndex,
                                onMove = { towards -> onMove(site, towards) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (editing != null) {
        SiteEditor(
            initial = editing,
            onDismiss = { onEdit(null) },
            onSave = {
                onSave(it)
                onEdit(null)
            },
        )
    }
}

/**
 * The up and down arrows, stacked into one column's width.
 *
 * Stacked rather than set beside the edit and delete buttons: four buttons in
 * a row leaves a long server name almost no space on a phone, and these two
 * belong together anyway. They are half-height for the same reason, which is
 * why the touch target is set explicitly rather than left to the default --
 * a 24dp button is a 24dp target, and that is too small to hit.
 */
@Composable
private fun MoveButtons(
    name: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Move) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MoveButton(
            icon = Icons.Filled.KeyboardArrowUp,
            description = stringResource(R.string.sites_move_up, name),
            enabled = canMoveUp,
            onClick = { onMove(Move.UP) },
        )
        MoveButton(
            icon = Icons.Filled.KeyboardArrowDown,
            description = stringResource(R.string.sites_move_down, name),
            enabled = canMoveDown,
            onClick = { onMove(Move.DOWN) },
        )
    }
}

@Composable
private fun MoveButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(width = 40.dp, height = 32.dp),
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(22.dp),
            // Dimmed rather than hidden at the ends of the list: a button that
            // disappears makes the row below it jump under the finger.
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        )
    }
}
