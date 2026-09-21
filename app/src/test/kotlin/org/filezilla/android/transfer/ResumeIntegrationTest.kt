package org.filezilla.android.transfer

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.filezilla.android.data.AppDatabase
import org.filezilla.android.data.FakePasswordCipher
import org.filezilla.android.data.RoomTransferJournal
import org.filezilla.android.data.SiteEntity
import org.filezilla.android.storage.PartialFiles
import org.filezilla.android.storage.SafStorage
import org.filezilla.ftp.journal.TransferState
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.TransferMode
import org.filezilla.ftp.testing.FtpsTestServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What happens to a half-finished download, against a live FTPS server.
 *
 * The engine's resume is already tested here in `:core-ftp`, thoroughly and
 * against this same server. What was never tested is the app's use of it --
 * whether the queue, the journal and the partial file still add up to a
 * resumable transfer after the user or the network interrupts one. That gap is
 * where the bug the user reported lived: the engine resumed perfectly, and the
 * app had deleted the file it would have resumed from.
 *
 * So these tests interrupt a real transfer four ways and check the same two
 * things every time: the bytes already fetched are still there, and finishing
 * produces a file identical to the server's.
 */
@RunWith(RobolectricTestRunner::class)
class ResumeIntegrationTest {

    private lateinit var server: FtpsTestServer
    private lateinit var database: AppDatabase
    private lateinit var partials: PartialFiles
    private lateinit var log: AppLog

    /**
     * The Wi-Fi switch, as the queue sees it.
     *
     * Starts allowed, because connectivity is not what most of these tests are
     * about; the ones that are turn it off and on again.
     */
    private class SwitchableGate : TransferGate {
        private val lock = Object()

        @Volatile
        private var allowed = true

        override val isAllowed: Boolean get() = allowed

        fun set(value: Boolean) {
            synchronized(lock) {
                allowed = value
                lock.notifyAll()
            }
        }

        override fun awaitAllowed() {
            synchronized(lock) {
                // Bounded like the real gate's, so a missed notify recovers.
                while (!allowed) lock.wait(50)
            }
        }

        override fun waitBeforeRetry(backoffMillis: Long, stopped: () -> Boolean) {
            // Sliced like the real gate's, so a transfer stopped while it is
            // waiting out a backoff does not have to wait the backoff out.
            val deadline = System.currentTimeMillis() + minOf(backoffMillis, 200)
            while (!stopped() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        }
    }

    private val openGate = SwitchableGate()

    @Before
    fun setUp() {
        assumeTrue(
            "FTPS test server not set up; run core-ftp/src/testFixtures/resources/ftps-server/setup.sh",
            FtpsTestServer.isAvailable,
        )
        // Slow enough that an interruption lands partway through the file.
        server = FtpsTestServer(throttleBytesPerSecond = THROTTLE)
        server.start()

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        partials = PartialFiles(context)
        log = AppLog()

        runBlocking { database.sites().upsert(site()) }
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
        if (::database.isInitialized) database.close()
    }

    private fun site() = SiteEntity(
        id = "s1",
        name = "test",
        host = "127.0.0.1",
        port = server.port,
        user = server.user,
        passwordCipher = FakePasswordCipher().encrypt(server.password),
        security = FtpSecurity.EXPLICIT_TLS.name,
        transferMode = TransferMode.DEFAULT.name,
        pinnedCertificate = FtpsTestServer.fingerprint,
        initialPath = null,
    )

    /**
     * A manager over the same database and partial files as the last one.
     *
     * Building a second one is how a restarted process is reproduced: nothing
     * survives but what reached the disk, which is exactly the question resume
     * has to answer.
     */
    private fun newManager(): TransferManager = TransferManager(
        database = database,
        journal = RoomTransferJournal(database.transfers()),
        partials = partials,
        storage = SafStorage(ApplicationProvider.getApplicationContext()),
        log = log,
        networkGate = openGate,
        passwords = FakePasswordCipher(),
        io = Dispatchers.IO,
    )

