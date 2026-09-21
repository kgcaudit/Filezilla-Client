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
import org.filezilla.ftp.net.ServerCertificate
import java.text.DateFormat
import java.util.Date

/**
 * A fingerprint broken up so it can actually be compared.
 *
 * Sixty-four hex characters in one run is something people glance at and
 * declare identical. In rows of eight pairs it is something they can check
 * against what their server prints, which is the entire point of showing
 * it -- a fingerprint nobody compares is decoration.
 */
fun fingerprintLines(fingerprint: String, perLine: Int = 8): List<String> =
    fingerprint.split(":").chunked(perLine).map { it.joinToString(":") }

/** The last groups of a fingerprint, for naming one in passing. */
fun fingerprintTail(fingerprint: String, groups: Int = 4): String =
    fingerprint.split(":").takeLast(groups).joinToString(":")

/**
 * What the site editor says about the certificate, which is never a switch.
 *
 * Nothing here is a choice to make in advance: a certificate is accepted by
 * recognising it when the server presents it. What can be done from a form
 * is the other direction -- seeing what was accepted, and withdrawing it so
 * the next connection asks again.
 */
@Composable
fun CertificateStatus(pinned: String?, onForget: () -> Unit) {
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
            stringResource(
                if (pinned == null) R.string.cert_none_title else R.string.cert_pinned_title,
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (pinned == null) {
            Text(
                stringResource(R.string.cert_none_detail),
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
                    fingerprintTail(pinned),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onForget) {
                    Text(stringResource(R.string.cert_forget))
                }
            }
        }
    }
}

/**
 * The certificate itself, laid out to be read against another copy of it.
 *
 * Ordered by what settles the question. The fingerprint is the only part
 * that identifies this certificate and no other, so it goes first and
 * largest; the name and the dates are there to make an unfamiliar
 * fingerprint recognisable, not to be trusted on their own -- anyone can
 * put any name in a certificate they signed themselves.
 */
@Composable
fun CertificateFacts(certificate: ServerCertificate, nowMillis: Long = System.currentTimeMillis()) {
    val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
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
                stringResource(R.string.cert_fingerprint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (line in fingerprintLines(certificate.fingerprint)) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium
                        .copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
        Fact(stringResource(R.string.cert_subject), certificate.commonName)
        Fact(
            stringResource(R.string.cert_issuer),
            if (certificate.selfSigned) {
                stringResource(R.string.cert_issuer_self)
            } else {
                certificate.issuerName
            },
        )
        Fact(
            stringResource(R.string.cert_valid),
            stringResource(
                if (certificate.isCurrentAt(nowMillis)) R.string.cert_valid_range else R.string.cert_valid_expired,
                dates.format(Date(certificate.notBeforeMillis)),
                dates.format(Date(certificate.notAfterMillis)),
            ),
        )
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * What the app needs to put a certificate in front of somebody.
 *
 * The site is carried along because accepting is not a decision about the
 * dialog, it is a decision about a saved server: the fingerprint has to go
 * somewhere, and the retry has to know where to go back to.
 */
data class CertificateQuestion(
    val site: org.filezilla.android.data.SiteEntity,
    val certificate: ServerCertificate,
    /** Set when a certificate accepted before has been replaced by this one. */
    val replacing: String?,
)

/**
 * Asks whether this is the right server, once.
 *
 * Deliberately not a yes/no about an error. An error dialog trains people
 * to press the button that makes it go away, and the button that makes this
 * one go away is the one that grants a server permanent trust. So the
 * accepting button says what it does rather than "OK", the certificate is
 * shown in a form that can be compared rather than summarised as "invalid",
 * and a certificate that has *changed* gets a different, colder dialog --
 * because a first meeting is routine and a change is not.
 */
@Composable
fun CertificateDialog(
    question: CertificateQuestion,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    val changed = question.replacing != null
    OloDialog(
        title = stringResource(
            if (changed) R.string.cert_changed_title else R.string.cert_ask_title,
            question.site.name.ifBlank { question.site.host },
        ),
        detail = stringResource(
            if (changed) R.string.cert_changed_detail else R.string.cert_ask_detail,
        ),
        onDismiss = onDismiss,
        content = {
            CertificateFacts(question.certificate)
            if (changed) {
                Text(
                    stringResource(R.string.cert_changed_was, fingerprintTail(question.replacing!!)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        action = {
            ConfirmButton(
                text = stringResource(
                    if (changed) R.string.cert_accept_changed else R.string.cert_accept,
                ),
                onClick = onTrust,
                // A changed certificate is the one case where the accepting
                // button is not the safe one, so it is not dressed as the
                // safe one either.
                destructive = changed,
            )
        },
    )
}
