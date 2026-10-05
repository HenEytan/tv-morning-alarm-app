package com.henos.tvalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object AlarmScheduler {

    private const val REQUEST_CODE = 1001

    /** See [nextTrigger]: how close to the alarm minute the alarm's own re-arm counts as "passed". */
    const val EARLY_FIRE_GUARD_SECONDS = 60

    /**
     * The next local occurrence of [hour]:[minute] on a selected day, from [now].
     *
     * Anything within the next [graceSeconds] counts as "already passed". The
     * default 60 s is the early-fire guard for the re-arm the ALARM itself does:
     * an alarm that fires a hair early must not re-arm for the same minute and
     * ring twice. A re-arm for any other reason (a clock or zone change, a boot,
     * a restore) passes 0 — an NTP correction a few seconds before the alarm
     * minute must keep today's alarm, not skip to tomorrow's. A [daysMask] of 0
     * selects every day (the UI never saves 0, and a restored 0 is corrected by
     * Backup before it is stored). Pure; tested.
     */
    fun nextTrigger(
        hour: Int,
        minute: Int,
        daysMask: Int,
        now: Calendar = Calendar.getInstance(),
        graceSeconds: Int = EARLY_FIRE_GUARD_SECONDS,
    ): Calendar {
        val threshold = (now.clone() as Calendar).apply { add(Calendar.SECOND, graceSeconds) }
        val next = (now.clone() as Calendar).apply { setClock(hour, minute) }
        if (next.before(threshold)) next.nextDay(hour, minute)
        var guard = 0
        while (daysMask != 0 && !Prefs.isDaySelected(daysMask, next.get(Calendar.DAY_OF_WEEK)) && guard < 7) {
            next.nextDay(hour, minute)
            guard++
        }
        return next
    }

    private fun Calendar.setClock(hour: Int, minute: Int) {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    /**
     * One day on, at [hour]:[minute] again. The clock is set afresh because a
     * day step carries whatever the calendar resolved on the day it left: a
     * time in the spring-forward gap (02:30 on the day clocks jump 02:00 →
     * 03:00) resolves to 03:30, and adding a day to that kept 03:30 for every
     * later day; stepping INTO the gap day gave 01:30, an hour early, and the
     * alarm's own re-arm then rang a second time at 03:30.
     */
    private fun Calendar.nextDay(hour: Int, minute: Int) {
        add(Calendar.DAY_OF_YEAR, 1)
        setClock(hour, minute)
    }

    /**
     * Arms the next exact alarm. Returns false if the OS refused (exact-alarm
     * permission missing). [graceSeconds]: see [nextTrigger].
     */
    fun scheduleNext(context: Context, graceSeconds: Int = EARLY_FIRE_GUARD_SECONDS): Boolean {
        val daysMask = Prefs.alarmDaysMask(context)
        val next = nextTrigger(Prefs.alarmHour(context), Prefs.alarmMinute(context), daysMask, graceSeconds = graceSeconds)

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
                // Grace 0: this is not the alarm re-arming itself, so an alarm
                // still ahead of us today — even seconds ahead — stays today's.
                val ok = scheduleNext(context, graceSeconds = 0)
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
