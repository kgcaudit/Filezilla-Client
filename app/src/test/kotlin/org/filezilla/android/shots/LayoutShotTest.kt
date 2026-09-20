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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.filezilla.android.R
import org.filezilla.android.ui.FlatIcon
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
                rows = emptyList(),
                downloadFolderName = "Download",
                onNewDirectory = {},
                onUpload = {},
                onChooseFolder = {},
                onGrant = {},
            )
        }
    }

    /** The header over a listing, which is how either is actually seen. */
    @Test
    fun `a pane of files`() {
        val model = MainViewModel(application)
        val rows = listOf(
            entry("Camera", directory = true),
            entry("Invoices", directory = true),
            entry("holiday-2024.jpg", size = 3_400_000),
            entry("meeting-notes.md", size = 4_120),
            entry("season-01.mkv", size = 1_930_000_000),
        )
        shoot("pane-of-files") {
            PaneHeader(
                id = PaneId.LEFT,
                model = model,
                options = model.options,
                rows = rows,
                downloadFolderName = "Download",
                onNewDirectory = {},
                onUpload = {},
                onChooseFolder = {},
                onGrant = {},
            )
            BrowseScreen(
                state = model.pane(PaneId.LEFT).copy(source = PaneSource.Local, entries = rows),
                rows = rows,
                options = model.options,
                onRefresh = {},
                onOpenLog = {},
                onFilterChange = {},
                onCloseFilter = {},
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
            "file" to R.drawable.ic_flat_file,
            "folder" to R.drawable.ic_flat_folder,
            "server" to R.drawable.ic_flat_server,
            "search" to R.drawable.ic_flat_search,
            "warning" to R.drawable.ic_flat_warning,
            "phone" to R.drawable.ic_flat_phone,
            "sdcard" to R.drawable.ic_flat_sdcard,
            "locked" to R.drawable.ic_flat_locked,
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
                rows = rows,
                downloadFolderName = "Download",
                onNewDirectory = {},
                onUpload = {},
                onChooseFolder = {},
                onGrant = {},
            )
            BrowseScreen(
                state = model.pane(PaneId.LEFT).copy(source = PaneSource.Local, entries = rows),
                rows = rows,
                options = model.options,
                onRefresh = {},
                onOpenLog = {},
                onFilterChange = {},
                onCloseFilter = {},
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
                options = model.options,
                downloadFolderName = "Download",
                onOpenLog = {},
                onGrant = {},
                onPickSite = {},
                onDownload = {},
                onRequestNotifications = {},
                onTransfersQueued = {},
                onDownloadSelected = {},
                onOpenLocalFile = {},
                onNewDirectory = {},
                onUpload = {},
                onChooseFolder = {},
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
            StoragePlaces(id = PaneId.LEFT, model = model, onGrant = {}, onDismiss = {})
        }
    }
}
