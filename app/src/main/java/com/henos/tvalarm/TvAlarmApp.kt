package com.henos.tvalarm

import android.app.Application

class TvAlarmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.appContext = applicationContext
    }
}
