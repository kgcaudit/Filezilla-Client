package org.filezilla.ftp.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import com.jcraft.jsch.UserInfo
import org.filezilla.ftp.journal.RemoteFingerprint
import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.listing.EntryTime
import org.filezilla.ftp.listing.TimeAccuracy
import org.filezilla.ftp.protocol.FtpLogger
import org.filezilla.ftp.protocol.LogLevel
import org.filezilla.ftp.transfer.TransferAbort
import org.filezilla.ftp.transfer.TransferDisposition
import org.filezilla.ftp.transfer.TransferOutcome
import org.filezilla.ftp.transfer.TransferProgressListener
import org.filezilla.ftp.io.TransferReader
import org.filezilla.ftp.io.TransferWriter
import java.io.Closeable
import java.io.IOException

/**
 * One connected SFTP session, with the browsing and transfer operations the UI
 * needs hung off it.
 *
 * The SSH parallel of [org.filezilla.ftp.transfer.FtpTransferEngine] and the
 * browsing half of [org.filezilla.android.transfer.FtpSession] rolled into one:
 * SFTP has no separate control and data channels, so listing a directory and
 * moving a file both run over the single `ChannelSftp`. Resume, progress and
 * cancel are all the protocol's own -- `get`/`put` take an offset and a
 * progress monitor -- so this class is a thin, honest wrapper over jsch rather
 * than a reimplementation of anything.
 *
 * Server-side answers (no such file, permission denied) surface as
 * [SftpStatusException], which is deliberately *not* an [IOException]: a
 * connection pool retries an [IOException] as a dropped socket, and a refusal
 * is an answer, not a dropped socket. Transport failures -- the connection
 * dying, the host key not recognised -- do surface as [IOException], because
 * reconnecting is the right response to those.
 *
 * Not thread-safe: one of these belongs to one caller at a time, exactly like
 * an [org.filezilla.ftp.protocol.FtpControlConnection].
 */
