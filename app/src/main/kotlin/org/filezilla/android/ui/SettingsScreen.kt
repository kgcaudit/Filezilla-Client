package org.filezilla.android.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.filezilla.android.R

/**
 * The settings screen: the app lock and the backup of what cannot be
 * downloaded again -- the saved servers and favourites.
 *
 * Reached from the storage sheet, and closed the same way as the other tools,
 * so back returns to the sheet it was opened from.
 */
@Composable
fun SettingsScreen(model: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lockAvailable = remember { appLockAvailable(context) }

    // The passphrase typed in the dialog, held until the file picker it launches
    // comes back with a destination. One of the two actions is live at a time.
    var pendingExportPass by remember { mutableStateOf<String?>(null) }
    var pendingImportPass by remember { mutableStateOf<String?>(null) }
    var askExport by remember { mutableStateOf(false) }
    var askImport by remember { mutableStateOf(false) }

    val failedMsg = stringResource(R.string.backup_failed)
    val importFailedMsg = stringResource(R.string.backup_import_failed)

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val pass = pendingExportPass
        pendingExportPass = null
        if (uri != null && pass != null) {
            model.exportBackup(
                uri,
                pass.toCharArray(),
                onDone = { s, b ->
                    Toast.makeText(context, context.getString(R.string.backup_exported, s, b), Toast.LENGTH_LONG).show()
                },
                onError = { Toast.makeText(context, failedMsg, Toast.LENGTH_LONG).show() },
            )
        }
    }

    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val pass = pendingImportPass
        pendingImportPass = null
        if (uri != null && pass != null) {
            model.importBackup(
                uri,
                pass.toCharArray(),
                onDone = { s, b ->
                    Toast.makeText(context, context.getString(R.string.backup_imported, s, b), Toast.LENGTH_LONG).show()
                },
                onError = { Toast.makeText(context, importFailedMsg, Toast.LENGTH_LONG).show() },
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        SectionLabel(R.string.settings_security_section)
        SettingToggle(
            title = stringResource(R.string.settings_lock_title),
            subtitle = if (lockAvailable) {
                stringResource(R.string.settings_lock_subtitle)
            } else {
                stringResource(R.string.settings_lock_unavailable)
            },
            checked = model.appLockEnabled && lockAvailable,
            enabled = lockAvailable,
            onChange = { model.setAppLock(it) },
        )

        SectionLabel(R.string.settings_backup_section)
        SettingRow(
            title = stringResource(R.string.settings_export),
            subtitle = stringResource(R.string.settings_export_subtitle),
            onClick = { askExport = true },
        )
        SettingRow(
            title = stringResource(R.string.settings_import),
            subtitle = stringResource(R.string.settings_import_subtitle),
            onClick = { askImport = true },
        )
    }

    val defaultName = stringResource(R.string.backup_default_name)
    if (askExport) {
        PassphraseDialog(
            title = stringResource(R.string.backup_export_title),
            hint = stringResource(R.string.backup_export_hint),
            confirmLabel = stringResource(R.string.backup_create),
            onDismiss = { askExport = false },
            onConfirm = { pass ->
                askExport = false
                pendingExportPass = pass
                createFile.launch(defaultName)
            },
        )
    }
    if (askImport) {
        PassphraseDialog(
            title = stringResource(R.string.backup_import_title),
            hint = stringResource(R.string.backup_import_hint),
            confirmLabel = stringResource(R.string.backup_restore),
            onDismiss = { askImport = false },
            onConfirm = { pass ->
                askImport = false
                pendingImportPass = pass
                openFile.launch(arrayOf("*/*"))
            },
        )
    }
}

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PassphraseDialog(
    title: String,
    hint: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    OloDialog(
        title = title,
        detail = hint,
        onDismiss = onDismiss,
        content = {
            OloTextField(
                value = pass,
                onValueChange = { pass = it },
                label = stringResource(R.string.backup_password_label),
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        },
        action = {
            ConfirmButton(
                text = confirmLabel,
                enabled = pass.isNotEmpty(),
                onClick = { onConfirm(pass) },
            )
        },
    )
}
