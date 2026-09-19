package org.filezilla.ftp.transfer

import org.filezilla.ftp.io.TransferReader
import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.listing.ListParser
import org.filezilla.ftp.listing.MlsdParser
import org.filezilla.ftp.io.TransferWriter
import org.filezilla.ftp.protocol.Capability
import org.filezilla.ftp.protocol.CapabilityName
import org.filezilla.ftp.protocol.FtpControlConnection
import org.filezilla.ftp.protocol.FtpLogger
import org.filezilla.ftp.protocol.LogLevel
import org.filezilla.ftp.protocol.ServerCapabilities
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val TWO_GB = 1L shl 31
private const val FOUR_GB = 1L shl 32
private const val BUFFER_SIZE = 64 * 1024

/**
 * File transfers with resume, ported from `CFtpFileTransferOpData`
 * (`engine/ftp/filetransfer.cpp`).
 *
 * The original is an event-driven state machine because the engine is
 * single-threaded and non-blocking; here the same sequence runs top to bottom
 * on a blocking socket. The states it moves through are unchanged, and are
 * named in comments where they apply:
 *
 * ```
 * init -> size -> mdtm -> resumetest -> transfer -> mfmt
 *                             \-> waitresumetest -/
 * ```
 */
class FtpTransferEngine(
    private val control: FtpControlConnection,
    private val capabilities: ServerCapabilities,
    private val logger: FtpLogger = FtpLogger.NONE,
    /**
     * Lets a caller on another thread stop a transfer that is parked on a
     * read. Optional: nothing in the engine needs it, and a caller with no
     * user to answer to has nothing to abort for.
     */
    private val abort: TransferAbort? = null,
) {
    private val server get() = control.settings.serverKey

    // ------------------------------------------------------------- download

    /**
     * Downloads [remoteFile] into [writer].
     *
     * When [resume] is set and a partial file is present, the transfer
     * restarts at its length -- after the server has been checked for the
     * offset bugs that would make that unsafe.
     */
    fun download(
        remoteFile: String,
        writer: TransferWriter,
        resume: Boolean = true,
        binary: Boolean = true,
        progress: TransferProgressListener? = null,
        /**
         * Start at exactly this offset instead of deriving one from the
         * partial file's length.
         *
         * A caller that has checked the partial file against the server -- as
         * [org.filezilla.ftp.journal.JournalledTransfer] does after a restart
         * -- knows better than the file does how many of its bytes are still
         * trustworthy. Passing 0 forces a full re-fetch and truncates whatever
         * was there.
         */
        forcedResumeOffset: Long? = null,
    ): TransferOutcome =
        writer.use { doDownload(remoteFile, it, resume, binary, progress, forcedResumeOffset) }

    private fun doDownload(
        remoteFile: String,
        writer: TransferWriter,
        resume: Boolean,
        binary: Boolean,
        progress: TransferProgressListener?,
        forcedResumeOffset: Long?,
    ): TransferOutcome {
        logger.log(LogLevel.STATUS, "Starting download of $remoteFile")
        control.setTransferType(binary)

        val remoteSize = querySize(remoteFile)
        val remoteTime = if (control.settings.preserveTimestamps) queryModifiedTime(remoteFile) else null

        // filetransfer_resumetest
        val localSize = writer.existingSize
        var resumeOffset = 0L
        if (forcedResumeOffset != null) {
            resumeOffset = forcedResumeOffset
            if (resumeOffset > 0) {
                checkResumeCapability(remoteFile, resumeOffset, remoteSize)?.let { return it }
            }
        } else if (resume && localSize != null && localSize > 0) {
            when {
                remoteSize == null -> {
                    // Without a remote size the partial file cannot be checked
                    // against anything, so resume on the server's word alone.
                    resumeOffset = localSize
                }

                localSize == remoteSize -> {
                    logger.log(LogLevel.STATUS, "Local file is already complete, skipping download")
                    remoteTime?.let { writer.setModifiedTime(it) }
                    return TransferOutcome(
                        TransferDisposition.ALREADY_COMPLETE, localSize, 0, remoteSize,
                    )
                }

                localSize > remoteSize -> {
                    // The partial file is longer than the whole remote file, so
                    // it cannot be a prefix of it -- the remote file changed
                    // under us. Resuming here would splice two different files
                    // together, so start over instead. FileZilla leaves this to
                    // the user via its overwrite prompt; on a phone, silently
                    // producing a corrupt file is the worse outcome.
                    logger.log(
                        LogLevel.STATUS,
                        "Local partial file ($localSize bytes) is larger than the remote file " +
                            "($remoteSize bytes); the remote file changed, restarting download",
                    )
                    resumeOffset = 0
                }

                else -> resumeOffset = localSize
            }

            if (resumeOffset > 0) {
                checkResumeCapability(remoteFile, resumeOffset, remoteSize)?.let { return it }
            }
        }

        // filetransfer_transfer
        val transferred = runTransfer(
            transferCommand = "RETR $remoteFile",
            resumeOffset = resumeOffset,
            totalSize = remoteSize,
            progress = progress,
            receiving = true,
        ) { dataIn, _ ->
            val out = writer.openAt(resumeOffset)
            if (remoteSize != null && remoteSize > resumeOffset) {
                writer.preallocate(remoteSize - resumeOffset)
            }
            copy(dataIn!!, out, progress, resumeOffset, remoteSize)
        }

        remoteTime?.let {
            if (!writer.setModifiedTime(it)) {
                logger.log(LogLevel.DEBUG, "Could not set modification time")
            }
        }

        logger.log(LogLevel.STATUS, "File transfer successful, transferred $transferred bytes")
        return TransferOutcome(
            disposition = if (resumeOffset > 0) TransferDisposition.RESUMED else TransferDisposition.TRANSFERRED,
            resumeOffset = resumeOffset,
            bytesTransferred = transferred,
            totalSize = remoteSize ?: (resumeOffset + transferred),
        )
    }

    // --------------------------------------------------------------- upload

    /**
     * Uploads [reader] to [remoteFile].
     *
     * Resuming an upload uses `REST`+`STOR` when the server advertised
     * `REST STREAM` in `FEAT`, and `APPE` otherwise
     * (`filetransfer.cpp:150-160`).
     */
    fun upload(
        remoteFile: String,
        reader: TransferReader,
        resume: Boolean = true,
        binary: Boolean = true,
        progress: TransferProgressListener? = null,
    ): TransferOutcome = reader.use { doUpload(remoteFile, it, resume, binary, progress) }

    private fun doUpload(
        remoteFile: String,
        reader: TransferReader,
        resume: Boolean,
        binary: Boolean,
        progress: TransferProgressListener?,
    ): TransferOutcome {
        logger.log(LogLevel.STATUS, "Starting upload of $remoteFile")
        control.setTransferType(binary)

        val localSize = reader.size
        val remoteSize = if (resume) querySize(remoteFile) else null

        var resumeOffset = 0L
        if (resume && remoteSize != null && remoteSize > 0) {
            resumeOffset = remoteSize

            // The remote copy is already at least as long as the source, so
            // there is nothing to send (filetransfer.cpp:102-114).
            if (localSize != null && resumeOffset >= localSize && binary) {
                logger.log(
                    LogLevel.DEBUG,
                    "No need to resume, remote file size matches local file size.",
                )
                maybeSetRemoteTimestamp(remoteFile, reader)
                return TransferOutcome(
                    TransferDisposition.ALREADY_COMPLETE, resumeOffset, 0, remoteSize,
                )
            }
        }

        val command = when {
            resumeOffset == 0L -> "STOR $remoteFile"
            capabilities.get(server, CapabilityName.REST_STREAM) == Capability.YES ->
                "STOR $remoteFile" // REST was sent, so STOR continues from there
            else -> "APPE $remoteFile"
        }
        if (resumeOffset > 0 && command.startsWith("APPE")) {
            logger.log(LogLevel.DEBUG, "Server has no REST STREAM, resuming upload with APPE")
        }

        val transferred = runTransfer(
            transferCommand = command,
            // APPE appends by itself; sending REST as well would be wrong.
            resumeOffset = if (command.startsWith("APPE")) 0 else resumeOffset,
            totalSize = localSize,
            progress = progress,
            receiving = false,
        ) { _, dataOut ->
            val input = reader.openAt(resumeOffset)
            copy(input, dataOut!!, progress, resumeOffset, localSize)
        }

        maybeSetRemoteTimestamp(remoteFile, reader)

        logger.log(LogLevel.STATUS, "File transfer successful, transferred $transferred bytes")
        return TransferOutcome(
            disposition = if (resumeOffset > 0) TransferDisposition.RESUMED else TransferDisposition.TRANSFERRED,
            resumeOffset = resumeOffset,
            bytesTransferred = transferred,
            totalSize = localSize,
        )
    }

    // -------------------------------------------------------------- listing

    /**
     * Lists the current remote directory.
     *
     * `MLSD` is used when `FEAT` advertised it, since it is exact and
     * unambiguous; otherwise this falls back to `LIST`, whose output was meant
     * for people to read and has to be guessed at. Lines no parser recognises
     * are skipped rather than turned into half-right entries, and are reported
     * in the log so an unsupported server format is visible instead of silent.
     */
    fun list(): List<DirectoryEntry> {
        val useMlsd = capabilities.get(server, CapabilityName.MLSD_COMMAND) == Capability.YES
        control.setTransferType(binary = true)

        val lines = mutableListOf<String>()
        runTransfer(
            transferCommand = if (useMlsd) "MLSD" else "LIST",
            resumeOffset = 0,
            totalSize = null,
            progress = null,
            receiving = true,
        ) { dataIn, _ ->
            // The control connection's charset applies to the data channel too.
            dataIn!!.bufferedReader(control.charset).forEachLine { lines += it }
            lines.size.toLong()
        }

        val entries = mutableListOf<DirectoryEntry>()
        var skipped = 0
        for (raw in lines) {
            val line = raw.trimEnd('\r', '\n')
            if (line.isBlank()) continue
            val entry = if (useMlsd) MlsdParser.parse(line) else ListParser.parseLine(line)
            if (entry != null) entries += entry else skipped++
        }
        if (skipped > 0) {
            logger.log(
                LogLevel.DEBUG,
                "Skipped $skipped unparsable listing line(s); the server's format may be unsupported",
            )
        }
        logger.log(LogLevel.STATUS, "Directory listing of ${entries.size} item(s) successful")
        return entries
    }

    // ------------------------------------------------------- resume capability

    /**
     * Port of `CFtpFileTransferOpData::TestResumeCapability`
     * (`filetransfer.cpp:188-235`).
     *
     * Some older servers truncate the resume offset to 32 bits, so a `REST`
     * past 2 GB or 4 GB silently restarts from the wrong place and corrupts
     * the file. Rather than trust the server, the engine probes it: `REST
     * (remoteSize - 1)` followed by `RETR` must deliver **exactly one byte**.
     * The verdict is cached per host so the probe costs one extra data
     * connection per server, not per transfer.
     *
     * @return a finished outcome when the transfer should stop here, or null
     *   to carry on.
     */
    private fun checkResumeCapability(
        remoteFile: String,
        localSize: Long,
        remoteSize: Long?,
    ): TransferOutcome? {
        // i = 0 checks the 4 GB boundary, i = 1 the 2 GB one, as in the original.
        for (i in 0..1) {
            val boundary = if (i == 1) TWO_GB else FOUR_GB
            val limitGb = if (i == 1) 2 else 4
            val name = if (i == 1) CapabilityName.RESUME_2GB_BUG else CapabilityName.RESUME_4GB_BUG
            if (localSize < boundary) continue

            when (capabilities.get(server, name)) {
                Capability.YES -> {
                    if (remoteSize != null && remoteSize == localSize) {
                        logger.log(
                            LogLevel.DEBUG,
                            "Server does not support resume of files > $limitGb GB. " +
                                "End transfer since file sizes match.",
                        )
                        return TransferOutcome(
                            TransferDisposition.ALREADY_COMPLETE, localSize, 0, remoteSize,
                        )
                    }
                    throw ResumeUnsupportedException(
                        limitGb,
                        "Server does not support resume of files > $limitGb GB.",
                    )
                }

                Capability.UNKNOWN -> {
                    if (remoteSize == null || remoteSize < localSize) {
                        // Nothing useful to probe with.
                        break
                    }
                    if (remoteSize == localSize) {
                        logger.log(
                            LogLevel.DEBUG,
                            "Server may not support resume of files > $limitGb GB. " +
                                "End transfer since file sizes match.",
                        )
                        return TransferOutcome(
                            TransferDisposition.ALREADY_COMPLETE, localSize, 0, remoteSize,
                        )
                    }
                    probeResumeSupport(remoteFile, remoteSize, name, limitGb)
                }

                Capability.NO -> Unit // Known good at this boundary.
            }
        }
        return null
    }

    /**
     * Runs the one-byte probe. `REST (remoteSize - 1)` + `RETR` must yield a
     * single byte; more than that means the server ignored or truncated the
     * offset (`transfersocket.cpp:353-384`).
     */
    private fun probeResumeSupport(
        remoteFile: String,
        remoteSize: Long,
        name: CapabilityName,
        limitGb: Int,
    ) {
        logger.log(LogLevel.STATUS, "Testing resume capabilities of server")

        var received = 0
        val honoured = try {
            runTransfer(
                transferCommand = "RETR $remoteFile",
                resumeOffset = remoteSize - 1,
                totalSize = null,
                progress = null,
                receiving = true,
            ) { dataIn, _ ->
                val buffer = ByteArray(2)
                while (received <= 1) {
                    val n = dataIn!!.read(buffer, 0, 2)
                    if (n <= 0) break
                    received += n
                }
                received.toLong()
            }
            received == 1
        } catch (e: java.io.IOException) {
            // A refused REST, a rejected RETR, or an abort after we stopped
            // reading early all mean the same thing: the offset was not
            // honoured. Anything else would have thrown before the probe.
            logger.log(LogLevel.DEBUG, "Resume probe did not complete: ${e.message}")
            received == 1
        }

        if (honoured) {
            capabilities.set(server, name, Capability.NO)
            return
        }

        logger.log(LogLevel.DEBUG, "Server incorrectly sent $received bytes")
        capabilities.set(server, name, Capability.YES)
        throw ResumeUnsupportedException(
            limitGb,
            "Server does not support resume of files > $limitGb GB.",
        )
    }

    // ------------------------------------------------------------- plumbing

    /**
     * Opens a data connection, runs [body] over it, then reads the closing
     * reply. [body] receives the data socket's input stream for downloads or
     * its output stream for uploads, and returns the byte count.
     */
    private fun runTransfer(
        transferCommand: String,
        resumeOffset: Long,
        totalSize: Long?,
        progress: TransferProgressListener?,
        receiving: Boolean,
        body: (InputStream?, OutputStream?) -> Long,
    ): Long {
        progress?.onProgress(0, resumeOffset, totalSize)
        val data = DataConnection(control, capabilities, logger)
        // So that a caller can unblock this read rather than wait out the
        // socket's timeout; see TransferAbort.
        abort?.arm { runCatching { data.close() } }
        val transferred: Long
        try {
            val socket = data.open(transferCommand, resumeOffset)
            // A stop that landed while the connection was being opened closed
            // a socket that did not exist yet, so it is answered here instead.
            // Without this the transfer would go on to run to completion with
            // the stop already recorded, and the button would have done
            // nothing after all.
            if (abort?.isStopped == true) throw TransferAbortedException()
            transferred = if (receiving) {
                body(socket.getInputStream(), null)
            } else {
                val out = socket.getOutputStream()
                val n = body(null, out)
                out.flush()
                // The server sees end-of-file when the data connection closes,
                // so it must close before the closing reply is read.
                socket.shutdownOutput()
                n
            }
        } finally {
            abort?.disarm()
            data.close()
        }
        data.finish()
        return transferred
    }

    private fun copy(
        input: InputStream,
        output: OutputStream,
        progress: TransferProgressListener?,
        resumeOffset: Long,
        totalSize: Long?,
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            output.write(buffer, 0, n)
            total += n
            progress?.onProgress(total, resumeOffset, totalSize)
        }
        output.flush()
        return total
    }

    /** `SIZE`, guarded by what `FEAT` said. Returns null when unavailable. */
    private fun querySize(remoteFile: String): Long? {
        if (capabilities.get(server, CapabilityName.SIZE_COMMAND) == Capability.NO) return null
        val reply = control.send("SIZE $remoteFile")
        if (!reply.isSuccess) return null
        if (capabilities.get(server, CapabilityName.SIZE_COMMAND) == Capability.UNKNOWN) {
            capabilities.set(server, CapabilityName.SIZE_COMMAND, Capability.YES)
        }
        // "213 <size>", read digits only -- some servers append extra text.
        return reply.text.takeWhile { it.isDigit() }.toLongOrNull()
    }

    /** `MDTM`, returning epoch millis adjusted by the configured offset. */
    private fun queryModifiedTime(remoteFile: String): Long? {
        if (capabilities.get(server, CapabilityName.MDTM_COMMAND) == Capability.NO) return null
        val reply = control.send("MDTM $remoteFile")
        if (!reply.isSuccess) return null
        val stamp = reply.text.take(14)
        if (stamp.length != 14 || !stamp.all { it.isDigit() }) return null
        return runCatching {
            val parsed = java.time.LocalDateTime.parse(stamp, MDTM_FORMAT)
            parsed.toInstant(ZoneOffset.UTC).toEpochMilli() +
                control.settings.timezoneOffsetMinutes * 60_000L
        }.getOrNull()
    }

    /** `MFMT` after an upload, when the server supports it. */
    private fun maybeSetRemoteTimestamp(remoteFile: String, reader: TransferReader) {
        if (!control.settings.preserveTimestamps) return
        if (capabilities.get(server, CapabilityName.MFMT_COMMAND) != Capability.YES) return
        val millis = reader.modifiedTime ?: return
        val stamp = MDTM_FORMAT.format(
            Instant.ofEpochMilli(millis - control.settings.timezoneOffsetMinutes * 60_000L)
                .atZone(ZoneOffset.UTC)
                .toLocalDateTime(),
        )
        runCatching { control.send("MFMT $stamp $remoteFile") }
            .onFailure { logger.log(LogLevel.DEBUG, "MFMT failed: ${it.message}") }
    }

    private companion object {
        val MDTM_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}
