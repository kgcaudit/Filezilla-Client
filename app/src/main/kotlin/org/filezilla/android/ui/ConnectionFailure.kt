package org.filezilla.android.ui

import androidx.annotation.StringRes
import org.filezilla.android.R
import org.filezilla.ftp.net.CertificateNotTrusted
import org.filezilla.ftp.protocol.FtpCommandException
import org.filezilla.ftp.sftp.SftpStatusException
import org.filezilla.ftp.smb.SmbStatusException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * A failure turned into something a person can act on.
 *
 * What was being shown was `Throwable.message`, and for the most common
 * mistake of all -- a typo in the host name -- Java's message for
 * `UnknownHostException` is the host name and nothing else. So a mistyped
 * server produced a pink box containing only the thing the user had just
 * typed, which told them nothing about what had happened or what to do.
 *
 * Each case therefore carries three parts: what went wrong, what to do about
 * it, and the original text kept verbatim underneath -- because the person
 * who needs the raw reply is the one debugging an awkward server, and
 * throwing it away to look tidy would lose exactly what this app exists for.
 */
data class ConnectionFailure(
    @StringRes val title: Int,
    @StringRes val advice: Int,
    /**
     * The detail line, as a format string plus the one concrete value it
     * carries -- the host, the endpoint, or the server's own reply.
     *
     * A format rather than finished text so the label around the value is
     * translated too. What the value itself is depends on where it came from:
     * a server's reply stays exactly as the server said it, because that is
     * evidence, while a Java exception name is replaced, because
     * "UnknownHostException" tells a user nothing they can act on.
     */
    @StringRes val detailFormat: Int,
    val detailArg: String,
)

/**
 * The outcome of the site editor's "Test connection": it either reached the
 * server and logged in, or it did not, with the same [ConnectionFailure] the
 * panes show so the reason and the next step read identically everywhere.
 */
sealed interface SiteTestResult {
    object Success : SiteTestResult
    data class Failure(val failure: ConnectionFailure) : SiteTestResult
}

fun describeFailure(error: Throwable, online: Boolean): ConnectionFailure {
    val host = (error as? UnknownHostException)?.message.orEmpty()
    val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    // Ahead of the offline check as well, and for a stronger reason: the
    // phone reached the server perfectly well. Saying the connection is
    // down would send somebody to look at their signal over a question
    // about who they are talking to -- which is the one failure here that
    // might not be a mistake.
    val refused = generateSequence(error) { it.cause }
        .filterIsInstance<CertificateNotTrusted>()
        .firstOrNull()
    if (refused != null) {
        return ConnectionFailure(
            if (refused.changed) R.string.cert_changed_short else R.string.fail_certificate,
            R.string.fail_certificate_advice,
            R.string.detail_plain,
            refused.certificate.fingerprint,
        )
    }

    // SMB and SFTP answer a refused request with a status, not an IOException,
    // so they never reach the network cases below. The server plainly answered,
    // so -- like the certificate case -- this is settled ahead of the offline
    // check rather than being blamed on the phone's signal.
    val smb = generateSequence(error) { it.cause }.filterIsInstance<SmbStatusException>().firstOrNull()
    if (smb != null) return describeSmb(smb)

    val sftp = generateSequence(error) { it.cause }.filterIsInstance<SftpStatusException>().firstOrNull()
    if (sftp != null) return describeSftp(sftp)

    // Ahead of the offline check, because this one is not a network failure
    // at all -- nothing was sent and nothing was removed -- and the offline
    // wording would send the user to look at their signal.
    if (error is TooMuchToDeleteException) {
        return ConnectionFailure(
            R.string.fail_too_much_to_delete, R.string.fail_too_much_to_delete_advice,
            R.string.detail_plain, message,
        )
    }

    // Offline is checked first: every network failure looks like a broken
    // server when the phone has no connection, and blaming the server would
    // send the user to fix something that is not wrong.
    if (!online && error is IOException) {
        return ConnectionFailure(
            R.string.fail_offline, R.string.fail_offline_advice,
            R.string.detail_plain, message,
        )
    }

    return when (error) {
        is UnknownHostException -> ConnectionFailure(
            R.string.fail_host, R.string.fail_host_advice,
            R.string.detail_host, host,
        )

        is ConnectException, is NoRouteToHostException -> ConnectionFailure(
            R.string.fail_refused, R.string.fail_refused_advice,
            R.string.detail_refused, message,
        )

        is SocketTimeoutException -> ConnectionFailure(
            R.string.fail_timeout, R.string.fail_timeout_advice,
            R.string.detail_timeout, message,
        )

        is SSLException -> ConnectionFailure(
            R.string.fail_tls, R.string.fail_tls_advice,
            R.string.detail_tls, message,
        )

        is FtpCommandException -> describeReply(error)

        is IOException -> ConnectionFailure(
            R.string.fail_network, R.string.fail_network_advice,
            R.string.detail_plain, message,
        )

        else -> ConnectionFailure(
            R.string.fail_unknown, R.string.fail_unknown_advice,
            R.string.detail_plain, "${error.javaClass.simpleName}: $message",
        )
    }
}

