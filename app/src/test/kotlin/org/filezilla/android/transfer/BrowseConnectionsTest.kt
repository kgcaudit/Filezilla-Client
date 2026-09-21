package org.filezilla.android.transfer

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.filezilla.android.data.SiteEntity
import org.filezilla.ftp.protocol.FtpSecurity
import org.filezilla.ftp.protocol.TransferMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeping a server's control connection instead of logging in every time.
 *
 * Measured before this existed: opening three folders after connecting cost
 * four logins, and making a folder and renaming it cost three. A login is a
 * TCP connect, a TLS handshake and six commands before the one that was
 * wanted -- nothing on a server across the desk, most of a second on one
 * across the internet, every time a folder is tapped.
 *
 * Reuse is the easy half. The half worth testing is what happens when the
 * connection is not there any more: servers drop idle control connections
 * after a few minutes and say nothing, so a kept one is a guess, and the
 * guess has to be wrong safely.
 */
class BrowseConnectionsTest {

    private fun site(id: String = "one", host: String = "nas") = SiteEntity(
        id = id,
        name = id,
        host = host,
        port = 21,
        user = "bob",
        passwordCipher = "x",
        security = FtpSecurity.EXPLICIT_TLS.name,
        transferMode = TransferMode.DEFAULT.name,
        trustAllCertificates = false,
        initialPath = null,
    )

    private class FakeSession : java.io.Closeable {
        var closed = false
        override fun close() { closed = true }
    }

    private class Pool(var clock: Long = 0L) {
        val opened = AtomicInteger()
        val sessions = mutableListOf<FakeSession>()
        val connections = BrowseConnections<FakeSession>(now = { clock }) {
            opened.incrementAndGet()
            FakeSession().also { sessions += it }
        }
    }

    @Test
    fun `a second browse reuses the first one's connection`() = runTest {
        val pool = Pool()

        pool.connections.withSession(site()) { }
        pool.connections.withSession(site()) { }
        pool.connections.withSession(site()) { }

        assertEquals("logged in again for a connection it already had", 1, pool.opened.get())
    }

    /** Two servers are two connections, however alike they look. */
    @Test
    fun `different servers do not share a connection`() = runTest {
        val pool = Pool()

        pool.connections.withSession(site(id = "one")) { }
        pool.connections.withSession(site(id = "two")) { }

        assertEquals(2, pool.opened.get())
    }

    /**
     * Editing a server has to reach the kept connection, or the next browse
     * would go on talking to the old host.
     */
    @Test
    fun `editing the server opens a new connection`() = runTest {
        val pool = Pool()

        pool.connections.withSession(site(host = "old")) { }
        pool.connections.withSession(site(host = "new")) { }

        assertEquals(2, pool.opened.get())
    }

    /** Idle too long and it is assumed gone rather than tried. */
    @Test
    fun `a connection left sitting is not trusted`() = runTest {
        val pool = Pool()

        pool.connections.withSession(site()) { }
        pool.clock += BrowseConnections.IDLE_LIMIT_MILLIS + 1
        pool.connections.withSession(site()) { }

        assertEquals(2, pool.opened.get())
        assertTrue("the stale connection was left open", pool.sessions[0].closed)
    }

    /**
     * The case the whole retry exists for: the server hung up while nobody
     * was looking, so the first command on the kept connection fails at IO.
     * The caller must not see that -- it is not their failure.
     */
    @Test
    fun `a connection the server dropped is replaced and the work retried`() = runTest {
        val pool = Pool()
        pool.connections.withSession(site()) { }

        var attempts = 0
        val answer = pool.connections.withSession(site()) {
            attempts++
            if (attempts == 1) throw IOException("connection reset") else "listed"
        }

        assertEquals("listed", answer)
        assertEquals("the work was not tried again", 2, attempts)
        assertEquals("no new connection was opened", 2, pool.opened.get())
    }

    /**
     * And not otherwise. A 550 for a folder that is not there is an answer,
     * not a dead socket; retrying it buys a second login to be told the
     * same thing.
     */
    @Test
    fun `an answer the server gave is not retried`() = runTest {
        val pool = Pool()
        pool.connections.withSession(site()) { }

        var attempts = 0
        val thrown = runCatching {
            pool.connections.withSession(site()) {
                attempts++
                error("550 no such directory")
            }
        }

        assertTrue(thrown.isFailure)
        assertEquals("a refusal was retried as though it were a dead socket", 1, attempts)
        assertEquals(1, pool.opened.get())
    }

    /** A fresh connection that fails at IO is not kept for the next caller. */
    @Test
    fun `a new connection that fails is not handed on`() = runTest {
        val pool = Pool()

        runCatching { pool.connections.withSession(site()) { throw IOException("dead") } }
        pool.connections.withSession(site()) { }

        assertEquals("a connection known to be broken was kept", 2, pool.opened.get())
    }

    /**
     * One at a time. A control connection carries one command and its
     * reply, so two callers on it would read each other's replies -- which
     * is not a crash but a listing showing another folder's contents.
     */
    @Test
    fun `two callers do not share the connection at once`() = runTest {
        val pool = Pool()
        val inside = AtomicInteger()
        var overlapped = false

        val first = async {
            pool.connections.withSession(site()) {
                if (inside.incrementAndGet() > 1) overlapped = true
                delay(50)
                inside.decrementAndGet()
            }
        }
        val second = async {
            pool.connections.withSession(site()) {
                if (inside.incrementAndGet() > 1) overlapped = true
                delay(50)
                inside.decrementAndGet()
            }
        }
        first.await()
        second.await()

        assertTrue("two browses were on one connection at the same time", !overlapped)
    }

    @Test
    fun `closing a server's connection opens a new one next time`() = runTest {
        val pool = Pool()

        pool.connections.withSession(site()) { }
        pool.connections.close(site())
        pool.connections.withSession(site()) { }

        assertEquals(2, pool.opened.get())
        assertTrue(pool.sessions[0].closed)
    }
}