    private suspend fun enqueue(manager: TransferManager, name: String, size: Int): String {
        server.putFile(name, size)
        return manager.enqueueDownload(
            site = database.sites().byEndpoint("127.0.0.1", server.port, server.user)!!,
            remotePath = "/$name",
            totalBytes = size.toLong(),
            // A tree URI no provider backs. Publishing therefore fails and is
            // logged, which leaves the finished bytes in the partial file --
            // where these tests can compare them. Publishing has its own tests.
            destinationTree = Uri.parse("content://unbacked/tree/x"),
        )
    }

    private fun bytesOf(id: String): ByteArray = partials.forTransfer(id).readBytes()

    private fun stateOf(id: String): TransferState? =
        database.transfers().byId(id)?.toRecord()?.state

    private fun bytesRecorded(id: String): Long =
        database.transfers().byId(id)?.toRecord()?.bytesTransferred ?: 0

    /** Runs the queue until it drains, with a ceiling so a hang fails loudly. */
    private suspend fun drain(manager: TransferManager) {
        withTimeout(120_000) { manager.runQueue() }
    }

    // ------------------------------------------------------------ the cases

    /**
     * The bug as reported: cancelling took the bytes away, and there was
     * nothing to resume from afterwards. Cancel is allowed to do that -- the
     * user asked for the transfer to go away -- but it has to be the only
     * thing that does.
     */
    @Test
    fun `cancelling a running transfer stops it and takes its bytes`() = runBlocking {
        val size = FILE_SIZE
        val manager = newManager()
        val id = enqueue(manager, "cancel.bin", size)

        val queue = async { drain(manager) }
        awaitBytes(manager, id, atLeast = INTERRUPT_AT)
        manager.cancel(id)
        queue.await()

        assertNull("the record should be gone", stateOf(id))
        assertTrue("the bytes should be gone", !partials.forTransfer(id).isFile)
        // It has to have actually stopped, not merely been forgotten while it
        // carried on downloading -- which is what the original bug did.
        assertTrue(
            "the transfer should not have run to completion",
            partials.forTransfer(id).length() < size,
        )
    }

    /** Cancelling something that is only queued is the easy half; still check it. */
    @Test
    fun `cancelling a waiting transfer removes it`() = runBlocking {
        val manager = newManager()
        val id = enqueue(manager, "queued.bin", 100_000)

        manager.cancel(id)

        assertNull(stateOf(id))
    }

    /** Nothing else may take the bytes away. This is the whole point. */
    @Test
    fun `a paused transfer keeps its bytes and finishes from where it stopped`() = runBlocking {
        val size = FILE_SIZE
        val manager = newManager()
        val id = enqueue(manager, "paused.bin", size)

        // Pause as soon as enough has moved to make resuming meaningful.
        val queue = async { drain(manager) }
        awaitBytes(manager, id, atLeast = INTERRUPT_AT)
        manager.pause(id)
        queue.await()

        val paused = bytesRecorded(id)
        assertEquals(TransferState.PAUSED, stateOf(id))
        assertPartial(paused, size, "pause")
        assertTrue("the partial file should still be there", partials.forTransfer(id).isFile)

        val mark = log.log.value.size
        manager.resume(id)
        drain(newManager())

        assertEquals(TransferState.COMPLETED, stateOf(id))
        assertResumedNotRestarted("paused.bin", paused, mark)
        assertArrayEquals(FtpsTestServer.contentOf(size), bytesOf(id))
    }

    /**
     * A connection that dies partway is the ordinary case on a phone, and the
     * one the whole design is for. The server is told to cut the data
     * connection; the engine reconnects and resumes underneath.
     */
    @Test
    fun `a dropped connection resumes and still produces the right file`() = runBlocking {
        server.stop()
        server = FtpsTestServer(dropAfterBytes = 150_000, dropTimes = 1, throttleBytesPerSecond = THROTTLE)
        server.start()
        runBlocking { database.sites().upsert(site()) }

        val size = 400_000
        val manager = newManager()
        val id = enqueue(manager, "dropped.bin", size)

        drain(manager)

        assertEquals(TransferState.COMPLETED, stateOf(id))
        assertArrayEquals(FtpsTestServer.contentOf(size), bytesOf(id))
        assertTrue(
            "the transfer should have taken more than one attempt",
            database.transfers().byId(id)!!.toRecord().attempts >= 1,
        )
    }

