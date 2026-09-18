package org.filezilla.android

import android.app.Application

class FileZillaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Built here so the database is open and the journal readable before
        // anything -- a restarted service included -- asks it what was in
        // flight when the process died.
        AppGraph.of(this)
    }
}
