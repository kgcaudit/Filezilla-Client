package org.filezilla.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.android.data.SyncDirection
import org.filezilla.android.data.SyncJobEntity
import org.filezilla.android.data.SyncRunStatus
import org.filezilla.android.ui.theme.status

/**
 * The two ends a new scheduled mirror will run between, captured from the panes.
 *
 * Only the ends and a suggested name: everything the user actually decides
 * -- which way, how often, on what conditions -- the editor collects. Carried
 * as its own small type so the editor and the view model agree on exactly what
 * a job is made of without the editor reaching into a pane.
 */
data class SyncJobDraft(
    val localRoot: String,
    val localLabel: String,
    val siteId: String,
    val remoteRoot: String,
    val remoteLabel: String,
    val suggestedName: String,
)

/** How often a scheduled mirror runs, as the three choices the editor offers. */
enum class SyncInterval(val minutes: Long, val label: Int) {
    SIX_HOURS(6 * 60, R.string.sched_interval_6h),
    TWELVE_HOURS(12 * 60, R.string.sched_interval_12h),
    DAILY(24 * 60, R.string.sched_interval_daily);

    companion object {
        /** The closest choice to a stored minute count, for editing a job. */
        fun nearest(minutes: Long): SyncInterval = entries.minByOrNull { kotlin.math.abs(it.minutes - minutes) } ?: TWELVE_HOURS
    }
}

/**
 * The standing scheduled mirrors, each a card that can be turned on or off, run
 * now, edited or removed.
 *
 * The background counterpart of the one-shot sync preview: where that mirrors
 * once from the two panes in front of you, these run on their own on a schedule
 * WorkManager keeps. A job is added from here only when a device folder and a
 * server folder are both open -- the same two ends the one-shot mirror needs --
 * so the "+" reads the panes and refuses with a hint when they are not a pair.
 */
@Composable
fun ScheduledSyncScreen(model: MainViewModel, modifier: Modifier = Modifier) {
    val jobs by model.scheduledSync.jobs.collectAsState()

    // The editor, open either on a captured draft (adding) or an existing job
    // (editing). Null means closed.
    var editing by remember { mutableStateOf<SyncEditorTarget?>(null) }
    var removing by remember { mutableStateOf<SyncJobEntity?>(null) }
    var hint by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        if (jobs.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.sched_empty),
                detail = stringResource(R.string.sched_empty_detail),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(jobs, key = { it.id }) { job ->
                    SyncJobCard(
                        job = job,
                        onToggle = { on -> model.scheduledSync.setEnabled(job, on) },
                        onRunNow = { model.scheduledSync.runNow(job) },
                        onEdit = { editing = SyncEditorTarget.Edit(job) },
                        onRemove = { removing = job },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = {
                val draft = model.syncJobDraftFromPanes()
                if (draft == null) hint = true else editing = SyncEditorTarget.Add(draft)
            },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.sched_add))
        }
    }

    when (val target = editing) {
        is SyncEditorTarget.Add -> ScheduledSyncEditor(
            draft = target.draft,
            existing = null,
            onDismiss = { editing = null },
            onSave = { name, direction, interval, wifi, charging, deleteExtras ->
                model.scheduledSync.add(name, target.draft, direction, interval.minutes, wifi, charging, deleteExtras)
                editing = null
            },
        )

        is SyncEditorTarget.Edit -> ScheduledSyncEditor(
            draft = target.job.toDraft(),
            existing = target.job,
            onDismiss = { editing = null },
            onSave = { name, direction, interval, wifi, charging, deleteExtras ->
                model.scheduledSync.update(
                    target.job.copy(
                        name = name,
                        direction = direction.name,
                        intervalMinutes = interval.minutes,
                        requiresWifi = wifi,
                        requiresCharging = charging,
                        deleteExtras = deleteExtras,
                    ),
                )
                editing = null
            },
        )

        null -> Unit
    }

    removing?.let { job ->
        OloConfirmDialog(
            title = stringResource(R.string.sched_remove_confirm),
            detail = stringResource(R.string.sched_remove_confirm_detail),
            confirmLabel = stringResource(R.string.sched_remove),
            onDismiss = { removing = null },
            onConfirm = {
                model.scheduledSync.delete(job)
                removing = null
            },
        )
    }

    if (hint) {
        OloConfirmDialog(
            title = stringResource(R.string.sched_add),
            detail = stringResource(R.string.sched_needs_panes),
            confirmLabel = stringResource(R.string.action_close),
            onDismiss = { hint = false },
            onConfirm = { hint = false },
        )
    }
}

/** Which thing the editor is open on. */
private sealed interface SyncEditorTarget {
    data class Add(val draft: SyncJobDraft) : SyncEditorTarget
    data class Edit(val job: SyncJobEntity) : SyncEditorTarget
}

private fun SyncJobEntity.toDraft() = SyncJobDraft(
    localRoot = localRoot,
    localLabel = localLabel,
    siteId = siteId,
    remoteRoot = remoteRoot,
    remoteLabel = remoteLabel,
    suggestedName = name,
)