    /**
     * The app being killed mid-transfer. Nothing survives but the journal row
     * and the partial file, so a fresh manager over the same two has to be
     * able to carry on.
     */
    @Test
    fun `a transfer interrupted by the process dying resumes in a new manager`() = runBlocking {
        val size = FILE_SIZE
        val first = newManager()
        val id = enqueue(first, "killed.bin", size)

        val queue = async { drain(first) }
        awaitBytes(first, id, atLeast = INTERRUPT_AT)
        // What process death looks like from here: the queue stops where it
        // stands and the manager is never used again.
        first.requestStop()
        first.pause(id)
        queue.await()

        val carried = bytesRecorded(id)
        assertPartial(carried, size, "process death")

        // A new manager, as a restarted process would build.
        val mark = log.log.value.size
        val second = newManager()
        second.resume(id)
        drain(second)

        assertEquals(TransferState.COMPLETED, stateOf(id))
        assertResumedNotRestarted("killed.bin", carried, mark)
        assertArrayEquals(FtpsTestServer.contentOf(size), bytesOf(id))
    }

    /**
     * A transfer the queue has given up on keeps its bytes. FAILED means stop
     * trying by yourself, not throw away what the user already paid for.
     */
    @Test
    fun `a failed transfer keeps its bytes and resumes when the user asks`() = runBlocking {
        val size = FILE_SIZE
        val manager = newManager()
        val id = enqueue(manager, "failed.bin", size)

        // Fetch part of it, then take the file away so the next attempts fail.
        val queue = async { drain(manager) }
        awaitBytes(manager, id, atLeast = INTERRUPT_AT)
        manager.pause(id)
        queue.await()

        val kept = bytesRecorded(id)
        assertPartial(kept, size, "failure")

        // Mark it failed the way three exhausted queue passes would.
        val record = database.transfers().byId(id)!!.toRecord()
        RoomTransferJournal(database.transfers()).put(
            record.copy(state = TransferState.FAILED, attempts = MAX_QUEUE_PASSES),
        )
        assertTrue("the bytes must survive a failure", partials.forTransfer(id).isFile)

        val mark = log.log.value.size
        manager.resume(id)
        drain(newManager())

        assertEquals(TransferState.COMPLETED, stateOf(id))
        assertResumedNotRestarted("failed.bin", kept, mark)
        assertArrayEquals(FtpsTestServer.contentOf(size), bytesOf(id))
    }

    /**
     * Resume has to be resume, not a restart with extra steps. The journal's
     * byte count is what the next attempt starts from, so it must not go
     * backwards across the interruption.
     */
    @Test
    fun `resuming does not start again from zero`() = runBlocking {
        val size = FILE_SIZE
        val manager = newManager()
        val id = enqueue(manager, "offset.bin", size)

        val queue = async { drain(manager) }
        awaitBytes(manager, id, atLeast = INTERRUPT_AT)
        manager.pause(id)
        queue.await()

        val before = bytesRecorded(id)
        assertPartial(before, size, "pause")
        val onDisk = partials.forTransfer(id).length()
        assertTrue("expected a real partial, got $onDisk bytes", onDisk >= before)

        val mark = log.log.value.size
        manager.resume(id)
        // The record still carries what was fetched, which is what the resume
        // decision reads. A zero here is the bug this test exists for.
        assertEquals(before, bytesRecorded(id))

        drain(newManager())
        assertResumedNotRestarted("offset.bin", before, mark)
        assertArrayEquals(FtpsTestServer.contentOf(size), bytesOf(id))
    }

    /**
     * Fails unless the engine actually resumed rather than starting again.
     *
     * Checking the finished file is not enough on its own: a transfer that
     * throws away what it had and downloads the whole thing again produces a
     * byte-identical file, and every other assertion here would pass. That is
     * what "resume is impossible" looks like from the outside -- it works, it
     * just costs the user the data twice.
     *
     * So this reads the decision the engine logged, which is the thing that
     * actually drives the behaviour.
     */
    private fun assertResumedNotRestarted(remoteName: String, atLeast: Long, since: Int) {
        // Only what was logged after the resume was asked for. The first
        // download of a file legitimately reports "no partial file on disk" --
        // there is none yet -- and counting that as a restart would fail every
        // one of these tests for doing the right thing.
        val lines = log.log.value.drop(since).map { it.message }
        val resumed = lines.filter { it.startsWith("Resuming /$remoteName from ") }
        val restarted = lines.filter { it.startsWith("Restarting /$remoteName") }

        assertTrue(
            "the engine restarted instead of resuming: $restarted",
            restarted.isEmpty(),
        )
        assertTrue(
            "the engine never logged a resume for $remoteName; it logged $lines",
            resumed.isNotEmpty(),
        )
        val offset = resumed.last().substringAfter(" from ").substringBefore(" bytes").toLong()
        assertTrue(
            "resumed from $offset bytes, which is less than the $atLeast already fetched",
            offset >= atLeast,
        )
    }

