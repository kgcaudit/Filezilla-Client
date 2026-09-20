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
     * What a dialog is made of, drawn from the same three theme values a real
     * one reads: the raised surface, the corner, and the type scale.
     *
     * A real AlertDialog draws in a window of its own, which nothing that
     * renders this screen can capture -- so this is those three values in the
     * shape Material puts them in. It was lavender, because the theme never
     * set the surface a dialog sits on and Material filled it from its own
     * baseline palette.
     */
    @Test
    fun `a dialog`() {
        shoot("dialog", height = 640) { DialogFace() }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun `a dialog at night`() {
        shoot("dialog-night", height = 640) { DialogFace() }
    }

    @Composable
    private fun DialogFace() {
        val scheme = androidx.compose.material3.MaterialTheme.colorScheme
        val type = androidx.compose.material3.MaterialTheme.typography
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        ) {
            androidx.compose.material3.Surface(
                color = scheme.surfaceContainerHigh,
                contentColor = scheme.onSurface,
                shape = androidx.compose.material3.MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                androidx.compose.foundation.layout.Column(modifier = Modifier.padding(24.dp)) {
                    androidx.compose.material3.Text("1개 항목을 삭제할까요?", style = type.headlineSmall)
                    androidx.compose.material3.Text(
                        "폴더는 안에 든 것까지 함께 삭제됩니다. 되돌릴 수 없습니다.",
                        style = type.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                    ) {
                        androidx.compose.material3.TextButton(onClick = {}) {
                            androidx.compose.material3.Text("취소")
                        }
                        // The real dialog's confirm button, not a second copy
                        // of its cancel: the two used to be drawn the same
                        // here and on screen, which is the whole reason the
                        // error colour moved.
                        DangerButton("삭제", onClick = {})
                    }
                }
            }
        }
    }

    /**
     * The card a failure arrives in.
     *
     * Shot because the user saw it before this did: it was a pink card on a
     * cream page, and nothing that rendered a screen here had ever drawn one.
     */
    @Test
    fun `an error card`() {
        shoot("error-panel", height = 520) { FailureFace() }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun `an error card at night`() {
        shoot("error-panel-night", height = 520) { FailureFace() }
    }

    @Composable
    private fun FailureFace() {
        ErrorPanel(
            failure = describeFailure(java.net.SocketTimeoutException("Read timed out"), online = false),
            onRetry = {},
            onOpenLog = {},
        )
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
