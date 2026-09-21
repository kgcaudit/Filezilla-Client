package org.filezilla.ftp.protocol

/**
 * Directory navigation and file management, all of which happen on the control
 * connection alone.
 *
 * Ported from the small op-data classes in `engine/ftp/`: `cwd.cpp`,
 * `mkd.cpp`, `delete.cpp`, `rmd.cpp`, `rename.cpp` and `chmod.cpp`.
 */
class FtpFileOperations(private val control: FtpControlConnection) {

    /** `PWD`, returning the server's idea of the current directory. */
    fun currentDirectory(): String {
        val reply = control.send("PWD")
        if (!reply.isSuccess) throw FtpCommandException(reply, "PWD failed: ${reply.raw}")
        return extractQuotedPath(reply.text) ?: reply.text
    }

    /**
     * `CWD`, returning where the server says that landed, when it says.
     *
     * The caller has to know the absolute path it ended up at, because the
     * path it asked for may have been relative or a symbolic link and the
     * next path is built from the answer. `PWD` is the reliable way to ask
     * and costs a round trip -- which, on a server across the internet, is
     * a quarter of what opening a folder costs.
     *
     * Many servers have already answered it. FileZilla Server replies
     * `250 CWD successful. "/a" is current directory.`; so, in their own
     * wording, do pyftpdlib and several NAS firmwares. vsftpd and ProFTPD
     * say only "Directory successfully changed", and for those this
     * returns null and the caller asks properly.
     *
     * Only an absolute path is believed. A reply may quote something that
     * is not a path at all, and "starts with a slash" is the cheap test
     * that tells the two apart.
     */
    fun changeDirectory(path: String): String? {
        val reply = control.send("CWD $path")
        if (!reply.isSuccess) {
            throw FtpCommandException(reply, "could not change to $path: ${reply.raw}")
        }
        return CwdReply.resolvedPathIn(reply.text)
    }

    /** `CDUP`, falling back to `CWD ..` for servers that lack it. */
    fun changeToParentDirectory() {
        val reply = control.send("CDUP")
        if (!reply.isSuccess) changeDirectory("..")
    }

    /** `MKD`. */
    fun createDirectory(path: String) {
        val reply = control.send("MKD $path")
        if (!reply.isSuccess) {
            throw FtpCommandException(reply, "could not create $path: ${reply.raw}")
        }
    }

    /** `CWD`, as a question rather than an order. */
    fun directoryExists(path: String): Boolean = control.send("CWD $path").isSuccess

    /**
     * Makes whatever is missing above [file], so a `STOR` into it can land.
     *
     * FTP will not make a folder's parents for you, and a `STOR` into a
     * folder that is not there is refused with a 550 that names the whole
     * path and says only "No such file or directory". So the folders are
     * made first, and only the ones that are actually missing -- see
     * [RemoteDirectories].
     *
     * A folder that appears between the question and the answer is not an
     * error: two files from the same upload can reach this at once, and the
     * loser of that race wants the folder, not an exception. So a failed
     * `MKD` is only failure if the folder is still not there afterwards.
     *
     * The working directory is left where it was found. Transfers address
     * files absolutely and would not notice, but a caller between transfers
     * would, and moving somebody's connection out from under them to answer
     * a question is not this function's business.
     */
    fun ensureParentsOf(file: String) {
        // Asked before anything else, because the question itself moves the
        // connection: the only portable way to ask whether a folder is there
        // is to try to go into it. Reading the answer afterwards would read
        // the place the probe had already arrived at.
        val wasAt = runCatching { currentDirectory() }.getOrNull()
        try {
            for (path in RemoteDirectories.plan(file) { directoryExists(it) }) {
                val made = control.send("MKD $path")
                if (!made.isSuccess && !directoryExists(path)) {
                    throw FtpCommandException(made, "could not create $path: ${made.raw}")
                }
            }
        } finally {
            if (wasAt != null) runCatching { changeDirectory(wasAt) }
        }
    }

    /** `RMD`. The directory must already be empty, as FTP has no recursive form. */
    fun removeDirectory(path: String) {
        val reply = control.send("RMD $path")
        if (!reply.isSuccess) {
            throw FtpCommandException(reply, "could not remove $path: ${reply.raw}")
        }
    }

    /** `DELE`. */
    fun deleteFile(path: String) {
        val reply = control.send("DELE $path")
        if (!reply.isSuccess) {
            throw FtpCommandException(reply, "could not delete $path: ${reply.raw}")
        }
    }

    /**
     * `RNFR` followed by `RNTO`. The pair has to go out back to back, since
     * `RNFR` leaves the server holding state that the next command consumes.
     */
    fun rename(from: String, to: String) {
        val rnfr = control.send("RNFR $from")
        if (!rnfr.isPositiveIntermediate) {
            throw FtpCommandException(rnfr, "could not rename $from: ${rnfr.raw}")
        }
        val rnto = control.send("RNTO $to")
        if (!rnto.isSuccess) {
            throw FtpCommandException(rnto, "could not rename to $to: ${rnto.raw}")
        }
    }

    /** `SITE CHMOD`, which most Unix servers accept even though it is not standard. */
    fun changeMode(path: String, mode: String) {
        val reply = control.send("SITE CHMOD $mode $path")
        if (!reply.isSuccess) {
            throw FtpCommandException(reply, "could not chmod $path: ${reply.raw}")
        }
    }

    /**
     * Pulls the path out of a `PWD` reply, which quotes it and doubles any
     * embedded quote: `257 "/pub/a""b" is current directory`.
     */
    private fun extractQuotedPath(text: String): String? {
        if (!text.startsWith('"')) return null
        val out = StringBuilder()
        var i = 1
        while (i < text.length) {
            val c = text[i]
            if (c == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') {
                    out.append('"')
                    i += 2
                    continue
                }
                return out.toString()
            }
            out.append(c)
            i++
        }
        return null   // Unterminated quote; let the caller fall back.
    }
}

/**
 * Where a `CWD` reply says the connection ended up, when it says at all.
 *
 * The caller needs the absolute path it landed at, since what it asked for
 * may have been relative or a symbolic link and the next path is built from
 * the answer. `PWD` answers reliably and costs a round trip -- a quarter of
 * what opening a folder costs on a server far enough away to notice.
 *
 * Many servers have already answered in the `CWD` reply. Those that have
 * not say so by not quoting a path, and the caller asks properly. Kept
 * apart from the connection so every shape of reply can be tried without
 * one.
 */
object CwdReply {

    /**
     * The absolute path quoted in [text], or null.
     *
     * Absolute only, and that is the whole guard: a reply may quote
     * something that is not a path -- a command name, a file that was
     * acted on -- and "starts with a slash" is what tells them apart
     * cheaply. Guessing wrong here would list the wrong directory, which
     * is worse than the round trip it saves.
     */
    fun resolvedPathIn(text: String): String? {
        val start = text.indexOf('"')
        if (start < 0) return null
        val quoted = readQuoted(text.substring(start)) ?: return null
        return quoted.takeIf { it.startsWith('/') }
    }

    private fun readQuoted(text: String): String? {
        if (!text.startsWith('"')) return null
        val out = StringBuilder()
        var i = 1
        while (i < text.length) {
            val c = text[i]
            if (c == '"') {
                // RFC 959 doubles a quote inside a quoted path.
                if (i + 1 < text.length && text[i + 1] == '"') {
                    out.append('"')
                    i += 2
                    continue
                }
                return out.toString()
            }
            out.append(c)
            i++
        }
        return null
    }
}