class SftpEngine(
    private val settings: SftpSettings,
    private val logger: FtpLogger = FtpLogger.NONE,
    /**
     * Lets a caller on another thread stop a transfer parked on a read, by
     * closing the channel. Optional, mirroring [FtpTransferEngine].
     */
    private val abort: TransferAbort? = null,
) : Closeable {

    private var session: Session? = null
    private var channel: ChannelSftp? = null
    private val hostKeys = VerifyingHostKeyRepository(settings.knownHostKey)

    /** What the server presented on the last connect, once one has happened. */
    val hostKey: SshHostKey? get() = hostKeys.seen

    private val sftp: ChannelSftp
        get() = channel ?: throw IOException("not connected")

    // -------------------------------------------------------------- connect

    /**
     * Opens the connection and logs in, verifying the host key first.
     *
     * The host key check is the whole reason connect can fail before a
     * password is even tried: an unrecognised or changed key raises
     * [HostKeyNotTrusted] rather than logging in to a server nobody vouched
     * for.
     */
    fun connect() {
        logger.log(LogLevel.STATUS, "Connecting to ${settings.host}:${settings.port} over SSH")
        val jsch = JSch()
        jsch.hostKeyRepository = hostKeys
        settings.privateKeyPem?.let { pem ->
            jsch.addIdentity(
                "${settings.user}@${settings.host}",
                pem.toByteArray(Charsets.UTF_8),
                null,
                settings.password.toByteArray(Charsets.UTF_8),
            )
        }
        val newSession = jsch.getSession(settings.user, settings.host, settings.port)
        if (settings.privateKeyPem == null) newSession.setPassword(settings.password)
        // "yes" means an unrecognised or changed key fails the connect rather
        // than prompting a console nobody is watching. We then turn that
        // failure into a HostKeyNotTrusted the app can put a dialog on.
        newSession.setConfig("StrictHostKeyChecking", "yes")
        // Password first: a saved server logs in with the password it was
        // given, and trying GSSAPI before it stalls on networks that answer
        // the probe and then time out.
        newSession.setConfig(
            "PreferredAuthentications",
            if (settings.privateKeyPem != null) "publickey" else "password,keyboard-interactive",
        )
        newSession.timeout = settings.readTimeoutMillis
        try {
            newSession.connect(settings.connectTimeoutMillis)
        } catch (e: JSchException) {
            // The repository saw the key and turned it down; that is a question
            // for the person, not a transport error.
            hostKeys.refusal()?.let {
                runCatching { newSession.disconnect() }
                throw it
            }
            throw IOException("could not connect to ${settings.host}: ${e.message}", e)
        }
        session = newSession
        val newChannel = try {
            (newSession.openChannel("sftp") as ChannelSftp).also { it.connect(settings.connectTimeoutMillis) }
        } catch (e: JSchException) {
            runCatching { newSession.disconnect() }
            session = null
            throw IOException("could not open an SFTP channel to ${settings.host}: ${e.message}", e)
        }
        channel = newChannel
        logger.log(LogLevel.STATUS, "Connected")
    }

    // ------------------------------------------------------------- browsing

    fun currentDirectory(): String = transport { sftp.pwd() }

    /** Returns where that landed, matching [FtpSession.changeDirectory]. */
    fun changeDirectory(path: String): String? = operation {
        sftp.cd(path)
        sftp.pwd()
    }

    fun changeToParent() = operation { sftp.cd("..") }

    /** Lists the current directory. */
    fun list(): List<DirectoryEntry> = operation {
        val entries = ArrayList<DirectoryEntry>()
        // The selector form rather than the Vector-returning one, so there is
        // no raw-collection cast to suppress a warning over.
        sftp.ls(".") { entry ->
            val name = entry.filename
            if (name != "." && name != "..") entries += toDirectoryEntry(name, entry.attrs)
            ChannelSftp.LsEntrySelector.CONTINUE
        }
        entries
    }

    fun createDirectory(path: String) = operation { sftp.mkdir(path) }

    fun removeDirectory(path: String) = operation { sftp.rmdir(path) }

    fun deleteFile(path: String) = operation { sftp.rm(path) }

    fun rename(from: String, to: String) = operation { sftp.rename(from, to) }

    /** `chmod`, taking the three-or-four octal digits the UI edits. */
    fun changeMode(path: String, mode: String) = operation {
        val bits = mode.toInt(8)
        sftp.chmod(bits, path)
    }

    /**
     * What the server says about [remotePath] right now, for resume safety to
     * compare against what the journal remembers.
     *
     * A missing file is a usable answer here -- an empty fingerprint -- not a
     * failure: the resume machinery treats "no size, no time" as "cannot
     * resume", which is the safe reading of a file that is not there.
     */
    fun fingerprint(remotePath: String): RemoteFingerprint = try {
        val attrs = transport { sftp.stat(remotePath) }
        RemoteFingerprint(
            size = attrs.size.takeIf { !attrs.isDir },
            modifiedMillis = attrs.mTime.toLong() * 1000L,
        )
    } catch (e: SftpStatusException) {
        logger.log(LogLevel.DEBUG, "No fingerprint for $remotePath: ${e.message}")
        RemoteFingerprint(size = null, modifiedMillis = null)
    }

    // ----------------------------------------------------------- transferring

    /**
     * Downloads [remoteFile] into [writer], resuming from the partial file's
     * length when [resume] is set.
     *
     * The resume decision mirrors [FtpTransferEngine.download]: a partial file
     * that already matches the remote size is complete and skipped; one longer
     * than the remote file means the remote file changed and the download
     * starts over rather than splicing two files together.
     */
    fun download(
        remoteFile: String,
        writer: TransferWriter,
        resume: Boolean = true,
        progress: TransferProgressListener? = null,
        /** Start at exactly this offset instead of the partial file's length. */
        forcedResumeOffset: Long? = null,
    ): TransferOutcome = writer.use { doDownload(remoteFile, it, resume, progress, forcedResumeOffset) }

    private fun doDownload(
        remoteFile: String,
        writer: TransferWriter,
        resume: Boolean,
        progress: TransferProgressListener?,
        forcedResumeOffset: Long?,
    ): TransferOutcome {
        logger.log(LogLevel.STATUS, "Starting download of $remoteFile")
        val remote = fingerprint(remoteFile)
        val remoteSize = remote.size
        val localSize = writer.existingSize

        var resumeOffset = 0L
        when {
            forcedResumeOffset != null -> resumeOffset = forcedResumeOffset
            resume && localSize != null && localSize > 0 -> resumeOffset = when {
                remoteSize == null -> localSize
                localSize == remoteSize -> {
                    logger.log(LogLevel.STATUS, "Local file is already complete, skipping download")
                    remote.modifiedMillis?.let { writer.setModifiedTime(it) }
                    return TransferOutcome(TransferDisposition.ALREADY_COMPLETE, localSize, 0, remoteSize)
                }
                localSize > remoteSize -> {
                    logger.log(
                        LogLevel.STATUS,
                        "Local partial file ($localSize bytes) is larger than the remote file " +
                            "($remoteSize bytes); the remote file changed, restarting download",
                    )
                    0
                }
                else -> localSize
            }
        }

        val out = writer.openAt(resumeOffset)
        if (remoteSize != null && remoteSize > resumeOffset) writer.preallocate(remoteSize - resumeOffset)
        // jsch's `get` in RESUME mode counts the skipped bytes into the monitor
        // before the first real byte, so the running total it reports is
        // offset-inclusive. The progress contract here is the FTP one -- bytes
        // moved *this* attempt, excluding the resume offset -- so that offset
        // is the baseline subtracted back out.
        val monitor = ProgressMonitor(progress, resumeOffset, remoteSize, baseline = resumeOffset)
        transfer {
            // A stop that landed before the first byte still has to end the
            // transfer, exactly as the FTP engine answers it before opening a
            // data connection.
            if (abort?.isStopped == true) throw org.filezilla.ftp.transfer.TransferAbortedException()
            sftp.get(remoteFile, out, monitor, ChannelSftp.RESUME, resumeOffset)
        }
        out.flush()

        remote.modifiedMillis?.let {
            if (!writer.setModifiedTime(it)) logger.log(LogLevel.DEBUG, "Could not set modification time")
        }

        val transferred = monitor.transferred
        logger.log(LogLevel.STATUS, "File transfer successful, transferred $transferred bytes")
        return TransferOutcome(
            disposition = if (resumeOffset > 0) TransferDisposition.RESUMED else TransferDisposition.TRANSFERRED,
            resumeOffset = resumeOffset,
            bytesTransferred = transferred,
            totalSize = remoteSize ?: (resumeOffset + transferred),
        )
    }

    /**
     * Uploads [reader] to [remoteFile], resuming onto a partial remote copy
     * when [resume] is set.
     *
     * Resume appends the source from the remote file's length with `APPEND`;
     * there is no `REST`/`APPE` distinction to make as there is for FTP,
     * because SFTP writes at an explicit offset. Missing parent directories
     * are made first, for the same reason the FTP engine makes them: the
     * server will not, and a reconnect between attempts can land on a session
     * that has never seen them.
     */
    fun upload(
        remoteFile: String,
        reader: TransferReader,
        resume: Boolean = true,
        progress: TransferProgressListener? = null,
    ): TransferOutcome = reader.use { doUpload(remoteFile, it, resume, progress) }

    private fun doUpload(
        remoteFile: String,
        reader: TransferReader,
        resume: Boolean,
        progress: TransferProgressListener?,
    ): TransferOutcome {
        logger.log(LogLevel.STATUS, "Starting upload of $remoteFile")
        ensureParentsOf(remoteFile)

        val localSize = reader.size
        val remoteSize = if (resume) fingerprint(remoteFile).size else null

        var resumeOffset = 0L
        if (resume && remoteSize != null && remoteSize > 0) {
            resumeOffset = remoteSize
            if (localSize != null && resumeOffset >= localSize) {
                logger.log(LogLevel.DEBUG, "No need to resume, remote file size matches local file size.")
                maybeSetRemoteTimestamp(remoteFile, reader)
                return TransferOutcome(TransferDisposition.ALREADY_COMPLETE, resumeOffset, 0, remoteSize)
            }
        }

        val input = reader.openAt(resumeOffset)
        // Uploads count only the bytes actually sent (APPEND appends the
        // stream, OVERWRITE writes it from the start), so no baseline to remove.
        val monitor = ProgressMonitor(progress, resumeOffset, localSize, baseline = 0)
        val mode = if (resumeOffset > 0) ChannelSftp.APPEND else ChannelSftp.OVERWRITE
        transfer {
            if (abort?.isStopped == true) throw org.filezilla.ftp.transfer.TransferAbortedException()
            sftp.put(input, remoteFile, monitor, mode)
        }

        maybeSetRemoteTimestamp(remoteFile, reader)

        val transferred = monitor.transferred
        logger.log(LogLevel.STATUS, "File transfer successful, transferred $transferred bytes")
        return TransferOutcome(
            disposition = if (resumeOffset > 0) TransferDisposition.RESUMED else TransferDisposition.TRANSFERRED,
            resumeOffset = resumeOffset,
            bytesTransferred = transferred,
            totalSize = localSize ?: (resumeOffset + transferred),
        )
    }

    /**
     * Makes the directories [remoteFile] sits in, ignoring the ones already
     * there. The SSH parallel of `FtpFileOperations.ensureParentsOf`.
     */
    private fun ensureParentsOf(remoteFile: String) {
        val directory = remoteFile.substringBeforeLast('/', "")
        if (directory.isEmpty()) return
        val absolute = directory.startsWith('/')
        val parts = directory.split('/').filter { it.isNotEmpty() }
        var path = if (absolute) "" else "."
        for (part in parts) {
            path = if (path.isEmpty()) "/$part" else "$path/$part"
            val exists = runCatching { transport { sftp.stat(path) } }.isSuccess
            if (!exists) operation { sftp.mkdir(path) }
        }
    }

    private fun maybeSetRemoteTimestamp(remoteFile: String, reader: TransferReader) {
        if (!settings.preserveTimestamps) return
        val millis = reader.modifiedTime ?: return
        runCatching { operation { sftp.setMtime(remoteFile, (millis / 1000L).toInt()) } }
            .onFailure { logger.log(LogLevel.DEBUG, "Could not set remote modification time: ${it.message}") }
    }

    // -------------------------------------------------------------- plumbing

    /** Runs a transfer with the abort armed to close the channel. */
    private inline fun <T> transfer(body: () -> T): T {
        abort?.arm { runCatching { channel?.disconnect() } }
        return try {
            operation(body)
        } finally {
            abort?.disarm()
        }
    }

    private fun toDirectoryEntry(name: String, attrs: SftpATTRS): DirectoryEntry = DirectoryEntry(
        name = name,
        // A directory's on-disk size is its inode size, which is not what a
        // person means by "how big is this folder", so it is left unknown --
        // matching how a LIST/MLSD entry for a directory carries no size.
        size = if (attrs.isDir) -1L else attrs.size,
        isDirectory = attrs.isDir,
        isLink = attrs.isLink,
        // SFTP mtime is whole seconds, so a listing already carries a usable
        // time and no second round trip is needed, unlike a Unix LIST line.
        time = EntryTime(attrs.mTime.toLong() * 1000L, TimeAccuracy.SECONDS),
        permissions = attrs.permissionsString,
    )

    /**
     * Runs a jsch call, translating its two failure kinds.
     *
     * A [SftpException] is the server's answer -- no such file, permission
     * denied -- unless it carries a lost connection, so it becomes a
     * [SftpStatusException], which is not an [IOException] and so is not
     * retried as a dropped socket. Anything that is a lost connection, and any
     * [JSchException], is transport trouble and becomes an [IOException].
     */
    private inline fun <T> operation(body: () -> T): T = try {
        body()
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_CONNECTION_LOST || e.cause is IOException) {
            throw IOException("the SFTP connection failed: ${e.message}", e)
        }
        throw SftpStatusException(e.id, e.message ?: "SFTP operation failed", e)
    } catch (e: JSchException) {
        throw IOException("the SFTP connection failed: ${e.message}", e)
    }

    /** As [operation], for calls where every failure is transport trouble. */
    private inline fun <T> transport(body: () -> T): T = try {
        body()
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
            throw SftpStatusException(e.id, e.message ?: "no such file", e)
        }
        throw IOException("the SFTP connection failed: ${e.message}", e)
    } catch (e: JSchException) {
        throw IOException("the SFTP connection failed: ${e.message}", e)
    }

    override fun close() {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        channel = null
        session = null
    }

    /**
     * Accumulates jsch's incremental counts and reports progress and cancel.
     *
     * [baseline] is how many of jsch's counted bytes are the resume offset
     * rather than bytes moved this attempt; it is subtracted so [transferred]
     * and what is reported match the FTP engine's meaning of "moved this
     * attempt".
     */
    private inner class ProgressMonitor(
        private val progress: TransferProgressListener?,
        private val resumeOffset: Long,
        private val totalSize: Long?,
        private val baseline: Long,
    ) : SftpProgressMonitor {
        private var raw = 0L

        /** Bytes moved this attempt, excluding the resume offset. */
        var transferred = 0L
            private set

        override fun init(op: Int, src: String?, dest: String?, max: Long) {
            progress?.onProgress(0, resumeOffset, totalSize)
        }

        override fun count(count: Long): Boolean {
            raw += count
            transferred = (raw - baseline).coerceAtLeast(0)
            progress?.onProgress(transferred, resumeOffset, totalSize)
            // Returning false is how jsch is told to stop; the armed abort has
            // usually closed the channel already, and this ends the loop for
            // the case where it has not.
            return abort?.isStopped != true
        }

        override fun end() {}
    }

    /**
     * The host key repository that turns jsch's built-in known-hosts check into
     * our recognise-or-ask flow.
     *
     * jsch calls [check] with the raw key the server sent. We record it either
     * way, so that a connect which then fails can be turned into a
     * [HostKeyNotTrusted] carrying what was seen -- there is no other way to
     * get the key back out of jsch once the connect has thrown.
     */
    private class VerifyingHostKeyRepository(private val pinned: String?) : HostKeyRepository {

        @Volatile
        var seen: SshHostKey? = null
            private set

        @Volatile
        private var mismatch = false

        /** The refusal to raise for a failed connect, or null if the key was fine. */
        fun refusal(): HostKeyNotTrusted? {
            val key = seen ?: return null
            if (pinned != null && !mismatch) return null
            return HostKeyNotTrusted(key, previouslyTrusted = pinned?.takeIf { mismatch })
        }

        override fun check(host: String?, key: ByteArray?): Int {
            val blob = key ?: return HostKeyRepository.NOT_INCLUDED
            val hostKey = SshHostKey.of(blob)
            seen = hostKey
            if (pinned == null) return HostKeyRepository.NOT_INCLUDED
            if (hostKey.fingerprint.equals(pinned, ignoreCase = true)) return HostKeyRepository.OK
            mismatch = true
            return HostKeyRepository.CHANGED
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String? = null
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }
}

/**
 * A server-side SFTP error: no such file, permission denied, and the like.
 *
 * A [RuntimeException] and pointedly not an [IOException], so that a connection
 * pool which retries an [IOException] as a dropped socket leaves this alone --
 * it is the server's answer to the request, and asking again on a fresh
 * connection would only be told the same thing.
 */
class SftpStatusException(
    /** The SFTP status code, e.g. `SSH_FX_NO_SUCH_FILE`. */
    val statusCode: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