    /**
     * Fails unless the transfer really was interrupted partway.
     *
     * Without this the tests would pass on a transfer that finished before the
     * interruption arrived -- proving only that a completed download is
     * complete, which is not what any of them are for. On loopback a few
     * megabytes go by in a quarter of a second, so this is a live risk rather
     * than a theoretical one.
     */
    private fun assertPartial(bytes: Long, size: Int, what: String) {
        assertTrue(
            "$what left $bytes of $size bytes: the transfer was not interrupted partway, " +
                "so this test proved nothing",
            bytes in 1 until size.toLong(),
        )
    }

    private companion object {
        /**
         * Data-channel rate the server is held to, in bytes per second.
         *
         * Chosen so a test file takes a couple of seconds rather than a
         * fraction of one: the interruption has to arrive while the transfer
         * is still running, and over unthrottled loopback it never does.
         */
        const val THROTTLE = 2_000_000

        /**
         * About three seconds at [THROTTLE], and several times the megabyte
         * the journal records at.
         *
         * The size matters: [org.filezilla.ftp.journal.JournalledTransfer]
         * writes the byte count every megabyte, so a file smaller than that
         * records nothing between its start and its finish -- and an
         * interruption would show zero bytes transferred however much had
         * actually arrived.
         */
        const val FILE_SIZE = 6_000_000

        /** Far enough in to be a real resume, far enough from the end to land. */
        const val INTERRUPT_AT = 2_000_000L

        /** Where the stalling server goes silent; past the journal's megabyte. */
        const val STALL_AT = 2_000_000

        /**
         * How long a stop may take on a frozen transfer.
         *
         * Comfortably under [org.filezilla.ftp.protocol.FtpSettings]' default
         * read timeout, so that passing cannot mean the socket gave up by
         * itself -- which is what the old code relied on and what made the
         * button feel dead.
         */
        const val PAUSE_DEADLINE_MILLIS = 12_000L
    }

    /**
     * Waits until the transfer has moved enough to be worth interrupting.
     *
     * Watches the live progress rather than the journal. The journal is
     * written every megabyte, so polling it would wait out whole megabytes and
     * -- on a file smaller than one -- would never see movement at all.
     */
    private suspend fun awaitBytes(manager: TransferManager, id: String, atLeast: Long) {
        withTimeout(60_000) {
            while ((manager.activeTransfers.value[id]?.bytes ?: 0) < atLeast) {
                kotlinx.coroutines.delay(20)
            }
        }
    }

    // --------------------------------------------------- the Wi-Fi-only path

    /**
     * Wi-Fi off, Wi-Fi on: exactly what the user did.
     *
     * The transfer has to come back by itself. It did not: parking the queue
     * and releasing it were both tied to one worker's index, and the worker
     * left holding the transfer is whichever one claimed it -- so the worker
     * that woke was often not the one allowed to put the work back, and the
     * queue sat on "waiting for Wi-Fi" with Wi-Fi plainly on.
     */
    @Test
    fun `losing and regaining an allowed network resumes the transfer`() = runBlocking {
        val size = FILE_SIZE
        val manager = newManager()
        val id = enqueue(manager, "wifi.bin", size)

        val queue = async { drain(manager) }
        awaitBytes(manager, id, atLeast = INTERRUPT_AT)

        // Wi-Fi off. The gate says no and the running transfer is told to stop.
        openGate.set(false)
        manager.onNetworkDisallowed()
        awaitState(id, TransferState.WAITING_FOR_NETWORK)

        val held = bytesRecorded(id)
        assertPartial(held, size, "the network going away")

        // Wi-Fi on.
        val mark = log.log.value.size
        openGate.set(true)

        // Nothing else is touched: the queue is still running and must pick
        // the transfer back up on its own.
        withTimeout(60_000) { queue.await() }

        assertEquals(TransferState.COMPLETED, stateOf(id))
        assertResumedNotRestarted("wifi.bin", held, mark)
        assertArrayEquals(FtpsTestServer.contentOf(size), bytesOf(id))
    }

