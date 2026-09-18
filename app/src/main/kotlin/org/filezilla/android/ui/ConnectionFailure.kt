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
    /** The original message, shown small and last. May be blank. */
    val technical: String,
)

fun describeFailure(error: Throwable, online: Boolean): ConnectionFailure {
    val technical = buildString {
        append(error.javaClass.simpleName)
        error.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
    }

    // Offline is checked first: every network failure looks like a broken
    // server when the phone has no connection, and blaming the server would
    // send the user to fix something that is not wrong.
    if (!online && error is IOException) {
        return ConnectionFailure(R.string.fail_offline, R.string.fail_offline_advice, technical)
    }

    return when (error) {
        is UnknownHostException ->
            ConnectionFailure(R.string.fail_host, R.string.fail_host_advice, technical)

        is ConnectException, is NoRouteToHostException ->
            ConnectionFailure(R.string.fail_refused, R.string.fail_refused_advice, technical)

        is SocketTimeoutException ->
            ConnectionFailure(R.string.fail_timeout, R.string.fail_timeout_advice, technical)

        is SSLException ->
            ConnectionFailure(R.string.fail_tls, R.string.fail_tls_advice, technical)

        is FtpCommandException -> describeReply(error, technical)

        is IOException ->
            ConnectionFailure(R.string.fail_network, R.string.fail_network_advice, technical)

        else ->
            ConnectionFailure(R.string.fail_unknown, R.string.fail_unknown_advice, technical)
    }
}

/**
 * A server that answered, and said no.
 *
 * The reply itself is the technical detail here rather than the exception
 * name: `530 Login incorrect` is worth more than `FtpCommandException`.
 */
private fun describeReply(error: FtpCommandException, fallback: String): ConnectionFailure {
    val raw = error.reply.raw.takeIf { it.isNotBlank() } ?: fallback
    return when {
        error.reply.code == 530 ->
            ConnectionFailure(R.string.fail_login, R.string.fail_login_advice, raw)

        error.reply.code == 550 ->
            ConnectionFailure(R.string.fail_denied, R.string.fail_denied_advice, raw)

        // 4yz is transient by RFC 959: the same request may well work later.
        error.reply.category == 4 ->
            ConnectionFailure(R.string.fail_busy, R.string.fail_busy_advice, raw)

        else ->
            ConnectionFailure(R.string.fail_refused_command, R.string.fail_refused_command_advice, raw)
    }
}
