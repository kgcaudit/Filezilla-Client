package org.filezilla.android.transfer

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.filezilla.android.data.PasswordCipher
import org.filezilla.android.data.SiteEntity
import org.filezilla.ftp.protocol.FtpLogger
import org.filezilla.ftp.protocol.LogLevel
import org.filezilla.ftp.protocol.ServerCapabilities
import java.io.IOException

/**
 * One kept-open control connection per server, for the browsing the UI does.
 *
 * Every browse used to be a whole login. Opening three folders after
 * connecting cost four of them; making a folder and renaming it cost three.
 * A login is a TCP connect, a TLS handshake, USER, PASS, FEAT, PBSZ, PROT
 * and then the command that was actually wanted -- about ten round trips
 * and a handshake, on a NAS that has to do the handshake's arithmetic
 * itself.
 *
 * On a server on the same desk that is a few tens of milliseconds and
 * nobody notices. On one across the internet it is most of a second, every
 * time a folder is tapped, which is what the user was describing when they
 * said one of their servers felt slower than the other. (It was not the
 * encoding. A pinned encoding skips the `OPTS UTF8 ON` round trip, so it
 * does strictly less work; what differed was which server was on the end.)
 *
 * So the connection is kept and handed back out. Three things make that
 * safe rather than merely faster:
 *
 *  - **One at a time.** A control connection carries one command and its
 *    reply, and two callers interleaving on it would read each other's
 *    replies. Each server's connection has a lock, and holding it is how
 *    you borrow it.
 *  - **Stale is expected.** Servers drop idle control connections, usually
 *    after five minutes, and say nothing. A connection older than
 *    [IDLE_LIMIT_MILLIS] since it was last used is thrown away unused, and
 *    a command that fails on a kept connection is retried once on a fresh
 *    one -- because the failure that matters here looks exactly like the
 *    server having hung up.
 *  - **Transfers keep their own.** A transfer occupies its connection for
 *    as long as it runs, and the user still wants to look around while a
 *    large file comes down. Nothing here touches those.
 */
class BrowseConnections<S : java.io.Closeable>(
    private val log: FtpLogger = FtpLogger.NONE,
    /**
     * The clock, so the staleness rule can be tested without waiting a
     * minute for it.
     */
    private val now: () -> Long = System::currentTimeMillis,
    /** Opens a connected session. The seam the tests replace. */
    private val open: (SiteEntity) -> S,
) {

    private inner class Kept(val session: S, var lastUsedAtMillis: Long)

    private val lock = Mutex()
    private val kept = mutableMapOf<String, Kept>()
    private val locks = mutableMapOf<String, Mutex>()

    /**
     * Runs [block] on [site]'s connection, opening or reopening one as needed.
     *
     * The block may be run twice: once on a kept connection that turned out
     * to be dead, and again on a fresh one. So it must be safe to repeat --
     * which every browsing operation is, being a question or an idempotent
     * edit, but which a transfer is not, and is one reason transfers do not
     * come through here.
     */
    suspend fun <T> withSession(site: SiteEntity, block: suspend (S) -> T): T {
        val key = keyFor(site)
        val guard = lock.withLock { locks.getOrPut(key) { Mutex() } }
        return guard.withLock {
            val existing = lock.withLock { kept[key] }?.takeIf { it.isFresh() }
            if (existing != null) {
                val reused = runCatching { block(existing.session) }
                if (reused.isSuccess) {
                    existing.lastUsedAtMillis = now()
                    return@withLock reused.getOrThrow()
                }
                val failure = reused.exceptionOrNull()
                // Anything but an IO failure is the block's own business --
                // a 550 for a folder that is not there is an answer, not a
                // dead socket, and retrying it on a new connection would
                // pay for a login to be told the same thing again.
                if (failure !is IOException) throw failure!!
                log.log(
                    LogLevel.STATUS,
                    "The kept connection had gone; opening a new one and trying again",
                )
            }
            discard(key)
            val opened = open(site)
            lock.withLock { kept[key] = Kept(opened, now()) }
            val result = runCatching { block(opened) }
            if (result.isFailure) {
                // A fresh connection that fails at IO is not worth keeping
                // either, and leaving it in the map would hand the next
                // caller a connection known to be broken.
                if (result.exceptionOrNull() is IOException) discard(key)
            } else {
                lock.withLock { kept[key]?.lastUsedAtMillis = now() }
            }
            result.getOrThrow()
        }
    }

    /** Closes everything, for a pane that has been pointed somewhere else. */
    suspend fun closeAll() {
        val all = lock.withLock { kept.values.toList().also { kept.clear() } }
        for (one in all) runCatching { one.session.close() }
    }

    /** Closes one server's connection, for a site that has been edited or deleted. */
    suspend fun close(site: SiteEntity) = discard(keyFor(site))

    private suspend fun discard(key: String) {
        val going = lock.withLock { kept.remove(key) } ?: return
        runCatching { going.session.close() }
    }

    private fun Kept.isFresh(): Boolean = now() - lastUsedAtMillis < IDLE_LIMIT_MILLIS

    /**
     * Everything about the connection that decides whether it can be reused.
     *
     * The site's id is not enough: editing a server's host or its encoding
     * must not hand the next browse a connection still talking to the old
     * one. Password is left out -- a changed password fails the next login,
     * which is the visible failure anyway.
     */
    private fun keyFor(site: SiteEntity): String = listOf(
        site.id,
        site.host,
        site.port.toString(),
        site.user,
        site.security,
        site.transferMode,
        site.encoding.orEmpty(),
        site.pinnedCertificate.orEmpty(),
    ).joinToString("\u0000")

    companion object {
        /**
         * How long a connection may sit unused before it is assumed gone.
         *
         * Servers commonly drop an idle control connection after five
         * minutes and say nothing about it; vsftpd's default is 300
         * seconds. Well under that, so the common case is a connection that
         * is certainly alive, and the retry below covers the rest.
         */
        const val IDLE_LIMIT_MILLIS = 60_000L

        /** The pool the app uses: sessions opened against a real server. */
        fun forServers(
            capabilities: ServerCapabilities,
            passwords: PasswordCipher,
            log: FtpLogger = FtpLogger.NONE,
        ): BrowseConnections<FtpSession> = BrowseConnections(log = log) { site ->
            FtpSession(site.toSettings(passwords), capabilities, log).also { it.connect() }
        }
    }
}
