package org.filezilla.android

import android.content.Context
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
    val passwords: PasswordCipher = KeystorePasswordCipher()

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

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun of(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}
