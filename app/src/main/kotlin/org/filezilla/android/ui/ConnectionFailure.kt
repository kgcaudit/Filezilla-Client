package org.filezilla.android.ui

import androidx.annotation.StringRes
import org.filezilla.android.R
import org.filezilla.ftp.protocol.FtpCommandException
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

fun describeFailure(error: Throwable, online: Boolean): ConnectionFailure {
    val host = (error as? UnknownHostException)?.message.orEmpty()
    val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

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
