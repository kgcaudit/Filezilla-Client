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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.filezilla.android.archive.ArchiveNav
import org.filezilla.android.archive.Archives
import kotlinx.coroutines.launch
import org.filezilla.android.R
import org.filezilla.android.files.FileAssociations
import org.filezilla.android.files.InstallApk
import org.filezilla.android.files.OpenFile
import org.filezilla.android.viewer.ImageFiles
import org.filezilla.android.viewer.TextFiles
import org.filezilla.android.files.ShareFiles
import org.filezilla.android.service.TransferService
import org.filezilla.android.storage.ConflictChoice
import org.filezilla.android.ui.theme.OloTheme

class MainActivity : ComponentActivity() {

    /**
     * The screen something outside the app asked for, until it is honoured.
     *
     * State rather than a plain read of `intent`, because the app is usually
     * already running when a notification is tapped: the activity comes
     * forward through onNewIntent, not onCreate, and nothing composed would
     * ever see a new intent that only sat in a field.
     */
    private val openAt = androidx.compose.runtime.mutableStateOf<Screen?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openAt.value = OpenAt.screenFor(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openAt.value = OpenAt.screenFor(intent)
        // The app bar runs under the status bar, so the status bar itself is
        // transparent and takes the app bar's colour. Which way its icons
        // should face depends on the theme, so OloTheme decides that.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        setContent {
            OloTheme {
                AppScreen(openAt = openAt)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(
    model: MainViewModel = viewModel(),
    /** See [MainActivity.openAt]; null once it has been honoured. */
    openAt: androidx.compose.runtime.MutableState<Screen?> =
        androidx.compose.runtime.mutableStateOf(null),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }

    var screen by remember { mutableStateOf(HOME) }

    // Cleared as it is honoured, so pressing back off the transfer list does
    // not land straight back on it.
    LaunchedEffect(openAt.value) {
        openAt.value?.let {
            screen = it
            openAt.value = null
        }
    }
    var queueSettingsOpen by remember { mutableStateOf(false) }
    var emptyingQueue by remember { mutableStateOf(false) }
    // The file waiting on a decision about which app opens it, and whether
    // the remembered choice counts. "Open with" is the way back from a
    // choice made once, so it has to ignore it.
    var openingFile by remember { mutableStateOf<java.io.File?>(null) }
    var askWhichApp by remember { mutableStateOf(false) }
    var installBlockedFor by remember { mutableStateOf<java.io.File?>(null) }
    val associations = remember(context) { FileAssociations(context) }
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

    // The queue's own line along the foot, and only while it has something
    // to say; see [summariseTransfers].
    val queue = remember(transfers, active) {
        summariseTransfers(transfers, active.mapValues { it.value.bytes })
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = {
            // In the Scaffold's own bottom slot rather than at the foot of
            // the files screen. A snackbar is placed above the bottom bar and
            // over everything else, so the strip drawn as content meant
            // "1 queued" landed on top of "1 transferring, 0%" -- the
            // transient message covering the one that was going to stay.
            //
            // Only on the files screen: the queue screen is the thing the
            // strip is a shortcut to.
            if (screen == HOME) {
                queue?.let { summary ->
                    TransferStrip(summary = summary, onOpen = { screen = Screen.QUEUE })
                }
            }
        },
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
                            // Two families, two slots. Running the queue is
                            // one thing at a time, so it is one button
                            // whose face says which; tidying it is several
                            // things that each need a word rather than a
                            // glyph, so they are a menu. See QueueActions.
                            val queue = queueActionsFor(transfers)
                            if (queue.canPause) {
                                IconButton(onClick = { model.pauseAll() }) {
                                    Icon(
                                        Icons.Filled.Pause,
                                        contentDescription = stringResource(R.string.queue_pause_all),
                                    )
                                }
                            } else if (queue.canStart) {
                                // The same arrow as a card's "resume",
                                // because it is the same idea: make the
                                // transfers go.
                                IconButton(
                                    onClick = {
                                        requestNotifications()
                                        model.startAll { TransferService.start(context) }
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = stringResource(R.string.queue_start_all),
                                    )
                                }
                            }
                            QueueOverflow(
                                actions = queue,
                                onClearFinished = model::clearCompleted,
                                onClearFailed = model::clearFailed,
                                onClearAll = { emptyingQueue = true },
                                onSettings = { queueSettingsOpen = true },
                            )
                        }
                        // The same broom as above: one glyph for one verb,
                        // wherever it is. These were both the list icon,
                        // which meant the same picture stood for two
                        // different things that were not lists.
                        Screen.LOG -> IconButton(onClick = { model.clearLog() }) {
                            Icon(
                                Icons.Filled.DeleteSweep,
                                contentDescription = stringResource(R.string.log_clear),
                            )
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
                onOpenLocalFile = { id, path ->
                    val file = java.io.File(path)
                    // Only the three extensions this app browses open here,
                    // and only if the bytes agree. An apk, a docx, a jar are
                    // all zip underneath, so a magic-byte check would open
                    // them in an archive list rather than in the app that
                    // reads them -- the apk that opened as an archive. A
                    // plain tap means "let me see inside"; the overflow's
                    // "open with" still hands the file to another app.
                    when {
                        ArchiveNav.browsable(file.name) && Archives.kindOf(file) != null ->
                            model.openArchive(id, file, file.parent ?: model.pane(id).path)
                        InstallApk.isPackage(file.name) && !InstallApk.allowed(context) ->
                            installBlockedFor = file
                        // The phone's own text and images open in the app's
                        // readers -- text editable, images swiped through the
                        // folder. "Open with" in the overflow still hands the
                        // file to another app for anyone who wants that.
                        TextFiles.looksTextual(file.name) && file.length() <= TextFiles.MAX_BYTES ->
                            model.openTextViewer(file, editable = true)
                        ImageFiles.looksImage(file.name) ->
                            model.openLocalImage(id, file)
                        else -> {
                            askWhichApp = false
                            openingFile = file
                        }
                    }
                },
                onOpenLocalFileWith = { path ->
                    val file = java.io.File(path)
                    if (InstallApk.isPackage(file.name) && !InstallApk.allowed(context)) {
                        installBlockedFor = file
                    } else {
                        askWhichApp = true
                        openingFile = file
                    }
                },
                onShareLocal = { paths ->
                    val intent = ShareFiles.intentFor(context, paths.map { java.io.File(it) })
                    val chooser = intent?.let {
                        Intent.createChooser(it, context.getString(R.string.action_share_selected))
                    }
                    val shared = chooser != null && runCatching { context.startActivity(chooser) }
                        .isSuccess
                    // Same reason as opening a file: a phone with nothing
                    // that accepts these is a fair state, and a button that
                    // does nothing looks broken.
                    if (!shared) {
                        scope.launch { snackbars.showSnackbar(context.getString(R.string.share_no_app)) }
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


    // An apk this app is not yet allowed to hand over. Asked before the
    // installer is launched, because an installer that turns the app away
    // does it silently: no error, no dialog, nothing to read and nothing to
    // fix. See InstallApk.
    installBlockedFor?.let { file ->
        OloDialog(
            title = stringResource(R.string.install_blocked_title),
            detail = stringResource(R.string.install_blocked_detail, file.name),
            onDismiss = { installBlockedFor = null },
            action = {
                ConfirmButton(
                    text = stringResource(R.string.install_blocked_settings),
                    onClick = {
                        runCatching { context.startActivity(InstallApk.settingsIntent(context)) }
                        installBlockedFor = null
                    },
                )
            },
        )
    }

    openingFile?.let { file ->
        // The remembered choice first, and straight there: somebody who has
        // said "always open .srt this way" has asked not to be asked.
        val remembered = if (askWhichApp) null else associations.appForFile(file.name)
        val wentStraight = remembered != null && runCatching {
            context.startActivity(OpenFile.intentFor(context, file, app = remembered))
        }.isSuccess
        if (wentStraight) {
            openingFile = null
        } else {
            // A remembered app that will not start is one that has been
            // uninstalled or renamed. Forgotten, so the user is asked again
            // rather than left with a tap that does nothing for ever.
            if (remembered != null) {
                FileAssociations.extensionOf(file.name)?.let(associations::forget)
            }
            val candidates = remember(file) { OpenFile.candidatesFor(context, file) }
            OpenWithSheet(
                fileName = file.name,
                candidates = candidates,
                onPick = { candidate, keep ->
                    if (keep) {
                        FileAssociations.extensionOf(file.name)?.let { extension ->
                            associations.remember(extension, candidate.component)
                        }
                    }
                    val intent = OpenFile.intentFor(
                        context,
                        file,
                        type = candidate.type,
                        app = candidate.component,
                    )
                    val opened = intent != null &&
                        runCatching { context.startActivity(intent) }.isSuccess
                    if (!opened) {
                        scope.launch {
                            snackbars.showSnackbar(context.getString(R.string.open_no_app))
                        }
                    }
                    openingFile = null
                },
                onSystemChooser = {
                    // The widest possible ask, through the system's own
                    // chooser: the last resort for a file nothing declared.
                    val intent = OpenFile.intentFor(context, file, type = OpenFile.FALLBACK)
                    val chooser = intent?.let {
                        Intent.createChooser(it, context.getString(R.string.open_with_title))
                    }
                    val opened = chooser != null &&
                        runCatching { context.startActivity(chooser) }.isSuccess
                    if (!opened) {
                        scope.launch {
                            snackbars.showSnackbar(context.getString(R.string.open_no_app))
                        }
                    }
                    openingFile = null
                },
                onDismiss = { openingFile = null },
            )
        }
    }

    if (queueSettingsOpen) {
        // Counted when the sheet opens rather than watched: it changes
        // when a file is fetched and when Android takes the space back,
        // and nothing tells the app about the second.
        var cacheBytes by remember { mutableStateOf(model.viewCacheBytes()) }
        QueueSettingsSheet(
            wifiOnly = model.wifiOnly,
            onWifiOnly = model::applyWifiOnly,
            cacheBytes = cacheBytes,
            onEmptyCache = {
                model.emptyViewCache()
                cacheBytes = model.viewCacheBytes()
            },
            onDismiss = { queueSettingsOpen = false },
        )
    }

    if (emptyingQueue) {
        // Asked, because it reaches the transfers that are still running
        // and the partial bytes they have already paid for.
        OloConfirmDialog(
            title = stringResource(R.string.queue_clear_all_title),
            detail = stringResource(R.string.queue_clear_all_detail),
            confirmLabel = stringResource(R.string.queue_clear_all_confirm),
            onDismiss = { emptyingQueue = false },
            onConfirm = {
                model.clearAllTransfers()
                emptyingQueue = false
            },
        )
    }

    if (creatingDirectory) {
        OloPromptDialog(
            title = R.string.new_folder_title,
            confirmLabel = R.string.action_create,
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

    // Above everything else it could collide with, because until this is
    // answered nothing can reach that server at all -- and because the
    // question is not about the operation that happened to trip over it.
    model.certificateQuestion?.let { question ->
        CertificateDialog(
            question = question,
            onTrust = model::trustCertificate,
            onDismiss = model::dismissCertificateQuestion,
        )
    }

    // Above the pane it belongs to, because until it ends nothing else can
    // reach that server: the browse connection is held by one caller at a
    // time, so the pane underneath could not have answered anyway.
    model.serverWork?.let { work ->
        // Keyed on the kind, so the wait before it appears is paid once --
        // a walk that turns into a delete does not go away and come back.
        key(work.kind) {
            ServerWorkDialog(work, onStop = model::stopServerWork)
        }
    }

    model.workOutcome?.let { outcome ->
        LaunchedEffect(outcome) {
            model.outcomeShown()
            snackbars.showSnackbar(
                context.getString(outcome.message, outcome.done, outcome.total),
            )
        }
    }

    model.viewing?.let { ViewingDialog(it, onCancel = model::cancelViewing) }

    model.viewingFailure?.let {
        ViewingFailureDialog(it, onDismiss = model::dismissViewingFailure)
    }

    // Before the file opens, not after: once another app has it, the moment
    // to say what opening means has gone.
    if (model.warnReadOnly) {
        ReadOnlyNotice(onAcknowledge = model::acknowledgeReadOnly)
    }

    // The same door the phone's own files go through, so the remembered
    // app, the chooser and the APK guard are all the ones already written.
    val ready = model.readyToOpen
    LaunchedEffect(ready, model.warnReadOnly) {
        if (ready != null && !model.warnReadOnly) {
            model.openedReady()
            // Archives -- on the phone or fetched from a server -- are opened
            // in the pane before they ever reach here, so this is a file for
            // another app: the installer for a package, the chooser or the
            // remembered app for the rest. A nested archive extracted from
            // inside one does come back through here, and opens in the pane.
            when {
                ArchiveNav.browsable(ready.name) && Archives.kindOf(ready) != null ->
                    model.openArchive(model.activePane, ready, ready.parent ?: "")
                InstallApk.isPackage(ready.name) && !InstallApk.allowed(context) ->
                    installBlockedFor = ready
                // A file fetched from a server, or unpacked from an archive, is
                // a read-only copy: text opens in the viewer but not for
                // editing, and an image opens on its own (its neighbours are
                // still on the server, not here to swipe to).
                TextFiles.looksTextual(ready.name) && ready.length() <= TextFiles.MAX_BYTES ->
                    model.openTextViewer(ready, editable = false)
                ImageFiles.looksImage(ready.name) ->
                    model.openImageViewer(listOf(MainViewModel.ImageRef.OnDisk(ready)), 0, comicKey = null)
                else -> {
                    askWhichApp = false
                    openingFile = ready
                }
            }
        }
    }

    model.archiveConflict?.let { conflict ->
        ArchiveConflictDialog(
            folderName = conflict.folderName,
            onChoose = model::resolveArchiveConflict,
            onDismiss = model::dismissArchiveConflict,
        )
    }

    if (model.archivePasswordAsked) {
        ArchivePasswordDialog(
            wrong = model.archivePasswordWrong,
            onSubmit = model::submitArchivePassword,
            onDismiss = model::dismissArchivePassword,
        )
    }

    // Extract, compress and opening one file from inside all show their
    // progress the same way, as a small modal with a stop button.
    model.archiveBusy?.let { busy -> ArchiveWorkDialog(busy) }

    // While an archive's index is being read. A large one on slow storage
    // takes a moment, and without this a tap on it shows nothing until it
    // is done -- which reads as a tap that did nothing.
    model.archiveOpening?.let { name -> ArchiveOpeningDialog(name) }

    model.archiveOutcome?.let { outcome ->
        LaunchedEffect(outcome) {
            model.archiveOutcomeShown()
            snackbars.showSnackbar(
                context.getString(outcome.message, *outcome.args.toTypedArray()),
            )
        }
    }

    model.changingMode?.let { (pane, entry) ->
        PermissionsDialog(
            entry = entry,
            onApply = { mode, extra -> model.applyMode(pane, entry, mode, extra) },
            onDismiss = model::dismissChangeMode,
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

    // The in-app readers, drawn over everything when open. Composed last so
    // they sit on top of the panes, and left as ordinary full-screen surfaces
    // rather than dialogs so the text editor's keyboard resizes the box the
    // normal way. Back closes the reader before it touches the panes.
    model.imageViewer?.let { viewer ->
        BackHandler { model.closeImageViewer() }
        ImageViewerScreen(viewer, model)
    }
    model.textViewer?.let { viewer ->
        BackHandler { model.closeTextViewer() }
        TextViewerScreen(viewer, model)
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

