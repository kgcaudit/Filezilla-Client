package org.filezilla.android.data

import org.filezilla.ftp.journal.RemoteFingerprint
import org.filezilla.ftp.journal.TransferDirection
import org.filezilla.ftp.journal.TransferRecord
import org.filezilla.ftp.journal.TransferState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The journal is only worth having if what comes out of it is what went in.
 *
 * A record that loses its fingerprint or its byte count on the way through the
 * database does not fail loudly: it makes the next resume decide on partial
 * information, which is how a transfer ends up splicing bytes from two
 * different files together.
 */
class TransferEntityRoundTripTest {

    private fun record() = TransferRecord(
        id = "abc-123",
        direction = TransferDirection.DOWNLOAD,
        host = "ftp.example.org",
        port = 21,
        user = "someone",
        remotePath = "/pub/big.iso",
        localPath = "/data/partials/abc-123.part",
        destination = "content://tree/primary%3ADownload",
        state = TransferState.INTERRUPTED,
        bytesTransferred = 4_294_967_296L,
        totalBytes = 8_589_934_592L,
        fingerprint = RemoteFingerprint(size = 8_589_934_592L, modifiedMillis = 1_700_000_000_000L),
        attempts = 3,
        lastError = "connection reset",
        updatedAtMillis = 1_700_000_123_456L,
    )

    @Test
    fun `a full record survives the round trip`() {
        assertEquals(record(), TransferEntity.from(record()).toRecord())
    }

    @Test
    fun `offsets past 4 GB survive the round trip`() {
        // The column is INTEGER, so this is really a test that nothing in the
        // mapping narrows to Int -- which would silently wrap exactly the
        // large offsets the 2 GB/4 GB resume probe exists for.
        val big = record().copy(bytesTransferred = 5_000_000_000L, totalBytes = 12_000_000_000L)
        assertEquals(big, TransferEntity.from(big).toRecord())
    }

    /**
     * The flag that decides whether something gets deleted.
     *
     * Lost on the way through the database, a move would quietly become a
     * copy again -- and read back as true where it was false, it would
     * remove a file nobody asked it to. The round trip is the only thing
     * standing between the column and both of those.
     */
    @Test
    fun `being half of a move survives the round trip`() {
        val move = record().copy(removeSourceWhenDone = true)

        assertEquals(true, TransferEntity.from(move).toRecord().removeSourceWhenDone)
        assertEquals(false, TransferEntity.from(record()).toRecord().removeSourceWhenDone)
    }

    @Test
    fun `a record with no fingerprint stays without one`() {
        val none = record().copy(fingerprint = null)
        assertEquals(null, TransferEntity.from(none).toRecord().fingerprint)
    }

    @Test
    fun `an empty fingerprint is not resurrected as a usable one`() {
        // RemoteFingerprint(null, null) carries nothing and reports itself
        // unusable. Reading it back as a non-null fingerprint would make
        // ResumeSafety think it had something to compare.
        val empty = record().copy(fingerprint = RemoteFingerprint(null, null))
        assertEquals(null, TransferEntity.from(empty).toRecord().fingerprint)
    }

    @Test
    fun `every state and direction round trips`() {
        for (state in TransferState.entries) {
            for (direction in TransferDirection.entries) {
                val candidate = record().copy(state = state, direction = direction)
                assertEquals(candidate, TransferEntity.from(candidate).toRecord())
            }
        }
    }
}
