package org.filezilla.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TransferEntity::class, SiteEntity::class, SyncJobEntity::class],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transfers(): TransferDao

    abstract fun sites(): SiteDao

    abstract fun syncJobs(): SyncJobDao

    companion object {

        /**
         * The schema version, named so the migration test can pin the same
         * one the app ships. It was written out twice, and the copy in the
         * test went stale the moment a column was added -- so every existing
         * database looked unmigratable from a test that was only out of date.
         */
        const val VERSION = 9

        /**
         * Every migration, in one list.
         *
         * Also written out twice, with the same result: a migration added
         * here and not there made the test fail as though the app could not
         * open an old database.
         */
        fun migrations(passwords: PasswordCipher): Array<Migration> =
            arrayOf(
                encryptPasswords(passwords),
                ADD_ENCODING,
                ADD_POSITION,
                ADD_REMOVE_SOURCE,
                PIN_CERTIFICATES,
                ADD_SFTP,
                ADD_PRIVATE_KEY,
                ADD_SYNC_JOBS,
            )

        /**
         * Version 9 adds scheduled folder mirrors, each its own row.
         *
         * A new table, so nothing already stored is touched: a phone that never
         * sets up a scheduled sync carries an empty table and behaves exactly as
         * before. The column shapes match [SyncJobEntity] so Room's own identity
         * check passes against it. Booleans are INTEGER, names are TEXT, and the
         * two destructive-leaning fields (delete_extras, requires_charging)
         * default to the safe side.
         */
        internal val ADD_SYNC_JOBS = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_jobs` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`local_root` TEXT NOT NULL, " +
                        "`local_label` TEXT NOT NULL, " +
                        "`site_id` TEXT NOT NULL, " +
                        "`remote_root` TEXT NOT NULL, " +
                        "`remote_label` TEXT NOT NULL, " +
                        "`direction` TEXT NOT NULL, " +
                        "`delete_extras` INTEGER NOT NULL DEFAULT 0, " +
                        "`interval_minutes` INTEGER NOT NULL, " +
                        "`requires_wifi` INTEGER NOT NULL DEFAULT 1, " +
                        "`requires_charging` INTEGER NOT NULL DEFAULT 0, " +
                        "`enabled` INTEGER NOT NULL DEFAULT 1, " +
                        "`last_run_at` INTEGER NOT NULL DEFAULT 0, " +
                        "`last_status` TEXT NOT NULL DEFAULT 'NONE', " +
                        "`last_result` TEXT, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

        fun open(context: Context, passwords: PasswordCipher): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "filezilla.db",
            )
                .addMigrations(*migrations(passwords))
                // No fallbackToDestructiveMigration: dropping this database
                // throws away the offsets that make a resume safe, which
                // would turn a schema change into silently re-downloading
                // everything in the queue -- and now the saved passwords too.
                .build()

        /**
         * Version 8 adds an SFTP private key, held encrypted like the password.
         *
         * A plain added column, nullable, defaulting to null -- which is
         * password authentication, what every SFTP site so far has used. A
         * private key is a credential, so the column holds ciphertext under the
         * keystore key, never the key itself; see [encryptPasswords].
         */
        internal val ADD_PRIVATE_KEY = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sites` ADD COLUMN `private_key_cipher` TEXT")
            }
        }

        /**
         * Version 7 adds SFTP: which protocol a site speaks, and the SSH host
         * key accepted for it.
         *
         * Both are plain added columns. `protocol` defaults to `"FTP"`, which
         * every site saved before this was, so nothing changes for them --
         * `known_host_key` is theirs to leave null, since FTP does not use it.
         * A rebuild would be needless here: nothing is being dropped, only
         * added, so `ALTER TABLE ADD COLUMN` is the whole migration, exactly as
         * for the encoding and position columns before it.
         */
        internal val ADD_SFTP = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `sites` ADD COLUMN `protocol` TEXT NOT NULL DEFAULT 'FTP'",
                )
                db.execSQL("ALTER TABLE `sites` ADD COLUMN `known_host_key` TEXT")
            }
        }

        /**
         * Version 6 trades "accept any certificate" for accepting one.
         *
         * Every site that had the switch on comes out with nothing pinned,
         * which means the next connection to it stops and asks. That is the
         * point rather than a cost of doing it: those sites were accepting
         * whatever was presented, so there is no certificate among them that
         * anybody ever actually looked at, and carrying the switch forward
         * under a new name would carry the hole with it.
         *
         * Sites that had it off are unaffected -- they were already being
         * checked properly and still are.
         *
         * The table is rebuilt rather than altered because the old column
         * has to go: SQLite before 3.35 cannot drop one, and Room will
         * refuse to open a database whose columns it did not expect.
         */
        internal val PIN_CERTIFICATES = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `sites_new` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`host` TEXT NOT NULL, " +
                        "`port` INTEGER NOT NULL, " +
                        "`user` TEXT NOT NULL, " +
                        "`password_cipher` TEXT NOT NULL, " +
                        "`security` TEXT NOT NULL, " +
                        "`transferMode` TEXT NOT NULL, " +
                        "`pinned_certificate` TEXT, " +
                        "`initialPath` TEXT, " +
                        "`encoding` TEXT, " +
                        "`position` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "INSERT INTO `sites_new` (" +
                        "`id`, `name`, `host`, `port`, `user`, `password_cipher`, " +
                        "`security`, `transferMode`, `pinned_certificate`, " +
                        "`initialPath`, `encoding`, `position`) " +
                        "SELECT `id`, `name`, `host`, `port`, `user`, `password_cipher`, " +
                        "`security`, `transferMode`, NULL, " +
                        "`initialPath`, `encoding`, `position` FROM `sites`",
                )
                db.execSQL("DROP TABLE `sites`")
                db.execSQL("ALTER TABLE `sites_new` RENAME TO `sites`")
            }
        }

        /**
         * Version 5 lets a queued transfer know it is half of a move.
         *
         * Defaulting to 0 is the whole migration: every transfer queued
         * before this existed was a copy, and a copy leaves its source
         * alone. Nothing already in the queue can start deleting things
         * because the app was updated.
         */
        internal val ADD_REMOVE_SOURCE = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `transfers` ADD COLUMN `remove_source` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Version 4 gives each site a place in an order the user arranges.
         *
         * The existing rows are numbered in the order they were being shown,
         * which was by name. Leaving them all at the default zero would have
         * been a smaller migration and a worse one: the list would fall back
         * on the name tiebreak and look unchanged until the first move, at
         * which point every other row would jump at once.
         */
        internal val ADD_POSITION = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sites` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0")
                // Numbered through a subquery rather than by reading the rows
                // out and writing them back: one statement, and no cursor
                // held open across the writes.
                db.execSQL(
                    "UPDATE `sites` SET `position` = (" +
                        "SELECT COUNT(*) FROM `sites` AS earlier " +
                        "WHERE earlier.`name` < `sites`.`name` " +
                        "OR (earlier.`name` = `sites`.`name` AND earlier.`id` < `sites`.`id`))",
                )
            }
        }

        /**
         * Version 3 adds the per-site encoding.
         *
         * A plain added column, nullable, defaulting to null -- which is the
         * "negotiate UTF-8 as before" behaviour, so every existing site keeps
         * working exactly as it did.
         */
        internal val ADD_ENCODING = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sites` ADD COLUMN `encoding` TEXT")
            }
        }

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
