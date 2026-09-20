package org.filezilla.android

import android.content.Context
import androidx.annotation.VisibleForTesting
import org.filezilla.android.data.AppDatabase
import org.filezilla.android.data.AppPreferences
import org.filezilla.android.data.KeystorePasswordCipher
import org.filezilla.android.data.PasswordCipher
import org.filezilla.android.data.RoomTransferJournal
import org.filezilla.android.files.LocalFileSource
import org.filezilla.android.files.StorageAccess
import org.filezilla.android.files.StorageVolumes
import org.filezilla.android.storage.PartialFiles
import org.filezilla.android.storage.SafStorage
import org.filezilla.android.transfer.AppLog
import org.filezilla.android.transfer.NetworkGate
import org.filezilla.android.transfer.TransferManager

/**
 * The app's single object graph.
 *
 * Hand-built rather than injected by a framework: there are six objects and
 * one lifetime, and the wiring is easier to follow written down than generated.
 *
 * It must be a singleton, and for one reason above the usual ones -- the
 * journal. Two instances would mean two Room databases over the same file,
 * and the offsets that make a resume safe would be read from one while written
 * to the other.
 */
class AppGraph private constructor(context: Context) {

    private val app = context.applicationContext

    /** Built before the database, which needs it to migrate old rows. */
    val passwords: PasswordCipher = sealPasswordsWith()

    val database: AppDatabase = AppDatabase.open(app, passwords)
    val preferences = AppPreferences(app)
    val log = AppLog()
    /**
     * Carries the saved Wi-Fi-only setting from the moment it is built.
     *
     * Set here rather than when the transfer service starts, so that anything
     * asking whether a transfer may run right now gets the same answer the
     * queue would give -- the service is not always running when the question
     * is asked.
     */
    val networkGate = NetworkGate(app).apply { policy = preferences.networkPolicy }

    private val partials = PartialFiles(app)
    val storage = SafStorage(app)

    /** Whether the file panes can see the device's storage, and how to ask. */
    val storageAccess = StorageAccess(app)
    val volumes = StorageVolumes(app)
    val localFiles = LocalFileSource(label = app.getString(org.filezilla.android.R.string.storage_this_device))

    val transfers = TransferManager(
        database = database,
        journal = RoomTransferJournal(database.transfers()),
        partials = partials,
        storage = storage,
        log = log,
        networkGate = networkGate,
        passwords = passwords,
    )

    /**
     * Clears away the folders a finished move emptied.
     *
     * Built here rather than in the service so it reads the one preferences
     * and the one journal: a second copy would sweep against a list the
     * paste never wrote to.
     */
    val moveCleanup = org.filezilla.android.transfer.MoveCleanup(
        preferences = preferences,
        sites = database.sites(),
        transfers = transfers,
        log = { level, message -> log.log(level, message) },
    )

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        /**
         * How passwords are sealed. The keystore, except under test.
         *
         * The one seam in this graph, and it is here because its absence was
         * costing real bugs. Everything below the view model can be tested
         * against a live FTPS server, and nothing at or above it could be,
         * because a site's password goes through the Android keystore and
         * there is no keystore off a device. So the tests stopped one layer
         * short of where the app is actually assembled -- and that is the
         * layer where an upload forgot to make its folders, and where a copy
         * forgot the folders with nothing in them. Both shipped. Both would
         * have been caught by a test that could paste.
         *
         * Internal, so only this module can reach it, and
         * [org.filezilla.android.AppGraphSeamTest] fails if anything outside
         * a test ever assigns it.
         */
        @Volatile
        @VisibleForTesting
        internal var sealPasswordsWith: () -> PasswordCipher = { KeystorePasswordCipher() }

        /**
         * Throws the graph away so the next [of] builds a fresh one.
         *
         * Only for a test that has just changed [sealPasswordsWith]: the
         * graph reads it once, when it is built.
         */
        @VisibleForTesting
        internal fun forget() {
            instance = null
        }

        fun of(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}
