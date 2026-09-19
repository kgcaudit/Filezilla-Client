package org.filezilla.android.transfer

/**
 * Turns a stream of progress callbacks into a speed worth showing.
 *
 * The engine reports every 64 KB, which on a fast link is a hundred times a
 * second and on a slow one is once every few seconds. Dividing bytes by time
 * between two such callbacks gives a figure that swings wildly -- readable
 * only by accident. So samples are taken no closer together than
 * [MIN_SAMPLE_MILLIS] and the result is smoothed, which is what makes the
 * number sit still long enough to read.
 *
 * Not a thread-safe class: one instance belongs to one running transfer.
 */
class TransferRate(private val now: () -> Long = System::currentTimeMillis) {

    private var markMillis = 0L
    private var markBytes = 0L
    private var smoothed: Double? = null

    /** Bytes per second, or null until there is enough to say. */
    val bytesPerSecond: Long?
        get() = smoothed?.takeIf { it > 0 }?.toLong()

    /**
     * Records the running total. Call it on every progress callback; it
     * decides for itself which ones become samples.
     */
    fun update(totalBytes: Long) {
        val at = now()
        if (markMillis == 0L) {
            markMillis = at
            markBytes = totalBytes
            return
        }
        val elapsed = at - markMillis
        if (elapsed < MIN_SAMPLE_MILLIS) return

        val moved = totalBytes - markBytes
        markMillis = at
        markBytes = totalBytes
        // A resume rewinds the running total; there is no speed to read from
        // that, so start again rather than report a negative one.
        if (moved < 0) {
            smoothed = null
            return
        }
        val instant = moved * 1000.0 / elapsed
        smoothed = smoothed?.let { it + SMOOTHING * (instant - it) } ?: instant
    }

    /** Forgotten between runs, so a paused transfer does not resume with a stale speed. */
    fun reset() {
        markMillis = 0L
        markBytes = 0L
        smoothed = null
    }

    companion object {
        /** Shortest gap between samples. Below this the figure is mostly noise. */
        const val MIN_SAMPLE_MILLIS = 700L

        /**
         * How much of each new sample to believe. Low enough that a stalled
         * moment does not read as zero, high enough to follow a real change.
         */
        const val SMOOTHING = 0.35
    }
}

/**
 * Seconds until a transfer ends, or null when that cannot honestly be said.
 *
 * Top-level and tested because the arithmetic has two ways to lie: an unknown
 * total makes any estimate a guess, and a rate of zero makes it infinite.
 */
fun secondsRemaining(bytes: Long, totalBytes: Long?, bytesPerSecond: Long?): Long? {
    if (totalBytes == null || totalBytes <= 0) return null
    if (bytesPerSecond == null || bytesPerSecond <= 0) return null
    val left = totalBytes - bytes
    if (left <= 0) return null
    return left / bytesPerSecond
}

/**
 * How long a running transfer may go without a byte before the screen should
 * stop claiming it is moving.
 *
 * The engine reports progress on every socket read, so on any live connection
 * this is a fraction of a second. Silence for seconds means the connection is
 * gone or the transfer is between attempts -- and in both cases the last speed
 * measured is no longer true. Long enough not to flicker on a slow link,
 * short enough that nobody sits watching a number that has stopped meaning
 * anything.
 */
const val STALL_AFTER_MILLIS = 5_000L

/**
 * Whether a transfer's figures have gone stale.
 *
 * Top-level and tested because it is the difference between a screen that
 * says what is happening and one that freezes mid-transfer showing a speed
 * from before the network went -- which is what it did.
 *
 * @param updatedAtMillis when this transfer last reported progress; 0 when it
 *   never has, which is not a stall but a transfer that has not started.
 */
fun isStalled(updatedAtMillis: Long, nowMillis: Long): Boolean =
    updatedAtMillis > 0 && nowMillis - updatedAtMillis >= STALL_AFTER_MILLIS