/**
 * A server that answered, and said no.
 *
 * The reply is carried through untranslated and unedited. "530 Login
 * incorrect" is what the server said, and on an awkward server the exact
 * wording is the only thing that explains the failure -- so it is evidence,
 * not a message to be rewritten.
 */
private fun describeReply(error: FtpCommandException): ConnectionFailure {
    val raw = error.reply.raw.takeIf { it.isNotBlank() } ?: error.message.orEmpty()
    val (title, advice) = when {
        error.reply.code == 530 -> R.string.fail_login to R.string.fail_login_advice
        error.reply.code == 550 -> R.string.fail_denied to R.string.fail_denied_advice
        // 4yz is transient by RFC 959: the same request may well work later.
        error.reply.category == 4 -> R.string.fail_busy to R.string.fail_busy_advice
        else -> R.string.fail_refused_command to R.string.fail_refused_command_advice
    }
    return ConnectionFailure(title, advice, R.string.detail_server_reply, raw)
}

/**
 * An SMB server's refusal, turned into a cause and a next step.
 *
 * The raw status text is kept underneath as evidence, the same as a server's
 * FTP reply is -- on an awkward share it is the only thing that explains the
 * failure. The share-not-found case is the one worth its own wording: the app
 * carries the SMB share as the first part of the path, which a person needs
 * telling.
 */
private fun describeSmb(error: SmbStatusException): ConnectionFailure {
    val raw = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    val (title, advice) = when (error.kind) {
        SmbStatusException.Kind.LOGIN_FAILED -> R.string.fail_login to R.string.fail_login_advice
        SmbStatusException.Kind.SHARE_NOT_FOUND -> R.string.fail_share to R.string.fail_share_advice
        SmbStatusException.Kind.ACCESS_DENIED -> R.string.fail_denied to R.string.fail_denied_advice
        SmbStatusException.Kind.NOT_FOUND -> R.string.fail_not_found to R.string.fail_not_found_advice
        SmbStatusException.Kind.OTHER -> R.string.fail_refused_command to R.string.fail_refused_command_advice
    }
    return ConnectionFailure(title, advice, R.string.detail_plain, raw)
}

/**
 * An SFTP server's refusal, turned into a cause and a next step.
 *
 * The two codes worth distinguishing are the two a person can act on: a
 * permission refusal and a path that is not there. The numbers are the SFTP
 * protocol's own `SSH_FX_*` status codes, stable across servers.
 */
private fun describeSftp(error: SftpStatusException): ConnectionFailure {
    val raw = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    val (title, advice) = when (error.statusCode) {
        SSH_FX_NO_SUCH_FILE -> R.string.fail_not_found to R.string.fail_not_found_advice
        SSH_FX_PERMISSION_DENIED -> R.string.fail_denied to R.string.fail_denied_advice
        else -> R.string.fail_refused_command to R.string.fail_refused_command_advice
    }
    return ConnectionFailure(title, advice, R.string.detail_plain, raw)
}

private const val SSH_FX_NO_SUCH_FILE = 2
private const val SSH_FX_PERMISSION_DENIED = 3

/**
 * A local filesystem failure in the same shape as a connection one.
 *
 * One failure type across both panes, so the screen has one thing to draw.
 * The message is kept verbatim underneath for the same reason a server's
 * reply is: it is the evidence, and a folder that cannot be read is usually
 * a permission that was taken away or a card that was removed, both of which
 * the original text names and a tidied-up sentence would not.
 */
fun describeLocalFailure(error: Throwable): ConnectionFailure = ConnectionFailure(
    title = R.string.fail_local_title,
    advice = R.string.fail_local_advice,
    detailFormat = R.string.detail_plain,
    detailArg = error.message ?: error.javaClass.simpleName,
)
