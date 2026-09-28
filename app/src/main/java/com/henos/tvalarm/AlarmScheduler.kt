package com.henos.tvalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object AlarmScheduler {

    private const val REQUEST_CODE = 1001

    /**
     * The next local occurrence of [hour]:[minute] on a selected day, from [now].
     *
     * Anything within the next 60s counts as "already passed", so an alarm that
     * fires a hair early can't re-arm for the same minute and ring twice. A
     * [daysMask] of 0 selects every day (the UI never saves 0, and a restored 0
     * is corrected by Backup before it is stored). Pure; tested.
     */
    fun nextTrigger(hour: Int, minute: Int, daysMask: Int, now: Calendar = Calendar.getInstance()): Calendar {
        val threshold = (now.clone() as Calendar).apply { add(Calendar.SECOND, 60) }
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(threshold)) add(Calendar.DAY_OF_YEAR, 1)
        }
        var guard = 0
        while (daysMask != 0 && !Prefs.isDaySelected(daysMask, next.get(Calendar.DAY_OF_WEEK)) && guard < 7) {
            next.add(Calendar.DAY_OF_YEAR, 1)
            guard++
        }
        return next
    }

    /** Arms the next exact alarm. Returns false if the OS refused (exact-alarm permission missing). */
    fun scheduleNext(context: Context): Boolean {
        val daysMask = Prefs.alarmDaysMask(context)
        val next = nextTrigger(Prefs.alarmHour(context), Prefs.alarmMinute(context), daysMask)

        if (!canScheduleExact(context)) {
            DebugLog.log("AlarmScheduler", "scheduleNext: exact alarm permission NOT granted - cannot arm")
            Prefs.saveNextAlarmAt(context, 0L)
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
            Prefs.saveNextAlarmAt(context, 0L)
            false
        }
    }

    /**
     * Bring the armed alarm back in line with the stored settings, after
     * anything that could have moved them apart: a boot, an update, a clock
     * or time-zone change, a restored settings file. An alarm is armed as an
     * absolute instant, so a zone change would otherwise leave it ringing at
     * the old instant — the wrong local time — once, before correcting itself.
     *
     * Returns true when the alarm is now armed. An install that is not paired
     * (a restored settings file on a new box) has its `is_scheduled` cleared, so
     * the screen says "Not scheduled" rather than showing a green line for an
     * alarm that cannot run. A refused arm (exact-alarm permission) keeps the
     * flag — the permission-state broadcast re-arms it — and clears
     * `next_alarm_at`, which the screen reads as "saved, but not armed".
     */
    fun rearm(context: Context, reason: String): Boolean {
        val tag = "AlarmScheduler"
        val configured = Prefs.tvIp(context).isNotBlank() && Prefs.clientKey(context) != null
        val armed = when {
            !Prefs.isAlarmEnabled(context) -> {
                DebugLog.log(tag, "rearm ($reason): alarm is disabled - making sure nothing is armed")
                cancel(context)
                false
            }
            !configured -> {
                DebugLog.log(tag, "rearm ($reason): skipped - not configured/paired yet")
                if (Prefs.isScheduled(context)) Prefs.markUnscheduled(context)
                false
            }
            !Prefs.isScheduled(context) -> {
                DebugLog.log(tag, "rearm ($reason): skipped - never scheduled")
                false
            }
            else -> {
                val ok = scheduleNext(context)
                DebugLog.log(
                    tag,
                    if (ok) "rearm ($reason): alarm re-armed for ${Prefs.alarmHour(context)}:${Prefs.alarmMinute(context)}"
                    else "rearm ($reason): could not re-arm (exact alarm permission missing?)"
                )
                ok
            }
        }
        return armed
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
