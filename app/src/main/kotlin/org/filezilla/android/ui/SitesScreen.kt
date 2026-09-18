package org.filezilla.android.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import org.filezilla.android.data.SiteEntity
import org.filezilla.ftp.protocol.FtpSecurity

@Composable
fun SitesScreen(
    sites: List<SiteEntity>,
    editing: SiteEntity?,
    onEdit: (SiteEntity?) -> Unit,
    onSave: (SiteEntity) -> Unit,
    onDelete: (SiteEntity) -> Unit,
    onConnect: (SiteEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sites.isEmpty()) {
        EmptyState(
            title = "No servers yet",
            detail = "Add one with the + button. FTP and FTPS are supported; FTPS explicit on port 21 is the usual choice.",
            modifier = modifier,
        )
    } else {
        LazyColumn(modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            items(sites, key = { it.id }) { site ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onConnect(site) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                site.name.ifBlank { site.host },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "${site.user}@${site.host}:${site.port} · ${securityLabel(site.securityEnum)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { onEdit(site) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit ${site.name}")
                        }
                        IconButton(onClick = { onDelete(site) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete ${site.name}")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiteEditor(
    initial: SiteEntity,
    onDismiss: () -> Unit,
    onSave: (SiteEntity) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var user by remember { mutableStateOf(initial.user) }
    var password by remember { mutableStateOf(initial.password) }
    var security by remember { mutableStateOf(initial.securityEnum) }
    var trustAll by remember { mutableStateOf(initial.trustAllCertificates) }
    var initialPath by remember { mutableStateOf(initial.initialPath.orEmpty()) }
    var securityExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.host.isBlank()) "New server" else "Edit server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { candidate -> port = candidate.filter { it.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                ExposedDropdownMenuBox(
                    expanded = securityExpanded,
                    onExpandedChange = { securityExpanded = it },
                ) {
                    OutlinedTextField(
                        value = securityLabel(security),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Encryption") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(securityExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = securityExpanded,
                        onDismissRequest = { securityExpanded = false },
                    ) {
                        FtpSecurity.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(securityLabel(option)) },
                                onClick = {
                                    security = option
                                    // The default port follows the protocol,
                                    // because implicit FTPS on 21 is a
                                    // connection that will simply hang.
                                    port = defaultPortFor(option).toString()
                                    securityExpanded = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("User") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = initialPath,
                    onValueChange = { initialPath = it },
                    label = { Text("Start directory (optional)") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = trustAll, onCheckedChange = { trustAll = it })
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text("Accept any certificate")
                        Text(
                            "Turns off certificate checking for this server. " +
                                "Only for a server whose certificate you already know.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank() && port.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.ifBlank { host },
                            host = host.trim(),
                            port = port.toIntOrNull() ?: defaultPortFor(security),
                            user = user.ifBlank { "anonymous" },
                            password = password,
                            security = security.name,
                            trustAllCertificates = trustAll,
                            initialPath = initialPath.ifBlank { null },
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun defaultPortFor(security: FtpSecurity): Int =
    if (security == FtpSecurity.IMPLICIT_TLS) 990 else 21

fun securityLabel(security: FtpSecurity): String = when (security) {
    FtpSecurity.PLAIN -> "Plain FTP"
    FtpSecurity.EXPLICIT_TLS -> "FTPS explicit (AUTH TLS)"
    FtpSecurity.IMPLICIT_TLS -> "FTPS implicit"
}