@Composable
private fun SyncJobCard(
    job: SyncJobEntity,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val upload = job.directionEnum == SyncDirection.UPLOAD
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (upload) Icons.Filled.CloudUpload else Icons.Filled.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    job.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                EndsRow(from = if (upload) job.localLabel else job.remoteLabel, to = if (upload) job.remoteLabel else job.localLabel)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    Text(
                        scheduleLine(job),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
                LastRunRow(job)
            }
            Switch(checked = job.enabled, onCheckedChange = onToggle)
            Box {
                androidx.compose.material3.IconButton(onClick = { menu = true }) {
                    Icon(painterResource(R.drawable.ic_menu_more), contentDescription = stringResource(R.string.menu_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sched_run_now)) },
                        onClick = { menu = false; onRunNow() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sched_edit)) },
                        onClick = { menu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sched_remove)) },
                        onClick = { menu = false; onRemove() },
                    )
                }
            }
        }
    }
}

@Composable
private fun EndsRow(from: String, to: String) {
    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            from,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp).padding(horizontal = 2.dp),
        )
        Text(
            to,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun LastRunRow(job: SyncJobEntity) {
    val colour = when (job.lastStatusEnum) {
        SyncRunStatus.OK -> MaterialTheme.status.done
        SyncRunStatus.PARTIAL -> MaterialTheme.status.paused
        SyncRunStatus.FAILED -> MaterialTheme.status.failed
        SyncRunStatus.NONE -> MaterialTheme.colorScheme.outline
    }
    val text = if (job.lastRunAt == 0L || job.lastResult == null) {
        stringResource(R.string.sched_last_never)
    } else {
        stringResource(R.string.sched_last_at, relativeDay(job.lastRunAt), job.lastResult)
    }
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(colour))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** The schedule as one line: how often, then the conditions, joined by dots. */
@Composable
private fun scheduleLine(job: SyncJobEntity): String {
    val how = if (job.intervalMinutes >= 24 * 60) {
        stringResource(R.string.sched_daily)
    } else {
        stringResource(R.string.sched_every_hours, (job.intervalMinutes / 60).toInt())
    }
    val parts = buildList {
        add(how)
        if (job.requiresWifi) add(stringResource(R.string.sched_cond_wifi))
        if (job.requiresCharging) add(stringResource(R.string.sched_cond_charging))
    }
    return parts.joinToString(" · ")
}

@Composable
private fun relativeDay(time: Long): String = remember(time) {
    android.text.format.DateUtils.getRelativeTimeSpanString(
        time,
        System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledSyncEditor(
    draft: SyncJobDraft,
    existing: SyncJobEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, direction: SyncDirection, interval: SyncInterval, wifi: Boolean, charging: Boolean, deleteExtras: Boolean) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(existing?.name ?: draft.suggestedName) }
    var direction by remember { mutableStateOf(existing?.directionEnum ?: SyncDirection.UPLOAD) }
    var interval by remember { mutableStateOf(existing?.let { SyncInterval.nearest(it.intervalMinutes) } ?: SyncInterval.TWELVE_HOURS) }
    var wifi by remember { mutableStateOf(existing?.requiresWifi ?: true) }
    var charging by remember { mutableStateOf(existing?.requiresCharging ?: false) }
    var deleteExtras by remember { mutableStateOf(existing?.deleteExtras ?: false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        SheetContent {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(
                    stringResource(if (existing == null) R.string.sched_new else R.string.sched_edit),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 10.dp),
                )

                OloTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.sched_field_name),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )

                val upload = direction == SyncDirection.UPLOAD
                ReadOnlyField(stringResource(R.string.sched_field_source), if (upload) draft.localLabel else draft.remoteLabel)
                ReadOnlyField(stringResource(R.string.sched_field_target), if (upload) draft.remoteLabel else draft.localLabel)

                FieldLabel(stringResource(R.string.sched_field_direction))
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(stringResource(R.string.sched_dir_upload), upload, onClick = { direction = SyncDirection.UPLOAD })
                    ChoiceChip(stringResource(R.string.sched_dir_download), !upload, onClick = { direction = SyncDirection.DOWNLOAD })
                }

                FieldLabel(stringResource(R.string.sched_field_interval))
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in SyncInterval.entries) {
                        ChoiceChip(stringResource(option.label), option == interval, onClick = { interval = option })
                    }
                }

                FieldLabel(stringResource(R.string.sched_conditions))
                SwitchRow(stringResource(R.string.sched_wifi_only), wifi) { wifi = it }
                SwitchRow(stringResource(R.string.sched_charging_only), charging) { charging = it }
                SwitchRow(stringResource(R.string.sync_delete_extras), deleteExtras) { deleteExtras = it }

                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                        .clickable { onSave(name, direction, interval, wifi, charging, deleteExtras) },
                ) {
                    Box(Modifier.padding(vertical = 14.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.sched_save),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun ReadOnlyField(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
