package org.filezilla.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.filezilla.ftp.journal.TransferDirection
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.journal.TransferState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The claim is what keeps two workers off one file.
 *
 * Without it, both would read the same PENDING record, both would download it
 * over their own connection into their own partial file, and whichever
 * finished last would publish over the other. That is not a bug a user could
 * diagnose -- it looks like the app is simply slow.
 */
@RunWith(RobolectricTestRunner::class)
class TransferClaimTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    private fun record(id: String, state: TransferState) = TransferRecord(
        id = id,
        direction = TransferDirection.DOWNLOAD,
        host = "nas",
        port = 21,
        user = "u",
        remotePath = "/HDD1/$id.bin",
        localPath = "/tmp/$id.part",
        state = state,
        updatedAtMillis = 1,
    )

    private fun put(id: String, state: TransferState) =
        database.transfers().upsert(TransferEntity.from(record(id, state)))

    @Test
    fun `only one caller can claim a transfer`() {
        put("a", TransferState.PENDING)

        assertEquals(1, database.transfers().claim("a", now = 2))
        assertEquals(0, database.transfers().claim("a", now = 3))
    }

    @Test
    fun `claiming marks it running`() {
        put("a", TransferState.PENDING)

        database.transfers().claim("a", now = 2)

        assertEquals(TransferState.RUNNING, database.transfers().byId("a")!!.toRecord().state)
    }

    @Test
    fun `an interrupted transfer can be claimed again`() {
        put("a", TransferState.INTERRUPTED)

        assertEquals(1, database.transfers().claim("a", now = 2))
    }

    /** Whatever holds these, it is not a worker looking for work. */
    @Test
    fun `paused, waiting, finished and failed transfers are not claimable`() {
        for (state in listOf(
            TransferState.PAUSED,
            TransferState.WAITING_FOR_NETWORK,
            TransferState.COMPLETED,
            TransferState.FAILED,
        )) {
            put(state.name, state)
            assertEquals("$state", 0, database.transfers().claim(state.name, now = 2))
        }
    }

    /**
     * RUNNING means a live worker holds it. The queue turns leftovers from a
     * killed process back into INTERRUPTED before any worker starts, so by the
     * time a worker is looking, RUNNING is never free.
     */
    @Test
    fun `a running transfer is not stolen by the other worker`() {
        put("a", TransferState.RUNNING)

        assertEquals(0, database.transfers().claim("a", now = 2))
    }

    @Test
    fun `a killed process leaves its transfer claimable again`() {
        put("a", TransferState.RUNNING)

        assertEquals(1, database.transfers().releaseStaleClaims(now = 2))

        assertEquals(TransferState.INTERRUPTED, database.transfers().byId("a")!!.toRecord().state)
        assertEquals(1, database.transfers().claim("a", now = 3))
    }

    /** The race itself, run for real rather than reasoned about. */
    @Test
    fun `two threads racing for one transfer produce exactly one winner`() {
        repeat(40) { round ->
            val id = "race$round"
            put(id, TransferState.PENDING)

            val wins = AtomicInteger()
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val pool = Executors.newFixedThreadPool(2)
            repeat(2) {
                pool.execute {
                    start.await()
                    if (database.transfers().claim(id, now = 5) == 1) wins.incrementAndGet()
                    done.countDown()
                }
            }
            start.countDown()
            done.await(10, TimeUnit.SECONDS)
            pool.shutdown()

            assertEquals("round $round", 1, wins.get())
        }
    }
}