    /**
     * The same, but the process died while the queue was parked.
     *
     * A restarted queue starts with a working network and records held for a
     * network that is no longer a problem. Nothing held for the network is
     * claimable, and only a worker coming out of a wait released it -- a wait
     * this run never enters. So it sat there.
     */
    @Test
    fun `a queue restarted with work held for the network releases it`() = runBlocking {
        val size = FILE_SIZE
        val first = newManager()
        val id = enqueue(first, "restart.bin", size)

        val queue = async { drain(first) }
        awaitBytes(first, id, atLeast = INTERRUPT_AT)
        openGate.set(false)
        first.onNetworkDisallowed()
        awaitState(id, TransferState.WAITING_FOR_NETWORK)
        first.requestStop()
        openGate.set(true)
        queue.await()

        val held = bytesRecorded(id)
        assertEquals(TransferState.WAITING_FOR_NETWORK, stateOf(id))

        // A new queue, as a restarted process would start, on a fine network.
        val mark = log.log.value.size
        drain(newManager())

        assertEquals(TransferState.COMPLETED, stateOf(id))
        assertResumedNotRestarted("restart.bin", held, mark)
        assertArrayEquals(FtpsTestServer.contentOf(size), bytesOf(id))
    }

    // ------------------------------------------- the transfer that froze

    /**
     * The transfer that froze, and the pause button that did nothing.
     *
     * As reported from the phone: the network went, the card sat at the same
     * byte count showing the same speed, and pause was dead. The server here
     * stops sending without closing anything, which is what the phone saw --
     * no error, no end of file, just a read that never returns.
     *
     * Nothing about the connection is shortened for the test. The read timeout
     * is the product's twenty seconds, because that is precisely what the old
     * code waited for: the pause was delivered through the progress callback,
     * which the engine calls as bytes arrive, so a transfer receiving nothing
     * could not be paused until the socket gave up. The deadline below is
     * shorter than that on purpose.
     */
    @Test
    fun `pausing a frozen transfer stops it without waiting out the socket`() = runBlocking {
        val stalled = restartServerStalling()
        val manager = newManager()
        val id = enqueue(manager, "frozen.bin", FILE_SIZE)

        val queue = async { drain(manager) }
        awaitBytes(manager, id, atLeast = stalled.toLong())
        awaitFrozen(manager, id)

        manager.pause(id)
        // Well inside the read timeout, so a pass here cannot be the socket
        // timing out and the transfer stopping for its own reasons.
        withTimeout(PAUSE_DEADLINE_MILLIS) { queue.await() }

        assertEquals(TransferState.PAUSED, stateOf(id))
        assertTrue("the partial file should still be there", partials.forTransfer(id).isFile)
    }

    /** The same for cancel, which reached the transfer the same dead way. */
    @Test
    fun `cancelling a frozen transfer stops it without waiting out the socket`() = runBlocking {
        val stalled = restartServerStalling()
        val manager = newManager()
        val id = enqueue(manager, "frozen.bin", FILE_SIZE)

        val queue = async { drain(manager) }
        awaitBytes(manager, id, atLeast = stalled.toLong())
        awaitFrozen(manager, id)

        manager.cancel(id)
        withTimeout(PAUSE_DEADLINE_MILLIS) { queue.await() }

        assertNull("the record should be gone", stateOf(id))
        assertTrue("the bytes should be gone", !partials.forTransfer(id).isFile)
    }

