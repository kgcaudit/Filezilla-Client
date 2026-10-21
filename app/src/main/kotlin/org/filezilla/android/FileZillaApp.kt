package org.filezilla.android

import android.app.Application

class FileZillaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // First, so a crash during the graph's own build is recorded too.
        CrashReporter.install(this)
        // Built here so the database is open and the journal readable before
        // anything -- a restarted service included -- asks it what was in
        // flight when the process died.
        val graph = AppGraph.of(this)
        // A crash from the previous run, if there was one, is read into the log
        // so the log screen can show it.
        CrashReporter.surfaceInto(this, graph.log)
    }
}
