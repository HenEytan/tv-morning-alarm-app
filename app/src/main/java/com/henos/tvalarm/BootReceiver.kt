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
        if (intent.action in relevantActions) {
            if (Prefs.tvIp(context).isNotBlank() && Prefs.clientKey(context) != null) {
                AlarmScheduler.scheduleNext(context)
            }
        }
    }
}
