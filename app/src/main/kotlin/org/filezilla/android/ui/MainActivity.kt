package org.filezilla.android.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.filezilla.android.data.SiteEntity
import org.filezilla.android.service.TransferService
import org.filezilla.android.ui.theme.FileZillaTheme

private enum class Tab(val label: String) { SITES("Sites"), BROWSE("Browse"), QUEUE("Queue"), LOG("Log") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FileZillaTheme {
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
    var editingSite by remember { mutableStateOf<SiteDraft?>(null) }
    var creatingDirectory by remember { mutableStateOf(false) }

    val sites by model.sites.collectAsState()
    val transfers by model.transfers.collectAsState()
    val logLines by model.log.collectAsState()
    val active by model.active.collectAsState()

    // Remembers which file the user tapped while there was still no download
    // folder, so choosing one finishes the job instead of making them tap the
    // file again.
    var pendingDownload by remember { mutableStateOf<org.filezilla.ftp.listing.DirectoryEntry?>(null) }

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
            if (model.enqueueDownload(entry) { TransferService.start(context) }) {
                scope.launch { snackbars.showSnackbar("${entry.name} queued") }
            }
        }
    }

    val uploadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source: Uri? ->
        if (source == null) return@rememberLauncherForActivityResult
        model.enqueueUpload(source) { TransferService.start(context) }
        scope.launch { snackbars.showSnackbar("Upload queued") }
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun startDownload(entry: org.filezilla.ftp.listing.DirectoryEntry) {
        requestNotifications()
        val queued = model.enqueueDownload(entry) { TransferService.start(context) }
        if (queued) {
            scope.launch { snackbars.showSnackbar("${entry.name} queued") }
        } else {
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
                title = { Text(titleFor(tab, model)) },
                actions = {
                    when (tab) {
                        Tab.BROWSE -> if (model.browse.site != null) {
                            IconButton(onClick = { creatingDirectory = true }) {
                                Icon(Icons.Filled.CreateNewFolder, contentDescription = "New directory")
                            }
                            IconButton(onClick = {
                                requestNotifications()
                                uploadPicker.launch(arrayOf("*/*"))
                            }) {
                                Icon(Icons.Filled.Upload, contentDescription = "Upload a file")
                            }
                            IconButton(onClick = { folderPicker.launch(null) }) {
                                Icon(Icons.Filled.Folder, contentDescription = "Choose the download folder")
                            }
                        }

                        Tab.QUEUE -> {
                            IconButton(onClick = { model.clearCompleted() }) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Clear finished transfers")
                            }
                            IconButton(onClick = { TransferService.start(context) }) {
                                Icon(Icons.Filled.SwapVert, contentDescription = "Start the queue")
                            }
                        }

                        Tab.LOG -> IconButton(onClick = { model.clearLog() }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Clear the log")
                        }

                        Tab.SITES -> Unit
                    }
                },
            )
        },
        floatingActionButton = {
            if (tab == Tab.SITES) {
                FloatingActionButton(onClick = { editingSite = model.newSite() }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add a server")
                }
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { candidate ->
                    NavigationBarItem(
                        selected = tab == candidate,
                        onClick = { tab = candidate },
                        icon = { Icon(iconFor(candidate), contentDescription = null) },
                        label = { Text(candidate.label) },
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
                downloadFolderName = model.downloadFolderName,
                onUp = model::goUp,
                onRefresh = model::refresh,
                onOpen = { model.openDirectory(it.name) },
                onDownload = ::startDownload,
                onDelete = model::delete,
                onRename = model::rename,
                modifier = Modifier.padding(padding),
            )

            Tab.QUEUE -> QueueScreen(
                transfers = transfers,
                active = active,
                onPause = model::pause,
                onResume = { id ->
                    model.resume(id)
                    TransferService.start(context)
                },
                onCancel = model::cancel,
                modifier = Modifier.padding(padding),
            )

            Tab.LOG -> LogScreen(lines = logLines, modifier = Modifier.padding(padding))
        }
    }

    if (creatingDirectory) {
        TextPromptDialog(
            title = "New directory",
            label = "Name",
            onDismiss = { creatingDirectory = false },
            onConfirm = { name ->
                model.createDirectory(name)
                creatingDirectory = false
            },
        )
    }
}

private fun titleFor(tab: Tab, model: MainViewModel): String = when (tab) {
    Tab.SITES -> "Servers"
    Tab.BROWSE -> model.browse.site?.name?.ifBlank { model.browse.site?.host.orEmpty() } ?: "Browse"
    Tab.QUEUE -> "Transfers"
    Tab.LOG -> "Message log"
}

private fun iconFor(tab: Tab) = when (tab) {
    Tab.SITES -> Icons.Filled.Dns
    Tab.BROWSE -> Icons.Filled.Folder
    Tab.QUEUE -> Icons.Filled.SwapVert
    Tab.LOG -> Icons.AutoMirrored.Filled.List
}
