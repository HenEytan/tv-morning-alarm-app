package com.henos.tvalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DebugLog.appContext = context.applicationContext
        DebugLog.section("EXACT ALARM FIRED (scheduled time reached)")

        if (!Prefs.isAlarmEnabled(context)) {
            DebugLog.log("AlarmReceiver", "alarm is disabled - not running, not re-arming")
            return
        }

        // Expedited so Doze / app-standby can't defer the actual run.
        val request = OneTimeWorkRequestBuilder<TvAlarmWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(TvAlarmWorker.INPUT_MANUAL to false))
            .build()
        // Unique work, so a run that is somehow still in flight can't end up racing
        // this one against the same TV. REPLACE rather than KEEP: the alarm that just
        // fired is the run the user actually cares about right now.
        WorkManager.getInstance(context).enqueueUniqueWork(
            TvAlarmWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request
        )

        // Exact alarms are one-shot: arm tomorrow's right away.
        AlarmScheduler.scheduleNext(context)
        DebugLog.log("AlarmReceiver", "work enqueued (expedited), next alarm re-armed")
    }
}
