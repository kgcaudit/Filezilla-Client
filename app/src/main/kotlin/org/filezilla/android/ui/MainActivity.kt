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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.filezilla.android.R
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

    var screen by remember { mutableStateOf(HOME) }
    var queueMenuOpen by remember { mutableStateOf(false) }
    var editingSite by remember { mutableStateOf<SiteDraft?>(null) }
    var creatingDirectory by remember { mutableStateOf(false) }

    val sites by model.sites.collectAsState()
    val transfers by model.transfers.collectAsState()
    val logLines by model.log.collectAsState()
    val active by model.active.collectAsState()

    // Back does the nearest thing first and leaves last; see [backActionFor].
    // It used to do only the last one, so back from six folders deep closed
    // the app outright.
    var exitArmedAt by remember { mutableStateOf(0L) }
    val exitMessage = stringResource(R.string.exit_confirm)
    BackHandler {
        val armed = System.currentTimeMillis() - exitArmedAt < EXIT_CONFIRM_MILLIS
        when (
            backActionFor(
                screen = screen,
                selecting = model.browse.selecting,
                canGoUp = screen == Screen.FILES && model.canGoUp(model.activePane),
                exitArmed = armed,
            )
        ) {
            BackAction.CLEAR_SELECTION -> model.clearSelection()
            BackAction.GO_UP -> model.goUp()
            BackAction.CLOSE_SCREEN -> screen = HOME
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

    /**
     * Says why a download cannot start, and offers the tap that fixes it.
     *
     * Refusing rather than quietly using Downloads is the decision this
     * design rests on: a file that lands somewhere the user is not looking is
     * how "where did it go" happens.
     */
    fun refuseDownload(why: Destination) {
        val from = model.activePane
        scope.launch {
            val message = when (why) {
                Destination.NoStorageAccess -> context.getString(R.string.download_needs_storage)
                else -> context.getString(R.string.download_needs_local_pane)
            }
            val action = when (why) {
                Destination.NoStorageAccess -> context.getString(R.string.storage_grant_short)
                else -> context.getString(R.string.download_point_other_pane)
            }
            val pressed = snackbars.showSnackbar(
                message = message,
                actionLabel = action,
                duration = SnackbarDuration.Long,
            )
            if (pressed == SnackbarResult.ActionPerformed) {
                if (why == Destination.NoStorageAccess) {
                    requestStorageAccess()
                } else {
                    // The pane the download would have gone to, pointed at
                    // the phone. One tap, and where it goes is then on screen.
                    model.showLocal(model.facing(from))
                }
            }
        }
    }

    fun startDownload(entry: org.filezilla.ftp.listing.DirectoryEntry) {
        val destination = model.downloadDestination()
        if (destination !is Destination.Folder) {
            refuseDownload(destination)
            return
        }
        requestNotifications()
        // A file and a folder take the same path on purpose. A file used to
        // have one of its own, and it was the one that never looked in the
        // destination folder first -- so downloading the same file twice
        // asked nothing and quietly saved a second numbered copy.
        model.enqueueEntry(entry) { plan ->
            if (plan.files.isNotEmpty()) TransferService.start(context)
            scope.launch { snackbars.showSnackbar(queuedEntryMessage(entry, plan)) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            // Only for a screen that opens over the files screen. The
            // files screen has no bar of its own: every action on it
            // belongs to one pane or the other and lives in that pane's
            // header, so all that was left up here was a title -- and the
            // tab strip, the pane header and the first crumb of the path
            // were already saying the same word.
            if (screen != HOME) {
                TopAppBar(
                    title = {
                        Text(
                            titleFor(screen),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { screen = HOME }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                    // A surface, not the brand colour. A deep blue bar
                    // covered about a fifth of every screen, and against
                    // that much saturation the status colours -- the ones
                    // that actually mean something here -- read as muted.
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    actions = {
                        when (screen) {
                            Screen.QUEUE -> {
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
                        Screen.LOG -> IconButton(onClick = { model.clearLog() }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.log_clear))
                        }

                            Screen.SITES, Screen.FILES -> Unit
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (screen == Screen.SITES) {
                FloatingActionButton(
                    onClick = { editingSite = model.newSite() },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.sites_add))
                }
            }
        },
    ) { padding ->
        when (screen) {
            Screen.SITES -> SitesScreen(
                sites = sites,
                editing = editingSite,
                onEdit = { site -> editingSite = site?.let(model::draftOf) },
                onSave = model::saveSite,
                onDelete = model::deleteSite,
                onConnect = { site ->
                    model.connect(site)
                    screen = Screen.FILES
                },
                onMove = model::moveSite,
                modifier = Modifier.padding(padding),
            )

            // Not padded by the Scaffold: with no bar above it or below
            // it, the files screen runs from the status bar to the
            // gesture bar and takes those insets itself -- the tab strip
            // at the top, the list at the bottom. There is nothing left
            // for the Scaffold to inset it from.
            Screen.FILES -> FilePanes(
                model = model,
                options = model.options,
                onNewDirectory = { creatingDirectory = true },
                onUpload = {
                    requestNotifications()
                    uploadPicker.launch(arrayOf("*/*"))
                },
                onOpenScreen = { screen = it },
                onOpenLog = { screen = Screen.LOG },
                onGrant = ::requestStorageAccess,
                onPickSite = { screen = Screen.SITES },
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
                    val destination = model.downloadDestination()
                    if (destination !is Destination.Folder) {
                        refuseDownload(destination)
                    } else {
                        requestNotifications()
                        model.enqueueSelected { plan ->
                            if (plan.files.isNotEmpty()) TransferService.start(context)
                            scope.launch { snackbars.showSnackbar(queuedPlanMessage(plan)) }
                        }
                    }
                },
                onTransfersQueued = { count ->
                    TransferService.start(context)
                    scope.launch {
                        snackbars.showSnackbar(context.getString(R.string.queued_many, count))
                    }
                },
                modifier = Modifier.padding(padding),
            )

            Screen.QUEUE -> QueueScreen(
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

            Screen.LOG -> LogScreen(lines = logLines, modifier = Modifier.padding(padding))
        }
    }

    model.browse.properties?.let { entry ->
        PropertiesDialog(
            entry = entry,
            path = model.browse.path,
            onDismiss = { model.showProperties(null) },
        )
    }


    if (creatingDirectory) {
        OloPromptDialog(
            title = R.string.new_folder_title,
            detail = R.string.new_folder_detail,
            label = R.string.prompt_name,
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
private fun titleFor(screen: Screen): String = when (screen) {
    Screen.SITES -> stringResource(R.string.title_sites)
    Screen.QUEUE -> stringResource(R.string.title_queue)
    Screen.LOG -> stringResource(R.string.title_log)
    // Never asked for: the files screen carries no bar. Named rather than
    // left to an else, so adding a screen is a compile error here.
    Screen.FILES -> ""
}

