package com.henos.tvalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    /**
     * Everything after which the armed alarm may no longer match the settings.
     * The alarm is an absolute instant: a boot drops it, and a time-zone or
     * clock change leaves it at the old instant, i.e. the wrong local time.
     * Re-granting "Alarms & reminders" (API 31-32) is the moment an alarm that
     * could not be armed can be.
     */
    private val relevantActions = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        "android.intent.action.QUICKBOOT_POWERON",
        "com.htc.intent.action.QUICKBOOT_POWERON",
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIMEZONE_CHANGED,
        Intent.ACTION_TIME_CHANGED,
        // AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED, spelled
        // out so the receiver reads the same on API 26.
        "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
    )

    override fun onReceive(context: Context, intent: Intent) {
        DebugLog.appContext = context.applicationContext
        DebugLog.section("BOOT/UPDATE/CLOCK RECEIVED: ${intent.action}")
        if (intent.action !in relevantActions) {
            DebugLog.log("BootReceiver", "ignored action")
            return
        }
        AlarmScheduler.rearm(context, intent.action ?: "unknown")
    }
}
