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
        if (intent.action in relevantActions) {
            if (Prefs.tvIp(context).isNotBlank() && Prefs.clientKey(context) != null) {
                AlarmScheduler.scheduleNext(context)
                DebugLog.log("BootReceiver", "alarm re-armed for ${Prefs.alarmHour(context)}:${Prefs.alarmMinute(context)}")
            } else {
                DebugLog.log("BootReceiver", "skipped re-arming - not configured/paired yet")
            }
        } else {
            DebugLog.log("BootReceiver", "ignored action (not in relevant list)")
        }
    }
}
