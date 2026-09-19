package org.filezilla.android.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import org.filezilla.android.service.TransferService
import org.filezilla.android.storage.ConflictChoice
import org.filezilla.android.ui.theme.OloTheme

private enum class Tab(val label: Int) {
    SITES(R.string.tab_sites),
    BROWSE(R.string.tab_browse),
    QUEUE(R.string.tab_queue),
    LOG(R.string.tab_log),
}

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
    var viewOptionsOpen by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    val sites by model.sites.collectAsState()
    val transfers by model.transfers.collectAsState()
    val logLines by model.log.collectAsState()
    val active by model.active.collectAsState()

    // Remembers which file the user tapped while there was still no download
    // folder, so choosing one finishes the job instead of making them tap the
    // file again.
    var pendingDownload by remember { mutableStateOf<org.filezilla.ftp.listing.DirectoryEntry?>(null) }

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
        model.enqueueUpload(source) { TransferService.start(context) }
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
                        if (tab == Tab.BROWSE && model.browse.selecting) {
                            stringResource(R.string.menu_selected, model.browse.selection.size)
                        } else {
                            titleFor(tab, model)
                        },
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
                colors = if (tab == Tab.BROWSE && model.browse.selecting) {
                    // Selecting is a mode, and a mode should look like one. A
                    // tint rather than a saturated fill, so it stands out
                    // from the plain bar without going back to the wall of
                    // colour this change is undoing.
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                navigationIcon = {
                    if (tab == Tab.BROWSE && model.browse.selecting) {
                        IconButton(onClick = model::clearSelection) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.menu_select_none),
                            )
                        }
                    }
                },
                actions = {
                    if (tab == Tab.BROWSE && model.browse.selecting) {
                        IconButton(onClick = {
                            requestNotifications()
                            val queued = model.enqueueSelected { plan ->
                                if (plan.files.isNotEmpty()) TransferService.start(context)
                                scope.launch {
                                    snackbars.showSnackbar(queuedPlanMessage(plan))
                                }
                            }
                            // No folder chosen yet: ask, exactly as a single
                            // download does, rather than failing quietly.
                            if (!queued) folderPicker.launch(null)
                        }) {
                            Icon(
                                Icons.Filled.Download,
                                contentDescription = stringResource(R.string.action_download_selected),
                            )
                        }
                        IconButton(
                            onClick = { confirmingDelete = true },
                            enabled = model.browse.selection.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete_selected),
                            )
                        }
                        return@TopAppBar
                    }
                    when (tab) {
                        Tab.BROWSE -> if (model.browse.site != null) {
                            BrowseQuickActions(
                                onNewDirectory = { creatingDirectory = true },
                                onUpload = {
                                    requestNotifications()
                                    uploadPicker.launch(arrayOf("*/*"))
                                },
                            )
                            BrowseOverflow(
                                options = model.options,
                                filterOpen = model.browse.filterOpen,
                                onSelectMode = model::toggleSelectionMode,
                                onSelectAll = model::selectAll,
                                onToggleFilter = model::toggleFilter,
                                onViewOptions = { viewOptionsOpen = true },
                                onChooseFolder = { folderPicker.launch(null) },
                                onRefresh = model::refresh,
                                onOptions = model::applyOptions,
                            )
                        }

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
                modifier = Modifier.padding(padding),
            )

            Tab.BROWSE -> BrowseScreen(
                state = model.browse,
                rows = model.visibleEntries,
                options = model.options,
                downloadFolderName = model.downloadFolderName,
                onUp = model::goUp,
                onRefresh = model::refresh,
                onOpenLog = { tab = Tab.LOG },
                onFilterChange = model::setFilter,
                onCloseFilter = model::toggleFilter,
                actions = EntryActions(
                    onOpen = { model.openDirectory(it.name) },
                    onDownload = ::startDownload,
                    onDelete = model::delete,
                    onRename = model::rename,
                    onProperties = { model.showProperties(it) },
                    onToggleSelected = { model.toggleSelected(it.name) },
                ),
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

    if (viewOptionsOpen) {
        ViewOptionsDialog(
            options = model.options,
            onDismiss = { viewOptionsOpen = false },
            onApply = model::applyOptions,
        )
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
