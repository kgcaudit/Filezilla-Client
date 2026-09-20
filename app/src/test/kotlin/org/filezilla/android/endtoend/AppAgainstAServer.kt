package org.filezilla.android.endtoend

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.filezilla.android.AppGraph
import org.filezilla.android.data.FakePasswordCipher
import org.filezilla.android.data.SiteEntity
import org.filezilla.android.ui.MainViewModel
import org.filezilla.android.ui.PaneId
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.TransferMode
import org.filezilla.ftp.testing.FtpsTestServer
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The whole app, with a real FTPS server at the other end.
 *
 * What this exists for: every piece under the view model could already be
 * tested against a live server, and the view model itself could not, because
 * a site's password goes through the Android keystore and there is no
 * keystore off a device. So the tests stopped one layer short of where the
 * app is assembled -- and that layer is where an upload forgot to make its
 * folders on the server, and where a copy forgot the folders with nothing in
 * them. Both shipped, both were found by the user, and both are the kind of
 * thing that only shows up when the pieces are put together.
 *
 * [AppGraph.sealPasswordsWith] is the one seam that makes it possible.
 * Extend this, put a folder on the phone's side, paste it, and look at what
 * arrived.
 */
abstract class AppAgainstAServer {

    @get:Rule
    val phone = TemporaryFolder()

    protected lateinit var server: FtpsTestServer

    protected val application: Application get() = ApplicationProvider.getApplicationContext()

    /** Where the server keeps what it was sent. */
    protected val onServer: File get() = server.root

    @Before
    fun startServerAndApp() {
        assumeTrue(
            "FTPS test server not set up; run core-ftp/src/testFixtures/resources/ftps-server/setup.sh",
            FtpsTestServer.isAvailable,
        )
        server = FtpsTestServer()
        server.start()

        // Before the graph is built, since it reads the cipher once.
        AppGraph.sealPasswordsWith = { FakePasswordCipher() }
        AppGraph.forget()

        // Robolectric's phone has no network, and the queue is built to wait
        // for one rather than fail -- so without this it parks for ever and
        // the test times out having proved nothing. An unmetered network is
        // what "Wi-Fi only" would also accept.
        pretendThereIsWifi()

        val prefs = AppGraph.of(application).preferences
        for (id in PaneId.entries) {
            prefs.setPaneIsLocal(id.name, true)
            prefs.setPaneSiteId(id.name, null)
            prefs.setPanePath(id.name, "local", null)
        }
    }

    @After
    fun stopServerAndApp() {
        if (::server.isInitialized) server.stop()
        AppGraph.forget()
        AppGraph.sealPasswordsWith = { org.filezilla.android.data.KeystorePasswordCipher() }
    }

    private fun pretendThereIsWifi() {
        val manager = application.getSystemService(android.net.ConnectivityManager::class.java)
        val active = manager.activeNetwork ?: return
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(caps)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        org.robolectric.Shadows.shadowOf(caps)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        org.robolectric.Shadows.shadowOf(manager).setNetworkCapabilities(active, caps)
    }

    /** The test server, saved into the app's own database. */
    protected fun savedSite(): SiteEntity {
        val graph = AppGraph.of(application)
        val entity = SiteEntity(
            id = "test-server",
            name = "test",
            host = "127.0.0.1",
            port = server.port,
            user = server.user,
            passwordCipher = graph.passwords.encrypt(server.password),
            security = FtpSecurity.EXPLICIT_TLS.name,
            transferMode = TransferMode.DEFAULT.name,
            trustAllCertificates = true,
            initialPath = null,
        )
        kotlinx.coroutines.runBlocking { graph.database.sites().upsert(entity) }
        return entity
    }

    protected fun model(): MainViewModel = MainViewModel(application)

    /**
     * Pumps the main looper until [condition] holds.
     *
     * The view model launches on the main dispatcher and talks to the server
     * on IO, so neither a plain call nor a single idle() is enough: both
     * sides have to be let run.
     */
    protected fun waitFor(what: String, seconds: Long = 20, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /**
     * Runs the transfer queue to the end.
     *
     * On a phone a foreground service does this; nothing starts one here, so
     * a test that only queues work and then looks at the server sees an empty
     * server and no failures -- which is how a test can pass on an upload
     * that never happened. This one did, until it started checking that the
     * files had contents.
     */
    protected fun runTheQueue(): org.filezilla.android.transfer.TransferManager.QueueOutcome =
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(60_000) {
                AppGraph.of(application).transfers.runQueue()
            }
        }

    /** Points a pane at a folder on the phone and waits for the rows. */
    protected fun openOnPhone(model: MainViewModel, id: PaneId, folder: File) {
        model.showPane(id)
        model.openPath(id, folder.absolutePath)
        waitFor("$folder to be listed") {
            model.pane(id).path == folder.absolutePath && !model.pane(id).loading
        }
    }

    /** Points a pane at the test server and waits for the listing. */
    protected fun openOnServer(model: MainViewModel, id: PaneId, site: SiteEntity) {
        model.showSite(id, site)
        waitFor("the server to be listed") { !model.pane(id).loading }
    }
}
