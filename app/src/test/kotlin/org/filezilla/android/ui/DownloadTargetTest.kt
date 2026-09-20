package org.filezilla.android.ui

import org.filezilla.android.data.SiteEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one answer to "where does this download go".
 *
 * There used to be two, and one of them was a folder chosen once through the
 * system picker and never seen again -- so a transfer could be running while
 * the header said no folder had been chosen, and both were true.
 */
class DownloadTargetTest {

    private val site = SiteEntity(
        id = "s1", name = "NAS", host = "h", port = 21, user = "u",
        passwordCipher = "x", security = "PLAIN", transferMode = "DEFAULT",
        trustAllCertificates = false, initialPath = null,
    )

    @Test
    fun `the folder the other pane is showing`() {
        val other = BrowseState(source = PaneSource.Local, path = "/storage/emulated/0/Download")

        assertEquals(
            Destination.Folder("/storage/emulated/0/Download"),
            destinationFor(other, storageGranted = true),
        )
    }

    /**
     * Refused rather than sent to Downloads. Falling back would put the file
     * somewhere the user is not looking, which is the whole thing this
     * replaces.
     */
    @Test
    fun `a server on the other side is refused`() {
        val other = BrowseState(source = PaneSource.Remote(site), path = "/pub")

        assertEquals(
            Destination.OtherPaneNotLocal,
            destinationFor(other, storageGranted = true),
        )
    }

    @Test
    fun `an empty pane on the other side is refused`() {
        assertEquals(
            Destination.OtherPaneNotLocal,
            destinationFor(BrowseState(source = PaneSource.Empty), storageGranted = true),
        )
    }

    @Test
    fun `no storage access is its own answer`() {
        val other = BrowseState(source = PaneSource.Local, path = "/storage/emulated/0")

        assertEquals(Destination.NoStorageAccess, destinationFor(other, storageGranted = false))
    }

    /**
     * A pane that has not listed anything has no folder, and "" is not one.
     * Sending a download to an empty path would write at the filesystem root.
     */
    @Test
    fun `a pane that has been nowhere has no folder`() {
        val other = BrowseState(source = PaneSource.Local, path = "")

        assertEquals(Destination.NowhereYet, destinationFor(other, storageGranted = true))
    }

    /** Storage is checked before the folder: the grant is the first thing to fix. */
    @Test
    fun `the missing grant is reported before the missing folder`() {
        val other = BrowseState(source = PaneSource.Local, path = "")

        assertEquals(Destination.NoStorageAccess, destinationFor(other, storageGranted = false))
    }
}
