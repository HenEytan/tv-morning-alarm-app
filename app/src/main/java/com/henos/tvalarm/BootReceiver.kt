package com.henos.tvalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    private val relevantActions = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        "android.intent.action.QUICKBOOT_POWERON",
        "com.htc.intent.action.QUICKBOOT_POWERON",
        Intent.ACTION_MY_PACKAGE_REPLACED
    )

    override fun onReceive(context: Context, intent: Intent) {
        DebugLog.appContext = context.applicationContext
        DebugLog.section("BOOT/UPDATE RECEIVED: ${intent.action}")
        if (intent.action !in relevantActions) {
            DebugLog.log("BootReceiver", "ignored action")
            return
        }
        val configured = Prefs.tvIp(context).isNotBlank() && Prefs.clientKey(context) != null
        when {
            !configured -> DebugLog.log("BootReceiver", "skipped re-arming - not configured/paired yet")
            !Prefs.isScheduled(context) -> DebugLog.log("BootReceiver", "skipped re-arming - never scheduled")
            !Prefs.isAlarmEnabled(context) -> DebugLog.log("BootReceiver", "skipped re-arming - alarm is disabled")
            else -> {
                val ok = AlarmScheduler.scheduleNext(context)
                DebugLog.log("BootReceiver", if (ok) "alarm re-armed for ${Prefs.alarmHour(context)}:${Prefs.alarmMinute(context)}" else "could not re-arm (exact alarm permission missing?)")
            }
        }
    }
}
