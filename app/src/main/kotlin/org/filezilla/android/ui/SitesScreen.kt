package org.filezilla.android.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CardDefaults
import androidx.annotation.DrawableRes
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
import org.filezilla.android.data.SiteProtocol
import org.filezilla.ftp.protocol.FtpSecurity

@Composable
fun SitesScreen(
    sites: List<SiteEntity>,
    editing: SiteDraft?,
    onEdit: (SiteEntity?) -> Unit,
    onSave: (SiteDraft) -> Unit,
    onTest: suspend (SiteDraft) -> SiteTestResult,
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
                            // without being read -- but only for the protocols
                            // that have a clear-vs-encrypted answer to give,
                            // and in each one's own words. The security field
                            // means FTPS modes for FTP and http/https for
                            // WebDAV; SFTP is always encrypted; SMB has no
                            // FTP-style grade at all. Read as FTP vocabulary
                            // everywhere, the line named every WebDAV server
                            // "FTPS implicit" and every SMB server "Plain FTP".
                            SecurityBadge(site)
                        }
                        IconButton(onClick = { onEdit(site) }) {
                            Icon(
                                painterResource(R.drawable.ic_action_rename),
                                contentDescription = stringResource(R.string.sites_edit, site.name),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDelete(site) }) {
                            Icon(
                                painterResource(R.drawable.ic_action_delete),
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
            onTest = onTest,
        )
    }
}

/**
 * The one-line security note under a server's address, in the protocol's own
 * terms.
 *
 *  - FTP keeps the FTPS-mode wording and the shield/warning split on whether
 *    the login is plain.
 *  - WebDAV is http or https, told apart by the same [FtpSecurity.PLAIN] flag
 *    the editor stores: plain is http (a warning), anything else https (a
 *    shield). The security *label* is WebDAV's http/https, not FTP's.
 *  - SFTP runs over SSH and is always encrypted, so there is no clear-text
 *    mode to warn about -- a shield and the protocol's name.
 *  - SMB negotiates SMB 3 encryption opportunistically rather than as a
 *    user-chosen grade (see SmbClient's `withEncryptData`), so neither a green
 *    shield (which would promise encryption that may not happen) nor an amber
 *    warning (which would call an often-encrypted link plain) tells the truth.
 *    The row names the share instead, with a neutral folder glyph; a legacy
 *    site that kept its share in the path has none to show, so the line is
 *    simply left off.
 */
@Composable
private fun SecurityBadge(site: SiteEntity) {
    val plain = site.securityEnum == FtpSecurity.PLAIN
    val secureGlyph = R.drawable.ic_flat_secure
    val warningGlyph = R.drawable.ic_flat_warning
    when (site.protocolEnum) {
        SiteProtocol.FTP ->
            CardBadge(if (plain) warningGlyph else secureGlyph, securityLabel(site.securityEnum))
        SiteProtocol.WEBDAV ->
            CardBadge(if (plain) warningGlyph else secureGlyph, webdavEncryptionLabel(site.securityEnum))
        SiteProtocol.SFTP ->
            CardBadge(secureGlyph, protocolLabel(SiteProtocol.SFTP))
        SiteProtocol.SMB ->
            site.smbShare?.takeIf { it.isNotBlank() }?.let { share ->
                CardBadge(R.drawable.ic_flat_folder, share)
            }
    }
}

/**
 * A flat glyph and a line of label text, the shape the security note has worn
 * since it stopped being blue protocol text. The glyph is drawn unbounded, or
 * Material flattens the two-tone artwork to one colour and the amber warning
 * and the green shield become the same mark.
 */
@Composable
private fun CardBadge(@DrawableRes glyph: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(glyph),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(14.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
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
