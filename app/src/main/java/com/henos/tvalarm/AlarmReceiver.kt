package com.henos.tvalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DebugLog.section("EXACT ALARM FIRED (scheduled time reached)")
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<TvAlarmWorker>().build()
        )
        // Schedule tomorrow's alarm right away (exact alarms are one-shot).
        AlarmScheduler.scheduleNext(context)
        DebugLog.log("AlarmReceiver", "work enqueued, next day's alarm re-armed")
    }
}
