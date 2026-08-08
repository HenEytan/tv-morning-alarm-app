package com.henos.tvalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object AlarmScheduler {

    private const val REQUEST_CODE = 1001

    fun scheduleNext(context: Context) {
        val hour = Prefs.alarmHour(context)
        val minute = Prefs.alarmMinute(context)
        val daysMask = Prefs.alarmDaysMask(context)

        val now = Calendar.getInstance()
        var next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        // Advance to the next day that's actually selected (up to a week out).
        var guard = 0
        while (daysMask != 0 && !Prefs.isDaySelected(context, next.get(Calendar.DAY_OF_WEEK)) && guard < 7) {
            next.add(Calendar.DAY_OF_YEAR, 1)
            guard++
        }
        DebugLog.log("AlarmScheduler", "scheduleNext: next fire at ${next.time} (daysMask=$daysMask)")
        Prefs.saveNextAlarmAt(context, next.timeInMillis)

        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            next.timeInMillis,
            pendingIntent
        )
    }

    fun canScheduleExact(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }
}
