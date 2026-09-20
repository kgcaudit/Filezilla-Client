package org.filezilla.android.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Version 1 stored site passwords in plaintext. Version 2 does not, version
 * 3 adds the per-site encoding and version 4 the user's own ordering, so this
 * drives the whole chain a phone upgrading from the first build would run.
 *
 * This is the migration people lose their saved servers to if it is wrong, and
 * every way it can be wrong is quiet: a dropped table, columns in the wrong
 * order, a password that comes back as something the server rejects. So it is
 * driven against a real version 1 database rather than reasoned about.
 *
 * The version 1 file is built by hand rather than with `MigrationTestHelper`,
 * which reads the exported schema from instrumentation assets that a
 * Robolectric unit test does not have. Nothing is lost by it: Room checks the
 * migrated schema against its own identity hash when the database is opened
 * below, so a migration that produced the wrong shape fails there.
 */
@RunWith(RobolectricTestRunner::class)
class PasswordMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val passwords = FakePasswordCipher()

    @Before
    fun setUp() = context.deleteDatabase(DB).let { }

    @After
    fun tearDown() = context.deleteDatabase(DB).let { }

    @Test
    fun `a saved site survives the move to encrypted passwords`() {
        createVersionOne(
            "INSERT INTO sites (id, name, host, port, user, password, security, " +
                "transferMode, trustAllCertificates, initialPath) " +
                "VALUES ('s1', 'Work', 'ftp.example.org', 21, 'someone', 'hunter2', " +
                "'EXPLICIT_TLS', 'DEFAULT', 1, '/pub')",
        )

        val site = readSite("ftp.example.org", 21, "someone")
            ?: error("the site did not survive the migration")

        // Everything else came across untouched...
        assertEquals("Work", site.name)
        assertEquals(21, site.port)
        assertEquals("EXPLICIT_TLS", site.security)
        assertEquals(true, site.trustAllCertificates)
        assertEquals("/pub", site.initialPath)
        // The column version 3 added defaults to null, which is the same
        // "negotiate UTF-8" behaviour the site had before it existed.
        assertEquals(null, site.encoding)

        // ...and the password is no longer in the clear, but is still the
        // password. Both halves matter: encrypting it and losing it would pass
        // a test that only checked the first.
        assertNotEquals("hunter2", site.passwordCipher)
        assertFalse("hunter2" in site.passwordCipher)
        assertEquals("hunter2", passwords.decrypt(site.passwordCipher))
        assertEquals("hunter2", site.toSettings(passwords).password)
    }

    @Test
    fun `several sites all come across`() {
        // The migration copies row by row, because each password needs
        // encrypting on its own. Off-by-one there loses somebody's server.
        createVersionOne(
            "INSERT INTO sites VALUES ('a', 'A', 'a.example', 21, 'ua', 'pa', 'PLAIN', 'DEFAULT', 0, NULL)",
            "INSERT INTO sites VALUES ('b', 'B', 'b.example', 990, 'ub', 'pb', 'IMPLICIT_TLS', 'PASSIVE', 1, '/b')",
            "INSERT INTO sites VALUES ('c', 'C', 'c.example', 21, 'uc', 'pc', 'EXPLICIT_TLS', 'ACTIVE', 0, NULL)",
        )

        assertEquals("pa", passwords.decrypt(readSite("a.example", 21, "ua")!!.passwordCipher))
        assertEquals("pb", passwords.decrypt(readSite("b.example", 990, "ub")!!.passwordCipher))
        assertEquals("pc", passwords.decrypt(readSite("c.example", 21, "uc")!!.passwordCipher))
    }

    @Test
    fun `the plaintext column is gone afterwards`() {
        createVersionOne(
            "INSERT INTO sites VALUES ('s1', 'Work', 'h', 21, 'u', 'hunter2', 'PLAIN', 'DEFAULT', 0, NULL)",
        )
        // Opening through Room is what runs the migration.
        readSite("h", 21, "u")

        openRaw().use { db ->
            val columns = db.query("PRAGMA table_info(`sites`)").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
            assertTrue("password_cipher" in columns)
            // Versions 3 and 4 rode along in the same open.
            assertTrue("encoding" in columns)
            assertTrue("position" in columns)
            // The reason the table is rebuilt rather than given a new column:
            // a plaintext column left behind is a plaintext column.
            assertFalse("password" in columns)
        }
    }

    /**
     * The order the list was already being shown in survives becoming an
     * order the user can change.
     *
     * Leaving every row at the default zero would have looked right until the
     * first move: the name tiebreak would hold the list together, and then
     * one press would renumber everything and rearrange rows the user never
     * touched. So the migration writes down the order they were seeing.
     */
    @Test
    fun `existing sites are numbered in the order they were shown`() {
        createVersionOne(
            "INSERT INTO sites VALUES ('c', 'Charlie', 'c.example', 21, 'u', 'p', 'PLAIN', 'DEFAULT', 0, NULL)",
            "INSERT INTO sites VALUES ('a', 'Alpha', 'a.example', 21, 'u', 'p', 'PLAIN', 'DEFAULT', 0, NULL)",
            "INSERT INTO sites VALUES ('b', 'Bravo', 'b.example', 21, 'u', 'p', 'PLAIN', 'DEFAULT', 0, NULL)",
        )

        val ordered = allSites()

        assertEquals(listOf("Alpha", "Bravo", "Charlie"), ordered.map { it.name })
        // Numbered from zero with no gaps and no ties, which is what makes a
        // swap a swap rather than a no-op between two rows sharing a place.
        assertEquals(listOf(0, 1, 2), ordered.map { it.position })
    }

    /** Two servers named the same still get places of their own. */
    @Test
    fun `sites sharing a name are still given distinct places`() {
        createVersionOne(
            "INSERT INTO sites VALUES ('b', 'NAS', 'b.example', 21, 'u', 'p', 'PLAIN', 'DEFAULT', 0, NULL)",
            "INSERT INTO sites VALUES ('a', 'NAS', 'a.example', 21, 'u', 'p', 'PLAIN', 'DEFAULT', 0, NULL)",
        )

        assertEquals(listOf(0, 1), allSites().map { it.position })
    }

    @Test
    fun `a site whose key is gone reports the password as unreadable`() {
        createVersionOne(
            "INSERT INTO sites VALUES ('s1', 'Work', 'h', 21, 'u', 'hunter2', 'PLAIN', 'DEFAULT', 0, NULL)",
        )
        val site = readSite("h", 21, "u")!!

        // What clearing the app's data, or removing the screen lock, does.
        passwords.keyIsGone = true
        assertNull(passwords.decrypt(site.passwordCipher))
        // The connection is attempted with an empty password rather than
        // crashing a background thread; the login then fails where the user
        // can see it and type the password again.
        assertEquals("", site.toSettings(passwords).password)
    }

    // ------------------------------------------------------------- plumbing

    /** Builds a version 1 database, exactly as Room 1 would have left it. */
    private fun createVersionOne(vararg rows: String) {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(V1_TRANSFERS)
                db.execSQL(V1_SITES)
                // Without the master table and the matching hash, Room refuses
                // the file as one it did not create, and no migration runs.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS room_master_table " +
                        "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, ?)",
                    arrayOf<Any?>(V1_IDENTITY_HASH),
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase.use { db -> rows.forEach(db::execSQL) }
        helper.close()
    }

    /**
     * Opens through Room, which runs the migration and validates the result,
     * and reads a row back through the entity the app actually uses.
     *
     * Opened and closed by hand: RoomDatabase is not Closeable in Room 2.6, so
     * `use` quietly resolves to something else.
     */
    private fun readSite(host: String, port: Int, user: String): SiteEntity? {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, DB)
            .addMigrations(*AppDatabase.migrations(passwords))
            .allowMainThreadQueries()
            .build()
        return try {
            database.sites().byEndpoint(host, port, user)
        } finally {
            database.close()
        }
    }

    /** Every site, in the order the app shows them, after the migration. */
    private fun allSites(): List<SiteEntity> {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, DB)
            .addMigrations(*AppDatabase.migrations(passwords))
            .allowMainThreadQueries()
            .build()
        return try {
            kotlinx.coroutines.runBlocking { database.sites().all() }
        } finally {
            database.close()
        }
    }

    private fun openRaw() = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB)
            // The current schema version: opening at anything lower is a
            // downgrade, which SQLite refuses outright.
            .callback(object : SupportSQLiteOpenHelper.Callback(CURRENT_VERSION) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build(),
    ).writableDatabase

    private companion object {
        const val DB = "migration-test.db"
        val CURRENT_VERSION = AppDatabase.VERSION
        const val V1_IDENTITY_HASH = "77835b154afacbde0754e799cc2b8a3d"

        const val V1_SITES =
            "CREATE TABLE IF NOT EXISTS `sites` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`host` TEXT NOT NULL, `port` INTEGER NOT NULL, `user` TEXT NOT NULL, " +
                "`password` TEXT NOT NULL, `security` TEXT NOT NULL, " +
                "`transferMode` TEXT NOT NULL, `trustAllCertificates` INTEGER NOT NULL, " +
                "`initialPath` TEXT, PRIMARY KEY(`id`))"

        const val V1_TRANSFERS =
            "CREATE TABLE IF NOT EXISTS `transfers` (`id` TEXT NOT NULL, " +
                "`direction` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, " +
                "`user` TEXT NOT NULL, `remote_path` TEXT NOT NULL, `local_path` TEXT NOT NULL, " +
                "`destination` TEXT, `state` TEXT NOT NULL, `bytes_transferred` INTEGER NOT NULL, " +
                "`total_bytes` INTEGER, `fingerprint_size` INTEGER, `fingerprint_modified` INTEGER, " +
                "`attempts` INTEGER NOT NULL, `last_error` TEXT, `updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
    }
}
