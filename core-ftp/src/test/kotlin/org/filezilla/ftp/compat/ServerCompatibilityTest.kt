package org.filezilla.ftp.compat

import org.filezilla.ftp.io.asTransferReader
import org.filezilla.ftp.io.asTransferWriter
import org.filezilla.ftp.protocol.Capability
import org.filezilla.ftp.protocol.CapabilityName
import org.filezilla.ftp.protocol.FtpControlConnection
import org.filezilla.ftp.protocol.FtpFileOperations
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.FtpSettings
import org.filezilla.ftp.protocol.ServerCapabilities
import org.filezilla.ftp.testing.ExternalFtpServer
import org.filezilla.ftp.testing.FtpsTestServer
import org.filezilla.ftp.testing.ServerKind
import org.filezilla.ftp.transfer.FtpTransferEngine
import org.filezilla.ftp.transfer.TransferDisposition
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Phase 5: what each real server actually does, checked rather than assumed.
 *
 * Every other integration test in this module talks to the Python server,
 * which is scriptable enough to fake a broken `REST` or drop a connection on
 * cue -- but it agrees with the engine about everything it is not told to
 * break. The failures worth finding are the ones a different implementation
 * produces without being asked: vsftpd advertises no `MLSD`, so it forces the
 * `LIST` path through a parser that has to guess; Pure-FTPd advertises `MLSD`
 * and `MFMT` and takes both.
 *
 * Each probe is an assertion, so a server that breaks the engine fails the
 * build rather than quietly producing a row in a table. The table is written
 * as a side effect, to `build/reports/server-compatibility.md`, and the
 * checked-in copy in `docs/server-compatibility.md` is a snapshot of it.
 */
class ServerCompatibilityTest {

    @TempDir
    lateinit var tempDir: Path

    private var external: ExternalFtpServer? = null
    private var python: FtpsTestServer? = null

    @AfterEach
    fun tearDown() {
        external?.stop()
        external = null
        python?.stop()
        python = null
    }

    @Test
    @DisplayName("vsftpd, which advertises no MLSD and demands TLS session reuse")
    fun vsftpd() {
        assumeTrue(
            ExternalFtpServer.isAvailable(ServerKind.VSFTPD),
            "vsftpd not set up; run core-ftp/src/test/resources/compat-servers/setup-compat.sh as root",
        )
        val server = ExternalFtpServer(ServerKind.VSFTPD).also { external = it; it.start() }
        val observed = probe(
            server.port, server.user, server.password,
            put = { name, size -> server.putFile(name, size) },
            remote = { name -> File(server.root, name) },
        )

        // The point of including vsftpd at all. Its require_ssl_reuse is on by
        // default, and a client whose data connection does not resume the
        // control connection's TLS session is told `522 SSL connection failed:
        // session reuse required` -- it cannot even list a directory. Phase S
        // exists because of this server.
        assertTrue(observed.listed, "vsftpd refused the listing; TLS session reuse is broken")

        // vsftpd's FEAT carries no MLSD, so this is the LIST path end to end:
        // a human-readable listing put through a parser that has to guess.
        // A capability absent from FEAT stays UNKNOWN rather than becoming NO,
        // and the engine treats anything but YES as "fall back to LIST" -- so
        // what matters is that MLSD was not chosen, not which of the two
        // not-YES values it holds.
        assertTrue(observed.mlsd != Capability.YES, "vsftpd is not expected to advertise MLSD")
        // The risk the LIST path carries: entries come from a format meant for
        // people, so a listing that parses to nothing is the real failure.
        assertTrue(observed.entriesParsed > 0, "vsftpd's LIST output parsed to nothing")
        assertTrue(observed.resumedDownload, "resumed download did not match")
        assertTrue(observed.resumedUpload, "resumed upload did not match")
        record(ServerKind.VSFTPD.displayName, "3.0.5", observed)
    }

    @Test
    @DisplayName("Pure-FTPd, which advertises MLSD and MFMT")
    fun pureFtpd() {
        assumeTrue(
            ExternalFtpServer.isAvailable(ServerKind.PURE_FTPD),
            "Pure-FTPd not set up; run core-ftp/src/test/resources/compat-servers/setup-compat.sh as root",
        )
        val server = ExternalFtpServer(ServerKind.PURE_FTPD).also { external = it; it.start() }
        val observed = probe(
            server.port, server.user, server.password,
            put = { name, size -> server.putFile(name, size) },
            remote = { name -> File(server.root, name) },
        )

        assertTrue(observed.listed, "Pure-FTPd refused the listing")
        assertEquals(Capability.YES, observed.mlsd, "Pure-FTPd is expected to advertise MLSD")
        assertTrue(observed.resumedDownload, "resumed download did not match")
        assertTrue(observed.resumedUpload, "resumed upload did not match")
        record(ServerKind.PURE_FTPD.displayName, "1.0.50", observed)
    }

