package org.filezilla.android.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.TransferMode

/**
 * The encodings worth offering.
 *
 * Not every charset the JVM knows: a list of two hundred is not a choice, it
 * is an obstacle. These are the ones an FTP server in this part of the world
 * actually stores filenames in, and the note beside the field says to leave it
 * alone unless the names come out wrong.
 */
private val ENCODINGS = listOf(
    null to R.string.encoding_auto,
    "UTF-8" to null,
    "EUC-KR" to null,
    "x-windows-949" to null,
    "Shift_JIS" to null,
    "GBK" to null,
    "Big5" to null,
    "windows-1252" to null,
    "ISO-8859-1" to null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteEditor(
    initial: SiteDraft,
    onDismiss: () -> Unit,
    onSave: (SiteDraft) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var user by remember { mutableStateOf(initial.user) }
    var password by remember { mutableStateOf(initial.password) }
    var security by remember { mutableStateOf(initial.security) }
    var mode by remember { mutableStateOf(initial.transferMode) }
    var encoding by remember { mutableStateOf(initial.encoding) }
    var trustAll by remember { mutableStateOf(initial.trustAllCertificates) }
    var initialPath by remember { mutableStateOf(initial.initialPath.orEmpty()) }

    OloDialog(
        title = stringResource(
            if (initial.host.isBlank()) R.string.sites_new_title else R.string.sites_edit_title,
        ),
        onDismiss = onDismiss,
        content = {
            // Scrollable, and bounded. The form is taller than a phone screen
            // once encryption, connection mode and encoding are all on it, and
            // an AlertDialog does not scroll its content by itself -- it just
            // clips the bottom off, which is what it was doing to the
            // certificate switch.
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    // A floating label rides on the top border, so the first
                    // field needs headroom or the scroll container cuts it in
                    // half -- which is what it was doing to "Name".
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OloTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.field_name),
                    // Its own hint. Falling back to the host field's *label*
                    // put the word "호스트" in the name box, which read as the
                    // name field asking for a host.
                    placeholder = host.ifBlank { stringResource(R.string.name_hint) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OloTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = stringResource(R.string.field_host),
                    modifier = Modifier.fillMaxWidth(),
                )
                OloTextField(
                    value = port,
                    onValueChange = { candidate -> port = candidate.filter { it.isDigit() }.take(5) },
                    label = stringResource(R.string.field_port),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Picker(
                    label = stringResource(R.string.field_encryption),
                    selected = securityLabel(security),
                    options = FtpSecurity.entries.map { it to securityLabel(it) },
                    onSelect = { chosen ->
                        port = portAfterSecurityChange(port, security, chosen)
                        security = chosen
                    },
                )

                Picker(
                    label = stringResource(R.string.field_transfer_mode),
                    selected = modeLabel(mode),
                    options = TransferMode.entries.map { it to modeLabel(it) },
                    onSelect = { mode = it },
                )
                if (mode == TransferMode.ACTIVE) {
                    Hint(stringResource(R.string.mode_active_note))
                }

                Picker(
                    label = stringResource(R.string.field_encoding),
                    selected = encodingLabel(encoding),
                    options = ENCODINGS.map { (value, _) -> value to encodingLabel(value) },
                    onSelect = { encoding = it },
                )
                if (encoding != null) {
                    Hint(stringResource(R.string.encoding_note))
                }

                OloTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = stringResource(R.string.field_user),
                    // A hint, so the field is empty and ready to type into,
                    // while still saying what happens if it is left alone.
                    placeholder = SiteDraft.ANONYMOUS_USER,
                    modifier = Modifier.fillMaxWidth(),
                )
                OloTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.field_password),
                    placeholder = stringResource(R.string.password_hint),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = initial.passwordUnreadable,
                    supportingText = if (initial.passwordUnreadable) {
                        stringResource(R.string.password_unreadable)
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OloTextField(
                    value = initialPath,
                    onValueChange = { initialPath = it },
                    label = stringResource(R.string.field_initial_path),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(checked = trustAll, onCheckedChange = { trustAll = it })
                    Column(modifier = Modifier.padding(start = 10.dp)) {
                        Text(
                            stringResource(R.string.trust_all_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.trust_all_detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        action = {
            TextButton(
                enabled = host.isNotBlank() && port.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            name = name,
                            host = host,
                            port = port.toIntOrNull() ?: defaultPortFor(security),
                            user = user,
                            password = password,
                            security = security,
                            transferMode = mode,
                            encoding = encoding,
                            trustAllCertificates = trustAll,
                            initialPath = initialPath,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
    )
}

/** A read-only field that opens a menu; the same shape for all three choices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Picker(
    label: String,
    selected: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        // The same box as every other field. It was Material's own, so the
        // three pickers stood eight points taller than the six boxes around
        // them -- one form, two heights.
        OloTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = label,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            interactions = interactions,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

fun defaultPortFor(security: FtpSecurity): Int =
    if (security == FtpSecurity.IMPLICIT_TLS) 990 else 21

/**
 * The port to show after the user changes the encryption setting.
 *
 * Following the protocol is a convenience, not a rule: implicit FTPS on 21 is
 * a connection that simply hangs, so moving the port along with the protocol
 * saves a confusing failure. But it used to overwrite the port unconditionally,
 * which threw away a port the user had typed by hand -- and a server on a
 * non-standard port is precisely the case someone is editing this field for.
 *
 * So the port moves only when it is still the default for the protocol being
 * left, meaning nobody has chosen it. Anything else is the user's and is kept.
 */
fun portAfterSecurityChange(current: String, from: FtpSecurity, to: FtpSecurity): String {
    val typed = current.toIntOrNull()
    val wasUntouched = typed == null || typed == defaultPortFor(from)
    return if (wasUntouched) defaultPortFor(to).toString() else current
}

@Composable
fun securityLabel(security: FtpSecurity): String = stringResource(
    when (security) {
        FtpSecurity.PLAIN -> R.string.security_plain
        FtpSecurity.EXPLICIT_TLS -> R.string.security_explicit
        FtpSecurity.IMPLICIT_TLS -> R.string.security_implicit
    },
)

@Composable
private fun modeLabel(mode: TransferMode): String = stringResource(
    when (mode) {
        TransferMode.DEFAULT -> R.string.mode_default
        TransferMode.PASSIVE -> R.string.mode_passive
        TransferMode.ACTIVE -> R.string.mode_active
    },
)

@Composable
private fun encodingLabel(encoding: String?): String =
    encoding ?: stringResource(R.string.encoding_auto)
