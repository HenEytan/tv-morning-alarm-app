package com.henos.tvalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object AlarmScheduler {

    private const val REQUEST_CODE = 1001

    /** Arms the next exact alarm. Returns false if the OS refused (exact-alarm permission missing). */
    fun scheduleNext(context: Context): Boolean {
        val hour = Prefs.alarmHour(context)
        val minute = Prefs.alarmMinute(context)
        val daysMask = Prefs.alarmDaysMask(context)

        // Treat anything within the next 60s as "already passed" so an alarm that fires a hair
        // early can't re-arm for the same minute and ring twice.
        val threshold = Calendar.getInstance().apply { add(Calendar.SECOND, 60) }
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(threshold)) add(Calendar.DAY_OF_YEAR, 1)
        }
        var guard = 0
        while (daysMask != 0 && !Prefs.isDaySelected(context, next.get(Calendar.DAY_OF_WEEK)) && guard < 7) {
            next.add(Calendar.DAY_OF_YEAR, 1)
            guard++
        }

        if (!canScheduleExact(context)) {
            DebugLog.log("AlarmScheduler", "scheduleNext: exact alarm permission NOT granted - cannot arm")
            return false
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pendingIntent(context))
            Prefs.saveNextAlarmAt(context, next.timeInMillis)
            DebugLog.log("AlarmScheduler", "scheduleNext: armed for ${next.time} (daysMask=$daysMask)")
            true
        } catch (e: SecurityException) {
            DebugLog.log("AlarmScheduler", "scheduleNext: SecurityException - ${e.message}")
            false
        }
    }

    fun cancel(context: Context) {
        DebugLog.log("AlarmScheduler", "cancel: cancelling pending exact alarm")
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context))
    }

    fun canScheduleExact(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (android.os.Build.VERSION.SDK_INT >= 31) alarmManager.canScheduleExactAlarms() else true
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
