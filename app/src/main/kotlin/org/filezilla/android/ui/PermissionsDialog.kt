package org.filezilla.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.android.files.FileMode
import org.filezilla.ftp.listing.DirectoryEntry

/**
 * Who may do what with one file on a server.
 *
 * A grid rather than a text box for the three digits. The digits are how
 * the permission is sent and how anyone who knows them expects to read it,
 * so they are shown -- but they are shown as the *result*, under the
 * boxes, because the question being answered is "may the group write to
 * this" and answering it by arithmetic is a step nobody should have to do
 * on a phone.
 *
 * What the server said is where this opens. Where the server said nothing
 * this cannot pretend: it opens on what a new file or folder would get and
 * says so, because a dialog that opened on 000 and was confirmed would
 * take a file away from its owner.
 */
@Composable
fun PermissionsDialog(
    entry: DirectoryEntry,
    onApply: (FileMode, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val known = FileMode.of(entry.permissions)
    val extra = FileMode.extraDigitIn(entry.permissions)
    var mode by remember(entry) {
        mutableStateOf(known ?: if (entry.isDirectory) FileMode.FOLDER else FileMode.FILE)
    }

    OloDialog(
        title = stringResource(R.string.action_change_mode),
        detail = if (known == null) stringResource(R.string.mode_unknown) else null,
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FileHeading(entry.name, entry.isDirectory)

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.width(WHO_COLUMN))
                    for (what in FileMode.What.entries) {
                        Text(
                            stringResource(what.label()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(BOX_COLUMN),
                        )
                    }
                }

                for (who in FileMode.Who.entries) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(who.label()),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(WHO_COLUMN),
                        )
                        for (what in FileMode.What.entries) {
                            Box(
                                modifier = Modifier.width(BOX_COLUMN),
                                contentAlignment = Alignment.Center,
                            ) {
                                Checkbox(
                                    checked = mode.allows(who, what),
                                    onCheckedChange = { mode = mode.with(who, what, it) },
                                    modifier = Modifier.size(40.dp),
                                )
                            }
                        }
                    }
                }

                // The result, not a second place to type. Two inputs for
                // one value is two things to keep in step, and the one
                // that loses is always the one the user did not touch.
                Text(
                    stringResource(
                        R.string.mode_result,
                        extra.orEmpty() + mode.toString(),
                        mode.asLetters(),
                    ),
                    style = MaterialTheme.typography.bodyMedium
                        .copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )

                if (extra != null) {
                    // Said out loud because it is being sent and cannot be
                    // edited here. Silently keeping a bit is better than
                    // silently clearing one, but only saying so is honest.
                    Text(
                        stringResource(R.string.mode_special_kept, extra),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        action = {
            ConfirmButton(
                text = stringResource(R.string.action_apply),
                onClick = { onApply(mode, extra) },
            )
        },
    )
}

private val WHO_COLUMN = 92.dp
private val BOX_COLUMN = 56.dp

private fun FileMode.Who.label(): Int = when (this) {
    FileMode.Who.OWNER -> R.string.mode_owner
    FileMode.Who.GROUP -> R.string.mode_group
    FileMode.Who.EVERYONE -> R.string.mode_everyone
}

private fun FileMode.What.label(): Int = when (this) {
    FileMode.What.READ -> R.string.mode_read
    FileMode.What.WRITE -> R.string.mode_write
    FileMode.What.EXECUTE -> R.string.mode_execute
}
