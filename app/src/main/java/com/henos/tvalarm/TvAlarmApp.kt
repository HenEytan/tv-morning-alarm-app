package com.henos.tvalarm

import android.app.Application
import kotlin.concurrent.thread

class TvAlarmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.appContext = applicationContext
        // Off the main thread: a file delete that finds nothing after the first start.
        thread(name = "drop-legacy-log") { DebugLog.dropLegacyCopy(applicationContext) }
    }
}
