package org.filezilla.android.endtoend

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.filezilla.android.AppGraph
import org.filezilla.android.data.FakePasswordCipher
import org.filezilla.android.data.KeystorePasswordCipher
import org.filezilla.android.ui.MainViewModel
import org.filezilla.android.ui.PaneId
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Unpacking several archives selected at once, end to end.
 *
 * Selecting two archives and tapping unpack is meant to unpack both, each
 * into its own folder beside it. The batch loop used to hand the first
 * archive an "and then relist" step where it should have handed it "and
 * then move on to the next one", so the second archive was never opened --
 * two selected, one unpacked. This drives the view model the way the icon
 * does and insists both folders arrive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatchExtractTest {

    @get:Rule
    val phone = TemporaryFolder()

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun start() {
        AppGraph.sealPasswordsWith = { FakePasswordCipher() }
        AppGraph.forget()
        // Android 9's route to shared storage is a runtime permission; granting
        // it lets the pane list a real folder rather than an empty one.
        shadowOf(application).grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    @After
    fun stop() {
        AppGraph.forget()
        AppGraph.sealPasswordsWith = { KeystorePasswordCipher() }
    }

    private fun waitFor(what: String, seconds: Long = 60, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun zipInto(folder: File, name: String, entry: String, contents: String): File {
        val zip = File(folder, name)
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(entry))
            out.write(contents.toByteArray())
            out.closeEntry()
        }
        return zip
    }

    @Test
    fun `unpacking two selected archives unpacks both, not only the first`() {
        val folder = phone.newFolder("downloads")
        zipInto(folder, "one.zip", "first.txt", "from the first archive")
        zipInto(folder, "two.zip", "second.txt", "from the second archive")

        val model = MainViewModel(application)
        model.showLocalAt(PaneId.LEFT, folder.path)
        waitFor("the folder to list") {
            model.pane(PaneId.LEFT).entries.any { it.name == "two.zip" }
        }

        model.extractArchives(PaneId.LEFT, listOf("one.zip", "two.zip"))
        // Unpacking now asks new-folder-or-here first; answer as the old flow did.
        model.chooseExtractMode(MainViewModel.ExtractMode.NEW_FOLDER)

        // Each archive lands in its own folder beside it. The regression is the
        // second folder: on the old batch loop it never arrived.
        val first = File(folder, "one/first.txt")
        val second = File(folder, "two/second.txt")
        waitFor("both archives to unpack") { first.isFile && second.isFile }

        assertTrue("the first archive should have been unpacked", first.isFile)
        assertTrue("the second archive should have been unpacked too", second.isFile)
    }
}
