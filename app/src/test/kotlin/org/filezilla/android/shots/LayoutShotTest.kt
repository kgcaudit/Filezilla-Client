package org.filezilla.android.shots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.android.ui.ErrorPanel
import org.filezilla.android.ui.describeFailure
import org.filezilla.android.ui.OloPromptDialog
import org.filezilla.android.ui.OloConfirmDialog
import androidx.compose.ui.res.stringResource
import org.filezilla.android.ui.ConflictDialog
import org.filezilla.android.ui.PropertiesDialog
import org.filezilla.android.ui.ViewOptionsDialog
import org.filezilla.android.ui.SiteEditor
import org.filezilla.android.storage.DownloadConflict
import org.filezilla.android.ui.BrowseOptions
import org.filezilla.android.ui.SortKey
import org.filezilla.android.ui.SiteDraft
import org.filezilla.android.ui.EmptyState
import org.filezilla.android.ui.QueueSettings
import org.filezilla.android.ui.DangerButton
import org.filezilla.android.ui.FileKind
import org.filezilla.android.ui.FileTile
import org.filezilla.android.ui.FlatIcon
import org.filezilla.android.ui.colourFor
import androidx.test.core.app.ApplicationProvider
import org.filezilla.android.data.SiteEntity
import org.filezilla.android.ui.BrowseScreen
import org.filezilla.android.ui.SitesScreen
import org.filezilla.android.ui.FilePanes
import org.filezilla.android.ui.EntryActions
import org.filezilla.android.ui.MainViewModel
import org.filezilla.android.ui.PaneHeader
import org.filezilla.android.ui.PaneId
import org.filezilla.android.ui.PaneSource
import org.filezilla.android.files.OpenFile
import org.filezilla.android.ui.FileAssociationsDialog
import org.filezilla.android.ui.OpenWithSheet
import org.filezilla.android.ui.RememberedApp
import org.filezilla.android.ui.PasteBar
import org.filezilla.android.ui.PasteKind
import org.filezilla.android.ui.SearchDeeperRow
import org.filezilla.android.ui.SearchHit
import org.filezilla.android.ui.SearchResults
import org.filezilla.android.ui.SearchState
import org.filezilla.android.ui.SelectionBar
import org.filezilla.android.ui.TransferStrip
import org.filezilla.android.ui.TransferSummary
import org.filezilla.android.ui.StoragePlaces
import org.filezilla.android.ui.theme.OloTheme
import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.TransferMode
import org.filezilla.ftp.listing.EntryTime
import org.filezilla.ftp.listing.TimeAccuracy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders a piece of the app and writes it out as a PNG.
 *
 * Not an assertion about pixels. A screenshot test that fails on a one-pixel
 * shift is a test that gets deleted, and none of these assert anything beyond
 * "it drew something". This is a way to *look* at a layout without a device:
 * a header crammed until its title had nowhere left to go, or a bar whose
 * colour came out as Material's default pink because the theme never defined
 * the role it asked for, are both obvious in a picture and invisible in the
 * source -- and both of those shipped.
 *
 * The files land in `app/build/shots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class LayoutShotTest {

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    /**
     * Drawn straight onto a bitmap through the view's own draw(), rather than
     * through the test framework's capture, which copies pixels out of a real
     * window's surface -- and under Robolectric there is no surface to copy
     * from, so it waits two seconds for a frame that never arrives.
     */
    private fun shoot(
        name: String,
        width: Int = 1080,
        height: Int = 2340,
        content: @Composable () -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            OloTheme {
                Surface(modifier = Modifier.fillMaxSize()) { Column { content() } }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()

        val root = controller.get().window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()

        val bitmap = Bitmap.createBitmap(
            root.measuredWidth.coerceAtLeast(1),
            root.measuredHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        root.draw(Canvas(bitmap))

        val out = File("build/shots").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.close()
    }

    @Test
    fun `the pane header`() {
        val model = MainViewModel(application)
        shoot("pane-header") {
            PaneHeader(
                id = PaneId.LEFT,
                model = model,
                options = model.options,
                onNewDirectory = {},
                onUpload = {},
                onGrant = {},
                onOpenScreen = {},
            )
        }
    }

    /** The header over a listing, which is how either is actually seen. */
    @Test
    fun `a pane of files`() {
        val model = MainViewModel(application)
        val rows = listOf(
            entry("Camera", directory = true),
            entry("season-01.mkv", size = 1_930_000_000),
            entry("season-01.srt", size = 84_200),
            entry("holiday-2024.jpg", size = 3_400_000),
            entry("icon-pack.zip", size = 2_900_000),
            entry("olo-explorer.apk", size = 16_900_000),
            entry("track01.flac", size = 31_000_000),
            entry("MainViewModel.kt", size = 61_000),
            entry("README", size = 900),
        )
        shoot("pane-of-files") {
            PaneHeader(
                id = PaneId.LEFT,
                model = model,
                options = model.options,
                onNewDirectory = {},
                onUpload = {},
                onGrant = {},
                onOpenScreen = {},
            )
            BrowseScreen(
                state = model.pane(PaneId.LEFT).copy(source = PaneSource.Local, entries = rows),
                rows = rows,
                options = model.options,
                onRefresh = {},
                onOpenLog = {},
                onFilterChange = {},
                onCloseFilter = {},
                onSearchDeeper = {},
                onStopWalking = {},
                onCloseSearch = {},
                onOpenHit = {},
                actions = EntryActions(
                    onOpen = {},
                    onDownload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onProperties = {},
                    onToggleSelected = {},
                ),
            )
        }
    }

    /**
     * The whole app as it actually stacks up, so the chrome can be measured
     * rather than guessed at.
     */
    @Test
    fun `the whole screen`() {
        val controller = Robolectric.buildActivity(
            org.filezilla.android.ui.MainActivity::class.java,
        ).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val root = controller.get().window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        val bitmap = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val out = File("build/shots").apply { mkdirs() }.resolve("whole-screen.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.close()
    }

    /** Every tile, at the size a row shows it, so the set can be compared. */
    @Test
    fun `the file tiles`() {
        shoot("tiles", height = 520) {
            for (row in FileKind.entries.toList().chunked(5)) {
                androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
                    for (kind in row) {
                        androidx.compose.foundation.layout.Column(
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f).padding(14.dp),
                        ) {
                            FileTile(
                                kind = kind,
                                colour = colourFor(kind),
                                contentDescription = null,
                                size = 56.dp,
                                cornerRadius = 16.dp,
                            )
                            androidx.compose.material3.Text(
                                kind.name.lowercase(),
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * A real dialog, drawn from the window it really opens in.
     *
     * The facsimile below came first, because an AlertDialog puts itself in a
     * window of its own and the activity's decorView knows nothing about it.
     * It can be reached, though -- Robolectric keeps the last dialog shown --
     * and it has to be, because the facsimile is a drawing of what somebody
     * believed the dialog looked like. What actually shipped was a new-file
     * box labelled with the site editor's hint, and no drawing of a dialog
     * was ever going to show that.
     */
    private fun shootDialog(name: String, width: Int = 1080, height: Int = 900, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { OloTheme { content() } }
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()) {
            "nothing opened a dialog"
        }
        val root = requireNotNull(dialog.window).decorView
        // AT_MOST, so the card is the height it really is. EXACTLY stretches
        // it down the window and the shot stops saying anything about how much
        // room the dialog takes.
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()

        val bitmap = Bitmap.createBitmap(
            root.measuredWidth.coerceAtLeast(1),
            root.measuredHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        bitmap.eraseColor(android.graphics.Color.WHITE)
        root.draw(Canvas(bitmap))
        File("build/shots").apply { mkdirs() }.resolve("$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** Asking for a name: the dialog whose label was somebody else's. */
    @Test
    fun `the new folder dialog`() {
        shootDialog("dialog-new-folder") {
            OloPromptDialog(
                title = R.string.new_folder_title,
                detail = R.string.new_folder_detail,
                label = R.string.prompt_name,
                confirmLabel = R.string.action_create,
                onDismiss = {},
                onConfirm = {},
            )
        }
    }

    /** And the one that cannot be taken back. */
    @Test
    fun `the delete confirmation`() {
        shootDialog("dialog-delete") {
            OloConfirmDialog(
                title = androidx.compose.ui.res.pluralStringResource(R.plurals.confirm_delete_selected, 3, 3),
                detail = stringResource(R.string.confirm_delete_detail),
                confirmLabel = stringResource(R.string.action_delete),
                onDismiss = {},
                onConfirm = {},
            )
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun `the new folder dialog at night`() {
        shootDialog("dialog-new-folder-night") {
            OloPromptDialog(
                title = R.string.new_folder_title,
                detail = R.string.new_folder_detail,
                label = R.string.prompt_name,
                confirmLabel = R.string.action_create,
                onDismiss = {},
                onConfirm = {},
            )
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun `the delete confirmation at night`() {
        shootDialog("dialog-delete-night") {
            OloConfirmDialog(
                title = androidx.compose.ui.res.pluralStringResource(R.plurals.confirm_delete_selected, 3, 3),
                detail = stringResource(R.string.confirm_delete_detail),
                confirmLabel = stringResource(R.string.action_delete),
                onDismiss = {},
                onConfirm = {},
            )
        }
    }

    /**
     * The other four, so that every dialog in the app has been looked at.
     *
     * The point of the shell is that these carry their own content through
     * one frame. A shot of each is how that stops being a claim: a dialog
     * that quietly stopped matching the rest would show up here rather than
     * on somebody's phone.
     */
    @Test
    fun `the conflict dialog`() {
        shootDialog("dialog-conflict") {
            ConflictDialog(
                conflicts = listOf(
                    DownloadConflict(
                        displayName = "holiday-2024.jpg",
                        remoteSize = 3_500_000,
                        remoteModifiedMillis = 1_726_000_000_000,
                        localSize = 3_412_000,
                        localModifiedMillis = 1_725_000_000_000,
                    ),
                ),
                onChoose = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun `the properties dialog`() {
        shootDialog("dialog-properties", height = 1200) {
            PropertiesDialog(
                entry = DirectoryEntry(
                    name = "season-01.mkv",
                    isDirectory = false,
                    size = 1_932_735_283,
                    permissions = "-rw-r--r--",
                    ownerGroup = "bob staff",
                ),
                path = "/media/shows",
                onDismiss = {},
            )
        }
    }

    @Test
    fun `the open with sheet`() {
        shootDialog("dialog-open-with", height = 1200) {
            OpenWithSheet(
                fileName = "A.Big.Bold.Beautiful.Journey.2025.1080p.ko.srt",
                // Ten of them, which is what overflowed the dialog on the
                // user's phone and pushed the checkbox and the way out off
                // the bottom of the screen.
                candidates = (1..10).map {
                    OpenFile.Candidate(
                        android.content.ComponentName("app$it", "app$it.Main"),
                        "앱 이름 $it",
                        "text/*",
                    )
                },
                onPick = { _, _ -> },
                onSystemChooser = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun `the default apps list`() {
        shootDialog("dialog-associations", height = 900) {
            FileAssociationsDialog(
                associations = listOf(
                    RememberedApp("mkv", "MX Player", "com.example.player"),
                    RememberedApp("srt", "Subtitle Editor", "com.example.editor"),
                ),
                onForget = {},
                onDismiss = {},
            )
        }
    }

    /** The search results, and the offer that leads to them. */
    @Test
    fun `a deep search`() {
        val here = DirectoryEntry(name = "here", isDirectory = false)
        shoot("search-results", height = 900) {
            SearchResults(
                search = SearchState(
                    needle = "나토리",
                    running = false,
                    hits = listOf(
                        SearchHit("/HDD1/Database/MUSIC/2025", here.copy(name = "나토리 - Iris out.mp3")),
                        SearchHit("/HDD1/Database/MOVIE/라이브", here.copy(name = "킨모쿠세이 - 나토리.mkv")),
                        SearchHit("/HDD1/Backup", here.copy(name = "나토리", isDirectory = true)),
                    ),
                    foldersRead = 214,
                ),
                onOpen = {},
                onStopWalking = {},
                onClose = {},
            )
        }
    }

    @Test
    fun `the offer to search deeper`() {
        shoot("search-offer", height = 160) {
            SearchDeeperRow(shown = 4, onSearch = {})
        }
    }

    @Test
    fun `the view options dialog`() {
        shootDialog("dialog-view-options", height = 1200) {
            ViewOptionsDialog(
                options = BrowseOptions(sortKey = SortKey.DATE, ascending = false),
                onlyHere = true,
                onOnlyHere = {},
                onDismiss = {},
                onApply = {},
            )
        }
    }

    /** The bar the share button was added to, in both its shapes. */
    @Test
    fun `the selection bar on the phone`() {
        shoot("bar-selection-local", height = 160) {
            SelectionBar(
                count = 2,
                canRename = false,
                onCut = {},
                onCopy = {},
                onDelete = {},
                onRename = {},
                onClear = {},
                onShare = {},
                canShare = true,
            )
        }
    }

    /** The bar that says "open the folder to move them into". */
    @Test
    fun `the paste bar`() {
        shoot("bar-paste", height = 160) {
            PasteBar(
                count = 3,
                refusal = null,
                kind = PasteKind.FILE_OPERATION,
                cut = true,
                onPaste = {},
                onCancel = {},
                onNewFolder = {},
            )
        }
    }

    @Test
    fun `the selection bar on a server`() {
        shoot("bar-selection-remote", height = 160) {
            SelectionBar(
                count = 2,
                canRename = false,
                onCut = {},
                onCopy = {},
                onDelete = {},
                onRename = {},
                onClear = {},
                onDownload = {},
            )
        }
    }

    @Test
    fun `the site editor`() {
        shootDialog("dialog-site-editor", height = 1600) {
            SiteEditor(
                initial = SiteDraft(
                    id = "1",
                    name = "Office NAS",
                    host = "nas.example.org",
                    port = 21,
                    user = "bob",
                    password = "",
                    security = FtpSecurity.EXPLICIT_TLS,
                    transferMode = TransferMode.DEFAULT,
                    trustAllCertificates = false,
                    initialPath = null,
                ),
                onDismiss = {},
                onSave = {},
            )
        }
    }

    /**
     * The transfer list with nothing in it, and the one setting it has.
     *
     * Neither had ever been drawn here. The setting was a one-item dropdown
     * menu pinned under the button that opened it, and the empty screen was
     * the last icon in the app still wearing a white chip -- both of which
     * the user found before this did.
     */
    @Test
    fun `the empty queue`() {
        shoot("queue-empty", height = 1200) {
            EmptyState(
                title = stringResource(R.string.queue_empty_title),
                detail = stringResource(R.string.queue_empty_detail),
                icon = R.drawable.ic_tile_transfers,
                colour = org.filezilla.android.ui.theme.LightTiles.code,
            )
        }
    }

    @Test
    fun `the queue settings`() {
        shoot("queue-settings", height = 300) {
            QueueSettings(wifiOnly = true, onWifiOnly = {})
        }
    }

    /**
     * A long folder, with the index rail the user asked for.
     *
     * Three thousand films is the case: the rail is what gives the list a
     * length you can see, and it is built from the rows, so this is also
     * where a rail that does not match what it is sitting next to shows up.
     */
    @Test
    fun `a long list with its index rail`() {
        val names = listOf(
            "28년 후(2025)", "2번 배심원(2024)", "365일(2020)", "40 에이커스(2024)",
            "가족의 탄생", "거미집", "곡성", "기생충", "나랏말싸미", "노량",
            "다만 악에서", "라스트 듀얼", "마녀", "명량", "바람",
            "밀수", "범죄도시", "사바하", "서울대작전", "아가씨", "올드보이",
            "외계+인", "자산어보", "천문", "택시운전사", "파묘", "한산",
            "Avatar", "Dune", "Oppenheimer",
        )
        val model = MainViewModel(application)
        // Sorted the way the screen sorts, because the rail is built from
        // the rows it is standing next to. An unsorted fixture draws a rail
        // that climbs the alphabet twice and says nothing about the app.
        val rows = names.sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { entry(it, directory = true) }
        shoot("index-rail", height = 1800) {
            BrowseScreen(
                state = model.pane(PaneId.LEFT).copy(source = PaneSource.Local, entries = rows),
                rows = rows,
                options = model.options,
                onRefresh = {},
                onOpenLog = {},
                onFilterChange = {},
                onCloseFilter = {},
                onSearchDeeper = {},
                onStopWalking = {},
                onCloseSearch = {},
                onOpenHit = {},
                actions = EntryActions(
                    onOpen = {},
                    onDownload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onProperties = {},
                    onToggleSelected = {},
                ),
            )
        }
    }

    /** The strip the transfers tab was replaced by, at the foot of the screen. */
    @Test
    fun `the transfer strip`() {
        shoot("transfer-strip", height = 200) {
            TransferStrip(TransferSummary(count = 3, fraction = 0.42f), onOpen = {})
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
            TransferStrip(TransferSummary(count = 1, fraction = null), onOpen = {})
        }
    }

    /** The sites list, where a server says how it is reached before it is tapped. */
    @Test
    fun `the sites list`() {
        val sites = listOf(
            site(1, "Office NAS", "nas.example.org", 21, "bob", FtpSecurity.EXPLICIT_TLS),
            site(2, "Backup", "backup.example.org", 990, "archive", FtpSecurity.IMPLICIT_TLS),
            site(3, "", "10.0.0.7", 21, "anonymous", FtpSecurity.PLAIN),
        )
        shoot("sites", height = 760) {
            SitesScreen(
                sites = sites,
                editing = null,
                onEdit = {},
                onSave = {},
                onDelete = {},
                onConnect = {},
                onMove = { _, _ -> },
            )
        }
    }

    private fun site(
        id: Int,
        name: String,
        host: String,
        port: Int,
        user: String,
        security: FtpSecurity,
    ) = SiteEntity(
        id = id.toString(),
        name = name,
        host = host,
        port = port,
        user = user,
        passwordCipher = "",
        security = security.name,
        transferMode = TransferMode.DEFAULT.name,
        trustAllCertificates = false,
        initialPath = null,
        position = id,
    )

    /**
     * Every icon in the pack, on the chip it is shown on and at the size it
     * is shown at. The one place the whole set can be compared with itself.
     */
    @Test
    fun `the icon sheet`() {
        shoot("icons", height = 900) {
            IconSheet()
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun `the icon sheet at night`() {
        shoot("icons-night", height = 900) {
            IconSheet()
        }
    }

    @Composable
    private fun IconSheet() {
        val icons = listOf(
            "folder" to R.drawable.ic_flat_folder,
            "server" to R.drawable.ic_flat_server,
            "search" to R.drawable.ic_flat_search,
            "warning" to R.drawable.ic_flat_warning,
            "secure" to R.drawable.ic_flat_secure,
            "transfers" to R.drawable.ic_flat_transfers,
            "log" to R.drawable.ic_flat_log,
        )
        for (row in icons.chunked(4)) {
            androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
                for ((name, id) in row) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f).padding(12.dp),
                    ) {
                        FlatIcon(id, contentDescription = null, chipSize = 48.dp)
                        androidx.compose.material3.Text(
                            name,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }

    /**
     * The same pane in the dark theme.
     *
     * Worth its own picture because the artwork is multi-coloured and cannot
     * be tinted: whatever the chip behind it is, it is that in both themes,
     * and the only way to know it still reads is to look.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun `a pane of files at night`() {
        val model = MainViewModel(application)
        val rows = listOf(
            entry("Camera", directory = true),
            entry("holiday-2024.jpg", size = 3_400_000),
            entry("season-01.mkv", size = 1_930_000_000),
        )
        shoot("pane-of-files-night") {
            PaneHeader(
                id = PaneId.LEFT,
                model = model,
                options = model.options,
                onNewDirectory = {},
                onUpload = {},
                onGrant = {},
                onOpenScreen = {},
            )
            BrowseScreen(
                state = model.pane(PaneId.LEFT).copy(source = PaneSource.Local, entries = rows),
                rows = rows,
                options = model.options,
                onRefresh = {},
                onOpenLog = {},
                onFilterChange = {},
                onCloseFilter = {},
                onSearchDeeper = {},
                onStopWalking = {},
                onCloseSearch = {},
                onOpenHit = {},
                actions = EntryActions(
                    onOpen = {},
                    onDownload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onProperties = {},
                    onToggleSelected = {},
                ),
            )
        }
    }

    /**
     * Both panes at once, which is the layout the rearrangement is really
     * for: one bar above two panes could not say which pane it acted on.
     */
    @Test
    @Config(qualifiers = "w840dp-h1280dp-xhdpi")
    fun `two panes side by side`() {
        val model = MainViewModel(application)
        shoot("two-panes", width = 2100, height = 1400) {
            FilePanes(
                model = model,
                onOpenLog = {},
                onGrant = {},
                onPickSite = {},
                onDownload = {},
                onRequestNotifications = {},
                onTransfersQueued = {},
                onDownloadSelected = {},
                onOpenLocalFile = {},
                onOpenLocalFileWith = {},
                onShareLocal = {},
                onNewDirectory = {},
                onUpload = {},
                onOpenScreen = {},
            )
        }
    }

    private fun entry(name: String, size: Long = -1, directory: Boolean = false) = DirectoryEntry(
        name = name,
        size = size,
        isDirectory = directory,
        time = EntryTime(1_726_000_000_000, TimeAccuracy.MINUTES),
    )

    @Test
    fun `the storage sheet`() {
        val model = MainViewModel(application)
        shoot("storage-sheet") {
            StoragePlaces(
                id = PaneId.LEFT,
                model = model,
                onGrant = {},
                onOpenScreen = {},
                onDismiss = {},
            )
        }
    }
}
