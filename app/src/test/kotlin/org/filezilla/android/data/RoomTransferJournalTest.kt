package org.filezilla.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.filezilla.ftp.journal.RemoteFingerprint
import org.filezilla.ftp.journal.TransferDirection
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.journal.TransferState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomTransferJournalTest {

    private lateinit var database: AppDatabase
    private lateinit var journal: RoomTransferJournal

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        journal = RoomTransferJournal(database.transfers())
    }

    @After
    fun tearDown() = database.close()

    private fun record(id: String, state: TransferState, updatedAt: Long = 1) = TransferRecord(
        id = id,
        direction = TransferDirection.DOWNLOAD,
        host = "ftp.example.org",
        port = 21,
        user = "someone",
        remotePath = "/pub/$id.bin",
        localPath = "/data/$id.part",
        state = state,
        bytesTransferred = 1_024,
        totalBytes = 4_096,
        fingerprint = RemoteFingerprint(4_096, 1_700_000_000_000L),
        updatedAtMillis = updatedAt,
    )

    @Test
    fun `a stored record reads back unchanged`() {
        val stored = record("one", TransferState.RUNNING)
        journal.put(stored)
        assertEquals(stored, journal.get("one"))
    }

    @Test
    fun `putting the same id twice replaces rather than duplicates`() {
        journal.put(record("one", TransferState.RUNNING))
        journal.put(record("one", TransferState.RUNNING).copy(bytesTransferred = 9_000))
        assertEquals(1, journal.all().size)
        assertEquals(9_000L, journal.get("one")?.bytesTransferred)
    }

    @Test
    fun `a record left RUNNING by a killed process is resumable`() {
        // The case the journal exists for: nothing is running after a restart,
        // so a RUNNING row means the process died holding it.
        journal.put(record("killed", TransferState.RUNNING))
        assertTrue(journal.resumable().any { it.id == "killed" })
    }

    @Test
    fun `completed and failed records are not resumable`() {
        journal.put(record("done", TransferState.COMPLETED))
        journal.put(record("dead", TransferState.FAILED))
        journal.put(record("paused", TransferState.PAUSED))
        assertTrue(journal.resumable().isEmpty())
    }

    @Test
    fun `removing a record removes it`() {
        journal.put(record("one", TransferState.PENDING))
        journal.remove("one")
        assertNull(journal.get("one"))
        assertTrue(journal.all().isEmpty())
    }

    @Test
    fun `all is ordered oldest first so the queue runs in order`() {
        journal.put(record("second", TransferState.PENDING, updatedAt = 200))
        journal.put(record("first", TransferState.PENDING, updatedAt = 100))
        assertEquals(listOf("first", "second"), journal.all().map { it.id })
    }
}
