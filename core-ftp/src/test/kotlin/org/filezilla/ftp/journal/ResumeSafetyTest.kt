package org.filezilla.ftp.journal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResumeSafetyTest {

    private fun record(
        bytes: Long,
        fingerprint: RemoteFingerprint? = RemoteFingerprint(1000, 1_700_000_000_000),
    ) = TransferRecord(
        id = "t1",
        direction = TransferDirection.DOWNLOAD,
        host = "ftp.example.org",
        port = 21,
        user = "alice",
        remotePath = "/pub/file.bin",
        localPath = "/data/file.bin.part",
        bytesTransferred = bytes,
        fingerprint = fingerprint,
    )

    private val unchanged = RemoteFingerprint(1000, 1_700_000_000_000)

    @Test
    fun `resumes when nothing changed`() {
        assertEquals(
            ResumeDecision.ResumeFrom(400),
            ResumeSafety.decide(record(400), unchanged, localPartialSize = 400),
        )
    }

    @Test
    fun `restarts when the remote size changed`() {
        val d = ResumeSafety.decide(record(400), RemoteFingerprint(2000, 1_700_000_000_000), 400)
        val restart = assertInstanceOf(ResumeDecision.RestartFromZero::class.java, d)
        assertTrue(restart.reason.contains("size"), restart.reason)
    }

    @Test
    fun `restarts when the remote file was modified`() {
        // Same length, different content: the case size alone cannot catch, and
        // the one that would silently produce a corrupt file.
        val d = ResumeSafety.decide(record(400), RemoteFingerprint(1000, 1_800_000_000_000), 400)
        val restart = assertInstanceOf(ResumeDecision.RestartFromZero::class.java, d)
        assertTrue(restart.reason.contains("modified"), restart.reason)
    }

    @Test
    fun `trusts the journal rather than the file when the file is longer`() {
        // The process died between writing bytes and recording them, so the
        // unaccounted tail is not known to be correct.
        assertEquals(
            ResumeDecision.ResumeFrom(400),
            ResumeSafety.decide(record(400), unchanged, localPartialSize = 480),
        )
    }

    @Test
    fun `trusts the file when it is shorter than the journal says`() {
        assertEquals(
            ResumeDecision.ResumeFrom(250),
            ResumeSafety.decide(record(400), unchanged, localPartialSize = 250),
        )
    }

    @Test
    fun `restarts when the partial file is gone`() {
        assertInstanceOf(
            ResumeDecision.RestartFromZero::class.java,
            ResumeSafety.decide(record(400), unchanged, localPartialSize = null),
        )
        assertInstanceOf(
            ResumeDecision.RestartFromZero::class.java,
            ResumeSafety.decide(record(400), unchanged, localPartialSize = 0),
        )
    }

    @Test
    fun `reports a finished transfer as complete`() {
        assertEquals(
            ResumeDecision.AlreadyComplete,
            ResumeSafety.decide(record(1000), unchanged, localPartialSize = 1000),
        )
    }

    @Test
    fun `restarts when the partial is longer than the whole remote file`() {
        val d = ResumeSafety.decide(record(1500), RemoteFingerprint(1000, null), 1500)
        val restart = assertInstanceOf(ResumeDecision.RestartFromZero::class.java, d)
        assertTrue(restart.reason.contains("longer"), restart.reason)
    }

    @Test
    fun `resumes on size alone when no timestamp is available`() {
        // Plenty of servers answer SIZE but not MDTM. That is weaker evidence,
        // but refusing to resume at all would be worse for the user.
        assertEquals(
            ResumeDecision.ResumeFrom(400),
            ResumeSafety.decide(
                record(400, RemoteFingerprint(1000, null)),
                RemoteFingerprint(1000, null),
                400,
            ),
        )
    }

    @Test
    fun `resumes when the server tells us nothing to compare against`() {
        // Nothing can be verified, so this falls back to FileZilla's own
        // behaviour of resuming on the server's word.
        assertEquals(
            ResumeDecision.ResumeFrom(400),
            ResumeSafety.decide(record(400, null), RemoteFingerprint(null, null), 400),
        )
    }
}
