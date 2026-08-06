package com.henos.tvalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<TvAlarmWorker>().build()
        )
        AlarmScheduler.scheduleNext(context)
    }
}
