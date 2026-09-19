package org.filezilla.android.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.filezilla.android.R
import org.filezilla.android.data.SiteEntity
import org.filezilla.android.files.OpenFile
import org.filezilla.android.service.TransferService
import org.filezilla.android.storage.ConflictChoice
import org.filezilla.android.ui.theme.OloTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app bar runs under the status bar, so the status bar itself is
        // transparent and takes the app bar's colour. Which way its icons
        // should face depends on the theme, so OloTheme decides that.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        setContent {
            OloTheme {
                AppScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(model: MainViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }

    var tab by remember { mutableStateOf(Tab.SITES) }
    var queueMenuOpen by remember { mutableStateOf(false) }
    var editingSite by remember { mutableStateOf<SiteDraft?>(null) }
    var creatingDirectory by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    val sites by model.sites.collectAsState()
    val transfers by model.transfers.collectAsState()
    val logLines by model.log.collectAsState()
    val active by model.active.collectAsState()

    // Remembers which file the user tapped while there was still no download
    // folder, so choosing one finishes the job instead of making them tap the
    // file again.
    var pendingDownload by remember { mutableStateOf<org.filezilla.ftp.listing.DirectoryEntry?>(null) }

    // Back does the nearest thing first and leaves last; see [backActionFor].
    // It used to do only the last one, so back from six folders deep closed
    // the app outright.
    var exitArmedAt by remember { mutableStateOf(0L) }
    val exitMessage = stringResource(R.string.exit_confirm)
    BackHandler {
        val armed = System.currentTimeMillis() - exitArmedAt < EXIT_CONFIRM_MILLIS
        when (
            backActionFor(
                tab = tab,
                selecting = model.browse.selecting,
                canGoUp = tab == Tab.BROWSE && model.canGoUp(model.activePane),
                exitArmed = armed,
            )
        ) {
            BackAction.CLEAR_SELECTION -> model.clearSelection()
            BackAction.GO_UP -> model.goUp()
            BackAction.SHOW_FIRST_TAB -> tab = FIRST_TAB
            BackAction.CONFIRM_EXIT -> {
                exitArmedAt = System.currentTimeMillis()
                scope.launch {
                    // Short, and replacing whatever is up: this is a prompt
                    // for the next two seconds, not a report.
                    snackbars.currentSnackbarData?.dismiss()
                    snackbars.showSnackbar(exitMessage, duration = SnackbarDuration.Short)
                }
            }
            // finish() rather than passing the press on: re-dispatching it
            // from inside the handler that caught it comes straight back
            // here, and the app never closes at all.
            BackAction.EXIT -> (context as? android.app.Activity)?.finish()
        }
    }

    val uploadQueuedMessage = stringResource(R.string.queue_upload_toast)

    // Formatted through the context rather than by patching an already
    // rendered string. Rendering with a placeholder and substituting it back
    // works only until a translation happens to contain the same characters
    // somewhere else, and then it corrupts the wrong part of the sentence.
    fun queuedMessage(name: String) = context.getString(R.string.queue_queued_toast, name)
    /** What to say once a selection has been walked and queued. */
    fun queuedPlanMessage(plan: DownloadPlan): String = when {
        plan.files.isEmpty() -> context.getString(R.string.queued_none)
        plan.truncated -> context.getString(R.string.queued_truncated, plan.files.size)
        plan.skippedLinks > 0 ->
            context.getString(R.string.queued_skipped_links, plan.files.size, plan.skippedLinks)
        else -> context.getString(R.string.queued_many, plan.files.size)
    }

    /**
     * What to say about one row the user asked for.
     *
     * A single file is named rather than counted -- "One.Night.Only.mkv" says
     * more than "1 file" when that is all there was -- but only when it was
     * actually queued. A file the user chose to skip has an empty plan, and
     * saying it was queued would be the wrong answer to their own choice.
     */
    fun queuedEntryMessage(entry: org.filezilla.ftp.listing.DirectoryEntry, plan: DownloadPlan) =
        if (entry.isDirectory || plan.files.size != 1) {
            queuedPlanMessage(plan)
        } else {
            queuedMessage(entry.name)
        }

    // Two launchers because there are two ways in; see AccessRoute. Which one
    // is used is the platform's decision, not a preference.
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { model.refreshStorageAccess() }

    val storageSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { model.refreshStorageAccess() }

    fun requestStorageAccess() {
        val intent = model.storageSettingsIntent()
        if (intent != null) {
            storageSettings.launch(intent)
        } else {
            model.storagePermissionsToRequest()
                .takeIf { it.isNotEmpty() }
                ?.let(storagePermission::launch)
        }
    }

    // Granting all-files access happens in Settings, so the user leaves the
    // app to do it and something has to notice they came back having said
    // yes. Without this the pane stays on its permission screen until the
    // app is restarted, which reads as the grant not having worked.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) model.refreshStorageAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The transfer runs either way; without it the progress is just invisible. */ }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { tree: Uri? ->
        if (tree == null) return@rememberLauncherForActivityResult
        model.chooseDownloadFolder(tree)
        pendingDownload?.let { entry ->
            pendingDownload = null
            // The same two cases as startDownload, which cannot be called from
            // here: it is declared below, because it is what launches this
            // picker when there is no folder yet.
            model.enqueueEntry(entry) { plan ->
                if (plan.files.isNotEmpty()) TransferService.start(context)
                scope.launch { snackbars.showSnackbar(queuedEntryMessage(entry, plan)) }
            }
        }
    }

    val uploadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source: Uri? ->
        if (source == null) return@rememberLauncherForActivityResult
        model.enqueueUpload(source) { count ->
            // Zero means it is waiting on the conflict dialog, or the user
            // chose to keep what was there. Nothing to start either way.
            if (count > 0) TransferService.start(context)
        }
        scope.launch { snackbars.showSnackbar(uploadQueuedMessage) }
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun startDownload(entry: org.filezilla.ftp.listing.DirectoryEntry) {
        requestNotifications()
        // A file and a folder take the same path on purpose. A file used to
        // have one of its own, and it was the one that never looked in the
        // destination folder first -- so downloading the same file twice
        // asked nothing and quietly saved a second numbered copy.
        val queued = model.enqueueEntry(entry) { plan ->
            if (plan.files.isNotEmpty()) TransferService.start(context)
            scope.launch { snackbars.showSnackbar(queuedEntryMessage(entry, plan)) }
        }
        if (!queued) {
            // Nowhere to put it yet: ask first, rather than spending the
            // user's data on a file with no destination.
            pendingDownload = entry
            folderPicker.launch(null)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        // No selection title: the bar along the bottom says
                        // what is picked, and two bars saying it at once was
                        // one of them saying it twice.
                        titleFor(tab, model),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                // A surface, not the brand colour. A deep blue bar covered
                // about a fifth of every screen, and against that much
                // saturation the status colours -- which are the ones that
                // actually mean something here -- read as muted. The brand is
                // now what marks an action, not what fills a background.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                actions = {
                    when (tab) {
                        // Nothing here: every action on this screen belongs to
                        // one pane or the other, and a bar above both cannot
                        // say which. They live in each pane's own header now.
                        Tab.BROWSE -> Unit

                        Tab.QUEUE -> {
                            IconButton(onClick = { model.clearCompleted() }) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.queue_clear_finished))
                            }
                            IconButton(onClick = { TransferService.start(context) }) {
                                Icon(Icons.Filled.SwapVert, contentDescription = stringResource(R.string.queue_start))
                            }
                            IconButton(onClick = { queueMenuOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.queue_settings),
                                )
                            }
                            DropdownMenu(
                                expanded = queueMenuOpen,
                                onDismissRequest = { queueMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(stringResource(R.string.setting_wifi_only))
                                            Text(
                                                stringResource(R.string.setting_wifi_only_detail),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        Switch(
                                            checked = model.wifiOnly,
                                            onCheckedChange = { model.applyWifiOnly(it) },
                                        )
                                    },
                                    onClick = { model.applyWifiOnly(!model.wifiOnly) },
                                )
                            }
                        }

                        Tab.LOG -> IconButton(onClick = { model.clearLog() }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.log_clear))
                        }

                        Tab.SITES -> Unit
                    }
                },
            )
        },
        floatingActionButton = {
            if (tab == Tab.SITES) {
                FloatingActionButton(
                    onClick = { editingSite = model.newSite() },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.sites_add))
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Tab.entries.forEach { candidate ->
                    NavigationBarItem(
                        selected = tab == candidate,
                        onClick = { tab = candidate },
                        icon = {
                            Icon(
                                painter = painterResource(iconFor(candidate)),
                                contentDescription = null,
                                // Unbounded, so the artwork keeps its own
                                // colours instead of being flattened to one.
                                tint = Color.Unspecified,
                                modifier = Modifier.size(26.dp),
                            )
                        },
                        label = { Text(stringResource(candidate.label)) },
                        // Material shows labels on every item up to three and
                        // only on the selected one from four up, which is
                        // what this is. The label stays in the tree either
                        // way, so a screen reader still announces it.
                        alwaysShowLabel = false,
                        // The selected tab's indicator is the same pale chip
                        // the rows give these icons, for the same reason: on
                        // the dark theme's indicator the artwork's deep blue
                        // sat on deeper blue and the selected tab was the
                        // hardest one to make out.
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = FlatIconChip,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            Tab.SITES -> SitesScreen(
                sites = sites,
                editing = editingSite,
                onEdit = { site -> editingSite = site?.let(model::draftOf) },
                onSave = model::saveSite,
                onDelete = model::deleteSite,
                onConnect = { site ->
                    model.connect(site)
                    tab = Tab.BROWSE
                },
                onMove = model::moveSite,
                modifier = Modifier.padding(padding),
            )

            Tab.BROWSE -> FilePanes(
                model = model,
                options = model.options,
                downloadFolderName = model.downloadFolderName,
                onNewDirectory = { creatingDirectory = true },
                onUpload = {
                    requestNotifications()
                    uploadPicker.launch(arrayOf("*/*"))
                },
                onChooseFolder = { folderPicker.launch(null) },
                onOpenLog = { tab = Tab.LOG },
                onGrant = ::requestStorageAccess,
                onPickSite = { tab = Tab.SITES },
                onDownload = ::startDownload,
                onRequestNotifications = ::requestNotifications,
                onOpenLocalFile = { path ->
                    val intent = OpenFile.intentFor(context, java.io.File(path))
                    val opened = intent != null && runCatching { context.startActivity(intent) }
                        .isSuccess
                    // Said rather than swallowed: a phone with nothing that
                    // opens a .srt is a fair state, and silence from a tap
                    // looks like the app ignoring it.
                    if (!opened) {
                        scope.launch { snackbars.showSnackbar(context.getString(R.string.open_no_app)) }
                    }
                },
                onDownloadSelected = {
                    requestNotifications()
                    val queued = model.enqueueSelected { plan ->
                        if (plan.files.isNotEmpty()) TransferService.start(context)
                        scope.launch { snackbars.showSnackbar(queuedPlanMessage(plan)) }
                    }
                    if (!queued) folderPicker.launch(null)
                },
                onTransfersQueued = { count ->
                    TransferService.start(context)
                    scope.launch {
                        snackbars.showSnackbar(context.getString(R.string.queued_many, count))
                    }
                },
                modifier = Modifier.padding(padding),
            )

            Tab.QUEUE -> QueueScreen(
                transfers = transfers,
                active = active,
                onPause = model::pause,
                onResume = { id ->
                    model.resume(id)
                    TransferService.start(context)
                    // Restarting a transfer the network still rules out puts
                    // it straight back to waiting. Saying so beats letting the
                    // button look broken.
                    if (!model.transfersAllowedNow()) {
                        scope.launch {
                            snackbars.showSnackbar(context.getString(R.string.queue_still_waiting_wifi))
                        }
                    }
                },
                onCancel = model::cancel,
                modifier = Modifier.padding(padding),
            )

            Tab.LOG -> LogScreen(lines = logLines, modifier = Modifier.padding(padding))
        }
    }

    model.browse.properties?.let { entry ->
        PropertiesDialog(
            entry = entry,
            path = model.browse.path,
            onDismiss = { model.showProperties(null) },
        )
    }

    if (confirmingDelete) {
        val count = model.browse.selection.size
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.confirm_delete_title, count)) },
            text = { Text(stringResource(R.string.confirm_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    model.deleteSelected()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (creatingDirectory) {
        TextPromptDialog(
            title = stringResource(R.string.browse_new_directory),
            label = stringResource(R.string.prompt_name),
            onDismiss = { creatingDirectory = false },
            onConfirm = { name ->
                model.createDirectory(name)
                creatingDirectory = false
            },
        )
    }

    // The server already has files of these names. Asked before anything is
    // sent, for the same reason a download asks: answering "skip" then costs
    // no data at all.
    model.pendingUploadConflicts?.let { pending ->
        ConflictDialog(
            conflicts = pending.conflicts,
            onChoose = { choice ->
                requestNotifications()
                model.resolveUploadConflicts(choice) { count ->
                    if (count > 0) TransferService.start(context)
                    scope.launch {
                        snackbars.showSnackbar(
                            if (count == 0) {
                                context.getString(R.string.conflict_skipped_all)
                            } else {
                                context.getString(R.string.queued_many, count)
                            },
                        )
                    }
                }
            },
            onDismiss = model::dismissUploadConflicts,
        )
    }

    // A paste inside the phone asks the same question, for the same reason.
    // It did not: the file operations refuse to write over anything, so a
    // paste onto a name already there failed outright and the pane came back
    // looking exactly as it had -- as though nothing had been pasted at all.
    model.pendingPasteConflicts?.let { pending ->
        ConflictDialog(
            conflicts = pending.conflicts,
            onChoose = model::resolvePasteConflicts,
            onDismiss = model::dismissPasteConflicts,
        )
    }

    // Nothing has been fetched at this point: the walk found the names, and
    // the transfer waits on the answer.
    model.pendingConflicts?.let { pending ->
        ConflictDialog(
            conflicts = pending.conflicts,
            onChoose = { choice ->
                requestNotifications()
                model.resolveConflicts(choice) { plan ->
                    if (plan.files.isNotEmpty()) TransferService.start(context)
                    scope.launch {
                        // "Nothing to download" would be wrong here: there was
                        // something, and the user chose to keep what they had.
                        val message = if (choice == ConflictChoice.SKIP && plan.files.isEmpty()) {
                            context.getString(R.string.conflict_skipped_all)
                        } else {
                            queuedPlanMessage(plan)
                        }
                        snackbars.showSnackbar(message)
                    }
                }
            },
            onDismiss = model::dismissConflicts,
        )
    }
}

@Composable
private fun titleFor(tab: Tab, model: MainViewModel): String = when (tab) {
    Tab.SITES -> stringResource(R.string.title_sites)
    // The connected server's own name, when there is one: on this screen it
    // says more than the word "Files" does.
    Tab.BROWSE -> model.browse.site?.name?.ifBlank { model.browse.site?.host.orEmpty() }
        ?: stringResource(R.string.title_browse)
    Tab.QUEUE -> stringResource(R.string.title_queue)
    Tab.LOG -> stringResource(R.string.title_log)
}

/**
 * The tab icons, which are the app's own artwork rather than Material glyphs.
 *
 * These are identity, not controls: they say what each place is, and they are
 * the same four shapes wherever those places appear. Being multi-coloured they
 * do not tint with selection, so the bar's own indicator is what marks the
 * selected tab -- which is how it reads anyway, the colour being a repeat of
 * something the shape already said.
 */
@DrawableRes
private fun iconFor(tab: Tab): Int = when (tab) {
    Tab.SITES -> R.drawable.ic_flat_server
    Tab.BROWSE -> R.drawable.ic_flat_folder
    Tab.QUEUE -> R.drawable.ic_flat_transfers
    Tab.LOG -> R.drawable.ic_flat_log
}
