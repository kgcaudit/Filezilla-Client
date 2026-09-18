package org.filezilla.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TransferEntity::class, SiteEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transfers(): TransferDao

    abstract fun sites(): SiteDao

    companion object {
        fun open(context: Context, passwords: PasswordCipher): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "filezilla.db",
            )
                .addMigrations(encryptPasswords(passwords))
                // No fallbackToDestructiveMigration: dropping this database
                // throws away the offsets that make a resume safe, which
                // would turn a schema change into silently re-downloading
                // everything in the queue -- and now the saved passwords too.
                .build()

        /**
         * Version 2 moves site passwords out of a plaintext column and behind
         * the Android keystore.
         *
         * The encryption happens inside the migration rather than lazily on
         * first read, so the plaintext stops existing the moment the app
         * upgrades. A lazy scheme would leave it in the file for as long as
         * the user did not happen to open that site.
         *
         * SQLite cannot change a column, so the table is rebuilt -- the
         * standard recipe -- and the rows are carried over one at a time
         * because each password needs encrypting individually.
         */
        internal fun encryptPasswords(passwords: PasswordCipher) = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Zero freed pages rather than leaving the old plaintext in
                // the file's free list, where a backup would copy it verbatim.
                // Best effort, and stated as such: it does not reach content
                // already written to the WAL, and VACUUM -- which would --
                // cannot run here, because Room wraps a migration in a
                // transaction and SQLite refuses to vacuum inside one.
                // Through query, not execSQL: this PRAGMA returns its new
                // value, and Android's SQLite refuses a statement that yields
                // rows through execSQL.
                db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() }

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sites_v2` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `host` TEXT NOT NULL, " +
                        "`port` INTEGER NOT NULL, `user` TEXT NOT NULL, " +
                        "`password_cipher` TEXT NOT NULL, `security` TEXT NOT NULL, " +
                        "`transferMode` TEXT NOT NULL, `trustAllCertificates` INTEGER NOT NULL, " +
                        "`initialPath` TEXT, PRIMARY KEY(`id`))",
                )

                db.query(
                    "SELECT id, name, host, port, user, password, security, transferMode, " +
                        "trustAllCertificates, initialPath FROM sites",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        db.execSQL(
                            "INSERT INTO `sites_v2` (id, name, host, port, user, password_cipher, " +
                                "security, transferMode, trustAllCertificates, initialPath) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            // Explicitly Any?, or Kotlin infers an
                            // intersection type across the String, Int and
                            // null arguments.
                            arrayOf<Any?>(
                                cursor.getString(0),
                                cursor.getString(1),
                                cursor.getString(2),
                                cursor.getInt(3),
                                cursor.getString(4),
                                passwords.encrypt(cursor.getString(5)),
                                cursor.getString(6),
                                cursor.getString(7),
                                cursor.getInt(8),
                                if (cursor.isNull(9)) null else cursor.getString(9),
                            ),
                        )
                    }
                }

                // Dropping the old table is what removes the plaintext.
                db.execSQL("DROP TABLE `sites`")
                db.execSQL("ALTER TABLE `sites_v2` RENAME TO `sites`")
            }
        }
    }
}
