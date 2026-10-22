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
import androidx.compose.material3.TextButton
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
import org.filezilla.android.BuildConfig
import org.filezilla.android.R

/**
 * The settings screen: every app-wide choice in one place.
 *
 * The transfer and browsing switches used to live in a bottom sheet hung off
 * the transfer list's overflow menu -- a "transfer settings" entry three taps
 * deep inside the queue, where "fetch server thumbnails" (nothing to do with
 * transfers) and "how much disk the preview cache holds" also ended up because
 * there was nowhere else to put them. The app has a settings screen; these
 * belong on it, grouped by what they govern -- transfers, browsing, storage,
 * then the security and backup this screen already carried, and the build at
 * the foot where a person looks for it.
 *
 * Reached from the storage sheet, and closed the same way as the other tools,
 * so back returns to the sheet it was opened from.
 */
@Composable
fun SettingsScreen(model: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lockAvailable = remember { appLockAvailable(context) }

    // Counted when the screen opens rather than watched: it changes when a
    // file is fetched and when Android takes the space back, and nothing
    // tells the app about the second. Re-read after emptying.
    var cacheBytes by remember { mutableStateOf(model.viewCacheBytes()) }

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
        SectionLabel(R.string.settings_transfer_section, Modifier.padding(horizontal = 20.dp))
        SettingToggle(
            title = stringResource(R.string.setting_wifi_only),
            subtitle = stringResource(R.string.setting_wifi_only_detail),
            checked = model.wifiOnly,
            enabled = true,
            onChange = { model.applyWifiOnly(it) },
        )

        SectionLabel(R.string.settings_browse_section, Modifier.padding(horizontal = 20.dp))
        SettingToggle(
            title = stringResource(R.string.setting_server_thumbnails),
            subtitle = stringResource(R.string.setting_server_thumbnails_detail),
            checked = model.serverThumbnailsEnabled,
            enabled = true,
            onChange = { model.applyServerThumbnails(it) },
        )
        // The Wi-Fi-only rule for those fetches, shown only while they are on.
        if (model.serverThumbnailsEnabled) {
            SettingToggle(
                title = stringResource(R.string.setting_server_thumbnails_wifi),
                subtitle = stringResource(R.string.setting_server_thumbnails_wifi_detail),
                checked = model.serverThumbnailsWifiOnly,
                enabled = true,
                onChange = { model.applyServerThumbnailsWifiOnly(it) },
            )
        }

        // Opening a server file leaves a copy behind so the next tap is
        // instant. Held to a limit and dropped oldest first, but a few hundred
        // megabytes that nothing accounts for is the kind of thing nobody can
        // trace back -- so when there is any, it says how much and offers to
        // let it go. Empty, there is nothing to show, so the row is left out.
        if (cacheBytes > 0) {
            SectionLabel(R.string.settings_storage_section, Modifier.padding(horizontal = 20.dp))
            SettingActionRow(
                title = stringResource(R.string.setting_view_cache),
                subtitle = stringResource(R.string.setting_view_cache_detail, formatSize(cacheBytes)),
                actionLabel = stringResource(R.string.setting_view_cache_clear),
                onAction = {
                    model.emptyViewCache()
                    cacheBytes = model.viewCacheBytes()
                },
            )
        }

        SectionLabel(R.string.settings_security_section, Modifier.padding(horizontal = 20.dp))
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

        SectionLabel(R.string.settings_backup_section, Modifier.padding(horizontal = 20.dp))
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

        // Which build this is, where somebody holding the phone can read it.
        // Every build said "0.1" for a long run of commits, so "is this the one
        // with the fix in it" had no answer short of finding the bug again.
        SectionLabel(R.string.settings_info_section, Modifier.padding(horizontal = 20.dp))
        Text(
            stringResource(R.string.app_build, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
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
                // No document picker on this phone throws from launch(); clear
                // the held passphrase and say so rather than crash.
                if (runCatching { createFile.launch(defaultName) }.isFailure) {
                    pendingExportPass = null
                    Toast.makeText(context, context.getString(R.string.picker_no_app), Toast.LENGTH_LONG).show()
                }
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
                if (runCatching { openFile.launch(arrayOf("*/*")) }.isFailure) {
                    pendingImportPass = null
                    Toast.makeText(context, context.getString(R.string.picker_no_app), Toast.LENGTH_LONG).show()
                }
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

/** A setting whose value is not a switch but a one-off action -- a title, a
 *  sentence, and a button at the end that does the thing. */
@Composable
private fun SettingActionRow(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onAction, shape = MaterialTheme.shapes.small) {
            Text(actionLabel)
        }
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