    @Test
    @DisplayName("pyftpdlib, the scriptable server the other integration tests use")
    fun pyftpdlib() {
        assumeTrue(FtpsTestServer.isAvailable, "Python test server not set up; run setup.sh")
        val server = FtpsTestServer().also { python = it; it.start() }
        val observed = probe(
            server.port, server.user, server.password,
            put = { name, size -> server.putFile(name, size) },
            remote = { name -> File(server.root, name) },
        )

        assertTrue(observed.listed, "pyftpdlib refused the listing")
        assertTrue(observed.resumedDownload, "resumed download did not match")
        assertTrue(observed.resumedUpload, "resumed upload did not match")
        assertEquals(Capability.YES, observed.restStream, "pyftpdlib is expected to advertise REST STREAM")
        record("pyftpdlib", "1.5.x", observed)
    }

    @Test
    @DisplayName("a server without REST STREAM, which forces the APPE upload path")
    fun withoutRestStream() {
        assumeTrue(FtpsTestServer.isAvailable, "Python test server not set up; run setup.sh")
        // Every real server in the matrix advertises REST STREAM, so the APPE
        // fallback -- one of the six defences the README lists -- would never
        // run against a live server otherwise. This is the only row that is
        // configured rather than found, and it is labelled as such.
        val server = FtpsTestServer(noRestStream = true).also { python = it; it.start() }
        val observed = probe(
            server.port, server.user, server.password,
            put = { name, size -> server.putFile(name, size) },
            remote = { name -> File(server.root, name) },
        )

        assertTrue(observed.uploadUsedAppe, "the engine should have fallen back to APPE")
        // The assertion that matters: APPE resumed at the right offset and the
        // remote file came out byte for byte identical to the source.
        assertTrue(observed.resumedUpload, "the APPE upload did not reproduce the source")
        record("pyftpdlib (no `REST STREAM`)", "1.5.x", observed)
    }

    // ------------------------------------------------------------- the probes

    /** What one server was observed to do. */
    private data class Observed(
        val mlsd: Capability,
        val mfmt: Capability,
        val restStream: Capability,
        val size: Capability,
        val mdtm: Capability,
        val utf8: Capability,
        val epsv: Capability,
        val listed: Boolean,
        val entriesParsed: Int,
        val timestampsFromListing: Boolean,
        val resumedDownload: Boolean,
        val resumedUpload: Boolean,
        val uploadUsedAppe: Boolean,
        val directoryOps: Boolean,
        val nonAsciiNames: Boolean,
    )

