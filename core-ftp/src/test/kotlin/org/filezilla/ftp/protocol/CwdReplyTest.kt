package org.filezilla.ftp.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Whether a `CWD` reply can be believed about where it landed.
 *
 * The caller needs the absolute path, since what it asked for may have been
 * relative or a symbolic link and the next path is built from the answer.
 * `PWD` answers reliably and costs a round trip -- a quarter of what
 * opening a folder costs on a server far enough away to notice -- so the
 * reply is read first and `PWD` asked only when it says nothing.
 *
 * Which makes reading it wrong the expensive mistake: a guess here lists
 * the wrong directory, and that is worse than the round trip it saves. So
 * the shapes real servers send are written out, and so are the ones that
 * must not be believed.
 */
class CwdReplyTest {

    /** pyftpdlib, and several NAS firmwares in the same words. */
    @Test
    fun `a reply that opens with the path`() {
        assertEquals("/HDD1/MOVIE", CwdReply.resolvedPathIn("\"/HDD1/MOVIE\" is the current directory."))
    }

    /** FileZilla Server says something first. */
    @Test
    fun `a reply that says something before the path`() {
        assertEquals(
            "/HDD1/MOVIE",
            CwdReply.resolvedPathIn("CWD successful. \"/HDD1/MOVIE\" is current directory."),
        )
    }

    /** vsftpd. Nothing to take, so the caller has to ask. */
    @Test
    fun `vsftpd names nothing`() {
        assertNull(CwdReply.resolvedPathIn("Directory successfully changed."))
    }

    /** ProFTPD. Likewise. */
    @Test
    fun `proftpd names nothing`() {
        assertNull(CwdReply.resolvedPathIn("CWD command successful"))
    }

    /**
     * The guard that makes reading any quoted run safe: a reply may quote
     * something that is not a path at all.
     */
    @Test
    fun `a quoted thing that is not a path is not believed`() {
        assertNull(CwdReply.resolvedPathIn("\"CWD\" command successful"))
        assertNull(CwdReply.resolvedPathIn("Okay, \"MOVIE\" it is"))
    }

    /** A relative answer is no answer: the caller needs an absolute path. */
    @Test
    fun `a relative path is not believed`() {
        assertNull(CwdReply.resolvedPathIn("\"MOVIE/2025\" is the current directory."))
    }

    /** RFC 959 doubles a quote inside a quoted path. */
    @Test
    fun `a path containing a quote comes back whole`() {
        assertEquals(
            "/it\"s here",
            CwdReply.resolvedPathIn("\"/it\"\"s here\" is the current directory."),
        )
    }

    /** Korean names go through unchanged; the decoding happened upstream. */
    @Test
    fun `a korean path comes back whole`() {
        assertEquals("/영화/한국", CwdReply.resolvedPathIn("\"/영화/한국\" is the current directory."))
    }

    @Test
    fun `an unterminated quote is not believed`() {
        assertNull(CwdReply.resolvedPathIn("\"/HDD1/MOVIE is the current directory."))
    }

    @Test
    fun `an empty reply is not believed`() {
        assertNull(CwdReply.resolvedPathIn(""))
    }

    /** The root, which is the shortest absolute path there is. */
    @Test
    fun `the root is a path like any other`() {
        assertEquals("/", CwdReply.resolvedPathIn("\"/\" is the current directory."))
    }
}
