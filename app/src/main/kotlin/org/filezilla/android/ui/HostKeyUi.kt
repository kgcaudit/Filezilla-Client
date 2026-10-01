package org.filezilla.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.ftp.sftp.SshHostKey

/**
 * What the site editor says about the host key, which is never a switch.
 *
 * The SSH parallel of [CertificateStatus]. Nothing here is chosen in advance:
 * a host key is accepted by recognising it when the server presents it. What a
 * form can do is the other direction -- see what was accepted and withdraw it,
 * so the next connection asks again.
 */
@Composable
fun HostKeyStatus(known: String?, onForget: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(if (known == null) R.string.hostkey_none_title else R.string.hostkey_pinned_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (known == null) {
            Text(
                stringResource(R.string.hostkey_none_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    known,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                TextButton(onClick = onForget, shape = MaterialTheme.shapes.small) {
                    Text(stringResource(R.string.hostkey_forget))
                }
            }
        }
    }
}

/**
 * The host key itself, laid out to be read against another copy of it.
 *
 * The fingerprint settles the question and no other part does, so it goes
 * first and largest; the algorithm is there only to make an unfamiliar
 * fingerprint recognisable. Shown as SSH tools print it -- `SHA256:` and a
 * base64 run -- so it can be compared with `ssh-keygen -l` or a NAS page.
 */
@Composable
fun HostKeyFacts(hostKey: SshHostKey) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                stringResource(R.string.hostkey_fingerprint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                hostKey.fingerprintSha256,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
        HostKeyFact(stringResource(R.string.hostkey_algorithm), hostKey.algorithm)
        HostKeyFact(stringResource(R.string.hostkey_fingerprint_md5), hostKey.fingerprintMd5)
    }
}

@Composable
private fun HostKeyFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

/**
 * What the app needs to put a host key in front of somebody.
 *
 * Carries the site because accepting is a decision about a saved server, not
 * about the dialog: the fingerprint has to be stored somewhere, and the retry
 * has to know where to go back to.
 */
data class HostKeyQuestion(
    val site: org.filezilla.android.data.SiteEntity,
    val hostKey: SshHostKey,
    /** Set when a host key accepted before has been replaced by this one. */
    val replacing: String?,
)

/**
 * Asks whether this is the right server, once -- the SSH parallel of
 * [CertificateDialog].
 *
 * Not a yes/no about an error: the accepting button says what it does, the key
 * is shown in a form that can be compared, and a *changed* key gets a colder
 * dialog and a button that is not dressed as the safe one, because a first
 * meeting is routine and a change is not.
 */
@Composable
fun HostKeyDialog(
    question: HostKeyQuestion,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    val changed = question.replacing != null
    OloDialog(
        title = stringResource(
            if (changed) R.string.hostkey_changed_title else R.string.hostkey_ask_title,
            question.site.name.ifBlank { question.site.host },
        ),
        detail = stringResource(
            if (changed) R.string.hostkey_changed_detail else R.string.hostkey_ask_detail,
        ),
        onDismiss = onDismiss,
        content = {
            HostKeyFacts(question.hostKey)
            if (changed) {
                Text(
                    stringResource(R.string.hostkey_changed_was, question.replacing!!),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        action = {
            ConfirmButton(
                text = stringResource(
                    if (changed) R.string.hostkey_accept_changed else R.string.hostkey_accept,
                ),
                onClick = onTrust,
                // A changed key is the one case where accepting is not the safe
                // answer, so the button is not dressed as the safe one.
                destructive = changed,
            )
        },
    )
}
