package org.filezilla.ftp.transfer

/**
 * A way to stop a transfer that is parked on a socket read.
 *
 * Exists because a transfer in trouble is exactly when the user reaches for
 * the pause button, and exactly when it could not be reached: a stop was only
 * delivered through the progress callback, which the engine calls as bytes
 * arrive. When the network moves underneath a phone no bytes arrive, so pause
 * and cancel did nothing at all until the socket's own timeout expired -- on a
 * default timeout, twenty seconds of a dead button.
 *
 * A blocked read on an ordinary socket does not answer an interrupt. Closing
 * the socket is what unblocks it, so that is what this does.
 *
 * Both methods are safe to call from another thread, and safe to call when no
 * transfer is running.
 */
class TransferAbort {

    @Volatile
    private var closer: (() -> Unit)? = null

    @Volatile
    private var stopped = false

    /** True once [abortAndStop] has been called; see [ResilientTransfer]. */
    val isStopped: Boolean get() = stopped

    /**
     * Registers what to close. Called by the engine while a data connection is
     * open, and cleared by [disarm] when it is not.
     */
    fun arm(closer: () -> Unit) {
        this.closer = closer
        // An abort that arrived while nothing was armed still has to land, or
        // a stop pressed a moment before the connection opened would be lost.
        if (stopped) closer()
    }

    fun disarm() {
        closer = null
    }

    /**
     * Records the stop without touching the socket.
     *
     * For a caller that must not close it on its own thread: closing a TLS
     * socket writes a close_notify, and on Android's main thread that is an
     * error rather than a slow call. Such a caller sets this here, where it
     * costs nothing, and calls [abortAndStop] from a thread that may write.
     */
    fun stop() {
        stopped = true
    }

    /**
     * Stops for good: closes the socket, and the retry loop gives up rather
     * than reconnecting. For a pause or a cancel, where reconnecting is the
     * opposite of what was asked for.
     */
    fun abortAndStop() {
        stop()
        closer?.invoke()
    }

    /**
     * Closes the socket but leaves the retry loop to carry on. For a network
     * that has moved: the connection is already dead, and waiting out its
     * timeout before reconnecting on the network that now exists is time
     * spent for nothing.
     */
    fun abortAndRetry() {
        closer?.invoke()
    }

    /** Readies it for another run of the same transfer. */
    fun reset() {
        stopped = false
        closer = null
    }
}

/**
 * Thrown when a wait between attempts is cut short because the transfer has
 * been stopped.
 *
 * An [java.io.IOException] because that is what every other way a transfer
 * ends looks like to the code above; what makes this one different is that it
 * leaves the retry loop rather than going round it again, and it never reaches
 * [RetryPolicy] to be judged.
 */
class TransferAbortedException : java.io.IOException("the transfer was stopped")
