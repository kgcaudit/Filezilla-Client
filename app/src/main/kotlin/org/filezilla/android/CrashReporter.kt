package org.filezilla.android

import android.content.Context
import android.os.Build
import org.filezilla.android.transfer.AppLog
import org.filezilla.ftp.protocol.LogLevel
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches a crash the app itself could not, so the next launch can show it.
 *
 * There is no way to read logcat off someone's phone, and a force-close takes
 * the in-memory message log down with it -- so the stack trace of an uncaught
 * exception is written to a file that survives the death, and read back into
 * the log on the next start. The user reproduces the crash once, reopens the
 * app, and the log screen holds the reason to share.
 *
 * The previous handler is still called, so the system's own crash dialog and
 * the process death are exactly as before: this records the crash, it does not
 * swallow it. The file lives in [Context.getFilesDir], not the cache, so
 * clearing the cache (which the system crash dialog offers) does not erase it.
 */
object CrashReporter {

    private const val FILE = "last-crash.txt"

    /** Starts recording uncaught exceptions. Call first thing in [onCreate]. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            // Hand back to whoever was handling crashes before, so the system
            // dialog still shows and the process still dies.
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        File(context.filesDir, FILE).writeText(
            buildString {
                append("OLO Explorer ").append(version).append('\n')
                append("Android ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append(stamp).append("  thread=").append(thread.name).append("\n\n")
                append(trace)
            },
        )
    }

    /**
     * Reads a crash left by a previous run into [log], then clears it, so the
     * log screen shows what went wrong exactly once. A no-op when the last run
     * ended cleanly.
     */
    fun surfaceInto(context: Context, log: AppLog) {
        val file = File(context.filesDir, FILE)
        if (!file.isFile) return
        val report = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        if (report.isNullOrBlank()) return
        log.log(LogLevel.ERROR, "지난 실행에서 앱이 예기치 않게 종료되었습니다 — 아래가 그 원인입니다:")
        report.trimEnd().lineSequence().forEach { log.log(LogLevel.ERROR, it) }
    }
}