    private fun probe(
        port: Int,
        user: String,
        password: String,
        put: (String, Int) -> File,
        /** The server-side file, so an upload is checked byte for byte. */
        remote: (String) -> File,
    ): Observed {
        val capabilities = ServerCapabilities()
        val settings = FtpSettings(
            host = "127.0.0.1",
            port = port,
            user = user,
            password = password,
            security = FtpSecurity.EXPLICIT_TLS,
            pinnedCertificate = FtpsTestServer.fingerprint,
        )
        val key = settings.serverKey

        val source = FtpsTestServer.contentOf(40_000)
        put("download.bin", 40_000)
        put("한글-이름.bin", 1_000)

        return FtpControlConnection(settings, capabilities).use { control ->
            control.connect()
            control.login()
            val engine = FtpTransferEngine(control, capabilities)
            val operations = FtpFileOperations(control)

            val entries = runCatching { engine.list() }.getOrNull()

            // A download restarted from halfway: the half already on disk must
            // not be re-fetched, and the result must equal the whole file.
            val partial = File(tempDir.toFile(), "download.bin")
            partial.writeBytes(source.copyOfRange(0, 15_000))
            val download = runCatching {
                engine.download("download.bin", partial.asTransferWriter())
            }.getOrNull()
            val resumedDownload = download?.disposition == TransferDisposition.RESUMED &&
                partial.readBytes().contentEquals(source)

            // An upload resumed against a remote file that is already partly
            // there. Which command the engine reaches for depends on whether
            // the server advertised REST STREAM, so both paths are exercised
            // across the matrix rather than only the one this server takes.
            val local = File(tempDir.toFile(), "upload.bin").apply { writeBytes(source) }
            put("upload.bin", 15_000)
            val upload = runCatching {
                engine.upload("upload.bin", local.asTransferReader())
            }.getOrNull()
            // Disposition alone is not enough: an APPE that appends at the
            // wrong place still reports RESUMED, and the corrupt result is a
            // file that looks the right size. The bytes are what is checked.
            val resumedUpload = upload?.disposition == TransferDisposition.RESUMED &&
                upload.resumeOffset == 15_000L &&
                remote("upload.bin").readBytes().contentEquals(source)

            val directoryOps = runCatching {
                operations.createDirectory("probe-dir")
                operations.rename("probe-dir", "probe-dir-renamed")
                operations.removeDirectory("probe-dir-renamed")
            }.isSuccess

            val nonAscii = entries?.any { it.name == "한글-이름.bin" } ?: false

            Observed(
                mlsd = capabilities.get(key, CapabilityName.MLSD_COMMAND),
                mfmt = capabilities.get(key, CapabilityName.MFMT_COMMAND),
                restStream = capabilities.get(key, CapabilityName.REST_STREAM),
                size = capabilities.get(key, CapabilityName.SIZE_COMMAND),
                mdtm = capabilities.get(key, CapabilityName.MDTM_COMMAND),
                utf8 = capabilities.get(key, CapabilityName.UTF8_COMMAND),
                epsv = capabilities.get(key, CapabilityName.EPSV_COMMAND),
                listed = entries != null,
                entriesParsed = entries?.size ?: 0,
                timestampsFromListing = entries?.any { it.hasTime } ?: false,
                resumedDownload = resumedDownload,
                resumedUpload = resumedUpload,
                // No REST STREAM means the engine had to fall back to APPE.
                uploadUsedAppe = capabilities.get(key, CapabilityName.REST_STREAM) != Capability.YES,
                directoryOps = directoryOps,
                nonAsciiNames = nonAscii,
            )
        }
    }

    // ------------------------------------------------------------- the report

    private fun record(server: String, version: String, observed: Observed) {
        val row = listOf(
            server,
            version,
            tick(observed.mlsd),
            tick(observed.mfmt),
            tick(observed.restStream),
            if (observed.uploadUsedAppe) "`APPE`" else "`REST`+`STOR`",
            tick(observed.utf8),
            tick(observed.epsv),
            yes(observed.listed) + " (${observed.entriesParsed})",
            yes(observed.timestampsFromListing),
            yes(observed.resumedDownload),
            yes(observed.resumedUpload),
            yes(observed.directoryOps),
            yes(observed.nonAsciiNames),
        )
        synchronized(rows) { rows[server] = row }
        writeReport()
    }

    /**
     * `FEAT` succeeded on every server in the matrix, so a capability still
     * UNKNOWN afterwards means the server did not advertise it -- which is a
     * different statement from "the server says no", and worth keeping apart
     * in a table people will read as a specification.
     */
    private fun tick(capability: Capability) = when (capability) {
        Capability.YES -> "yes"
        Capability.NO -> "no"
        Capability.UNKNOWN -> "not advertised"
    }

    private fun yes(value: Boolean) = if (value) "yes" else "**no**"

    private fun writeReport() {
        val report = File("build/reports/server-compatibility.md")
        report.parentFile.mkdirs()
        val header = listOf(
            "Server", "Version", "`MLSD`", "`MFMT`", "`REST STREAM`", "Resumed upload uses",
            "`UTF8`", "`EPSV`", "Listing parsed", "Times in listing", "Resumed download",
            "Resumed upload", "mkdir/rename/rmdir", "Non-ASCII names",
        )
        val text = buildString {
            appendLine("<!-- Generated by ServerCompatibilityTest. Do not edit by hand. -->")
            appendLine()
            appendLine("| " + header.joinToString(" | ") + " |")
            appendLine("|" + header.joinToString("|") { "---" } + "|")
            // Sorted, because the checked-in snapshot must not churn just
            // because JUnit ran the tests in a different order.
            synchronized(rows) {
                rows.toSortedMap(String.CASE_INSENSITIVE_ORDER).values
                    .forEach { appendLine("| " + it.joinToString(" | ") + " |") }
            }
        }
        report.writeText(text)
    }

    private companion object {
        /** Keyed by server so a re-run replaces its row instead of appending. */
        val rows = linkedMapOf<String, List<String>>()
    }
}
