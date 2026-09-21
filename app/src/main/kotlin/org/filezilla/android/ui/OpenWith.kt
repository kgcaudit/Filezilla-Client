package org.filezilla.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.android.files.OpenFile

/**
 * Which app to hand a file to, asked once per kind of file.
 *
 * The app used to build an `ACTION_VIEW` and hope. That works for a photo
 * and fails for most of what a file manager holds -- Android calls a `.srt`
 * an `application/x-subrip` and almost nothing declares it -- so a tap
 * answered "no app can open this" on a phone with several apps that would
 * have opened it gladly.
 *
 * Asking is the fix, and remembering is what stops it being a nuisance: the
 * choice is kept against the extension, because that is what the user is
 * actually deciding about. They are not choosing an app for
 * `application/x-subrip`; they are choosing one for subtitles.
 */
@Composable
fun OpenWithSheet(
    /** What is being opened, for the title. */
    fileName: String,
    candidates: List<OpenFile.Candidate>,
    onPick: (OpenFile.Candidate, remember: Boolean) -> Unit,
    /** Hands it to the system's own chooser instead, for the rare leftovers. */
    onSystemChooser: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Defaulted on. Somebody opening a subtitle is opening every subtitle,
    // and a dialog that comes back for each one is the thing this exists to
    // stop -- while the box, and the list it feeds, make it plain that the
    // answer is remembered and where to undo it.
    var always by remember { mutableStateOf(true) }
    val extension = org.filezilla.android.files.FileAssociations.extensionOf(fileName)

    OloInfoDialog(
        title = stringResource(R.string.open_with_title),
        onDismiss = onDismiss,
        content = {
            // Bounded, and the list is the only part that gives. The fixed
            // rows under it -- the box that says "always" and the way to
            // look further -- are measured first and keep their space;
            // whatever is left is the list's. Capped only, so three apps
            // make a short dialog rather than a tall one with a gap.
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                Text(
                    fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                if (candidates.isEmpty()) {
                    Text(
                        stringResource(R.string.open_none_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    // Scrolled, and capped. A dialog's height is clamped by
                    // the screen, and a phone that offers ten apps pushed
                    // the box that says "always" and the way to look
                    // further right off the bottom, where neither could be
                    // reached at all. Only the list scrolls; the two things
                    // underneath stay put.
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                    for (candidate in candidates) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(candidate, always && extension != null) }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(
                                candidate.label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        HorizontalDivider()
                    }
                    }

                    // Only where there is an extension to remember it
                    // against. A file called README has no kind to speak of,
                    // and a box that cannot be honoured is worse than none.
                    if (extension != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = always, onClick = { always = !always })
                                .padding(top = 8.dp),
                        ) {
                            Checkbox(checked = always, onCheckedChange = { always = it })
                            Text(
                                stringResource(R.string.open_always, extension),
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }

                Text(
                    stringResource(R.string.open_system_chooser),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onSystemChooser)
                        .padding(vertical = 12.dp),
                )
            }
        },
    )
}

/**
 * Every "always open with" the user has set, and a way to take one back.
 *
 * The other half of remembering. A choice that can be made and not unmade
 * is a trap: pick the wrong app once for a kind of file you open daily, and
 * the app is wrong for ever with nothing on screen admitting it happened.
 */
@Composable
fun FileAssociationsDialog(
    associations: List<Pair<String, String>>,
    onForget: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    OloInfoDialog(
        title = stringResource(R.string.associations_title),
        onDismiss = onDismiss,
        content = {
            if (associations.isEmpty()) {
                Text(
                    stringResource(R.string.associations_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@OloInfoDialog
            }
            Column {
                for ((extension, label) in associations) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                ".$extension",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            stringResource(R.string.associations_forget),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clickable { onForget(extension) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                    HorizontalDivider()
                }
            }
        },
    )
}