    /**
     * Swapping one usable network for another, which is what dropping Wi-Fi
     * with mobile data on looks like.
     *
     * The queue is allowed to transfer throughout, so nothing pauses and
     * nothing waits -- but the sockets belong to the network that went. Left
     * alone the transfer sits there until the read times out; told that the
     * network moved, it should drop the dead connection and resume.
     */
    @Test
    fun `a network change reconnects a frozen transfer instead of waiting`() = runBlocking {
        // Stalls only the first data connection, so the reconnect can finish.
        val stalled = restartServerStalling(stallTimes = 1)
        val manager = newManager()
        val id = enqueue(manager, "moved.bin", FILE_SIZE)

        val queue = async { drain(manager) }
        awaitBytes(manager, id, atLeast = stalled.toLong())
        awaitFrozen(manager, id)

        val held = bytesRecorded(id)
        val mark = log.log.value.size
        manager.onNetworkChanged()
        withTimeout(PAUSE_DEADLINE_MILLIS) { queue.await() }

        assertEquals(TransferState.COMPLETED, stateOf(id))
        assertResumedInsideTheAttempt(held, mark)
        assertArrayEquals(FtpsTestServer.contentOf(FILE_SIZE), bytesOf(id))
    }

    /**
     * Fails unless the engine picked up where the dead connection left off.
     *
     * Deliberately not [assertResumedNotRestarted], which reads the queue's
     * resume decision: there is no second queue pass here. The engine
     * reconnected inside the one attempt, so the evidence is the `REST` it
     * sent -- and without it the test would pass on a transfer that quietly
     * fetched the whole file again, which is what it costs the user.
     */
    private fun assertResumedInsideTheAttempt(atLeast: Long, since: Int) {
        val lines = log.log.value.drop(since).map { it.message }
        val rest = lines.filter { it.startsWith("REST ") }
        assertTrue("the engine reconnected but never resumed; it logged $lines", rest.isNotEmpty())
        val offset = rest.last().removePrefix("REST ").trim().toLong()
        assertTrue(
            "resumed from $offset bytes, which is less than the $atLeast already fetched",
            offset >= atLeast && offset > 0,
        )
    }

    /** Replaces the throttled server with one that goes silent partway. */
    private suspend fun restartServerStalling(stallTimes: Int = 0): Int {
        server.stop()
        server = FtpsTestServer(stallAfterBytes = STALL_AT, stallTimes = stallTimes)
        server.start()
        database.sites().upsert(site())
        return STALL_AT
    }

    /**
     * Waits until the transfer has stopped receiving.
     *
     * Without this the stop could land between two chunks of a transfer that
     * is still moving, which every version of this code handles -- and the
     * test would prove nothing about the one that does not.
     */
    private suspend fun awaitFrozen(manager: TransferManager, id: String) {
        withTimeout(30_000) {
            var last = -1L
            var still = 0
            while (still < 10) {
                val now = manager.activeTransfers.value[id]?.bytes ?: 0
                still = if (now == last) still + 1 else 0
                last = now
                kotlinx.coroutines.delay(50)
            }
        }
    }

    /** Polls the journal until the transfer reaches [state]. */
    private suspend fun awaitState(id: String, state: TransferState) {
        withTimeout(60_000) {
            while (stateOf(id) != state) kotlinx.coroutines.delay(20)
        }
    }

    /**
     * The "try again now" button, on the state it exists for.
     *
     * A transfer left waiting for a network that is in fact fine is exactly
     * where the user reaches for it, so pressing it has to be enough on its
     * own -- no second interruption, no restart of the app.
     */
    @Test
    fun `restarting a network-held transfer by hand runs it`() = runBlocking {
        val size = FILE_SIZE
        val manager = newManager()
        val id = enqueue(manager, "byhand.bin", size)

        val queue = async { drain(manager) }
        awaitBytes(manager, id, atLeast = INTERRUPT_AT)
        openGate.set(false)
        manager.onNetworkDisallowed()
        awaitState(id, TransferState.WAITING_FOR_NETWORK)
        manager.requestStop()
        openGate.set(true)
        queue.await()

        // Stuck: held for the network, on a network that is fine.
        assertEquals(TransferState.WAITING_FOR_NETWORK, stateOf(id))
        val held = bytesRecorded(id)

        // What the button does.
        val mark = log.log.value.size
        manager.resume(id)
        drain(newManager())

        assertEquals(TransferState.COMPLETED, stateOf(id))
        assertResumedNotRestarted("byhand.bin", held, mark)
        assertArrayEquals(FtpsTestServer.contentOf(size), bytesOf(id))
    }
}
