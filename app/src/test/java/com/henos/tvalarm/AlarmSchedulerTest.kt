package com.henos.tvalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** The next-alarm computation, against a fixed clock. */
class AlarmSchedulerTest {

    private val zone = TimeZone.getTimeZone("Asia/Jerusalem")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Calendar =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }

    private fun mask(vararg days: Int) = days.fold(0) { m, d -> m or (1 shl (d - Calendar.SUNDAY)) }

    @Test
    fun laterTodayStaysToday() {
        val now = at(2026, 9, 28, 6, 0) // a Monday
        val next = AlarmScheduler.nextTrigger(7, 30, Prefs.ALL_DAYS_MASK, now)
        assertEquals(at(2026, 9, 28, 7, 30).timeInMillis, next.timeInMillis)
    }

    @Test
    fun alreadyPassedGoesToTomorrow() {
        val now = at(2026, 9, 28, 8, 0)
        val next = AlarmScheduler.nextTrigger(7, 30, Prefs.ALL_DAYS_MASK, now)
        assertEquals(at(2026, 9, 29, 7, 30).timeInMillis, next.timeInMillis)
    }

    @Test
    fun withinSixtySecondsCountsAsPassed() {
        // The early-fire guard: an alarm that rang at 07:29:30 must not re-arm for 07:30 today.
        val now = at(2026, 9, 28, 7, 29, 30)
        val next = AlarmScheduler.nextTrigger(7, 30, Prefs.ALL_DAYS_MASK, now)
        assertEquals(at(2026, 9, 29, 7, 30).timeInMillis, next.timeInMillis)
    }

    @Test
    fun aRearmWithNoGraceKeepsTodaysAlarm() {
        // Second review R3: a clock correction (TIME_SET) seconds before the alarm
        // minute re-arms with grace 0 and must keep today's 07:30, not skip it.
        val now = at(2026, 9, 28, 7, 29, 30)
        val next = AlarmScheduler.nextTrigger(7, 30, Prefs.ALL_DAYS_MASK, now, graceSeconds = 0)
        assertEquals(at(2026, 9, 28, 7, 30).timeInMillis, next.timeInMillis)
    }

    @Test
    fun aRearmWithNoGraceStillSkipsAPassedMinute() {
        val now = at(2026, 9, 28, 7, 30, 5)
        val next = AlarmScheduler.nextTrigger(7, 30, Prefs.ALL_DAYS_MASK, now, graceSeconds = 0)
        assertEquals(at(2026, 9, 29, 7, 30).timeInMillis, next.timeInMillis)
    }

    @Test
    fun skipsUnselectedDays() {
        val now = at(2026, 10, 2, 9, 0) // a Friday, after the alarm time
        val weekdays = mask(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        val next = AlarmScheduler.nextTrigger(7, 0, weekdays, now)
        assertEquals(Calendar.MONDAY, next.get(Calendar.DAY_OF_WEEK))
        assertEquals(at(2026, 10, 5, 7, 0).timeInMillis, next.timeInMillis)
    }

    @Test
    fun singleDayComesBackAWeekLater() {
        val now = at(2026, 9, 28, 7, 30) // Monday, exactly at the alarm minute
        val next = AlarmScheduler.nextTrigger(7, 30, mask(Calendar.MONDAY), now)
        assertEquals(at(2026, 10, 5, 7, 30).timeInMillis, next.timeInMillis)
    }

    @Test
    fun zeroMaskMeansEveryDay() {
        val now = at(2026, 9, 28, 6, 0)
        assertEquals(at(2026, 9, 28, 7, 0).timeInMillis, AlarmScheduler.nextTrigger(7, 0, 0, now).timeInMillis)
    }

    @Test
    fun doesNotMutateTheClockItWasGiven() {
        val now = at(2026, 9, 28, 8, 0)
        val copy = now.clone() as Calendar
        AlarmScheduler.nextTrigger(7, 0, Prefs.ALL_DAYS_MASK, now)
        assertEquals(copy.timeInMillis, now.timeInMillis)
    }

    @Test
    fun nextIsAlwaysInTheFuture() {
        for (h in 0..23) for (dow in Calendar.SUNDAY..Calendar.SATURDAY) {
            val now = at(2026, 9, 27 + dow - 1, h, 15)
            val next = AlarmScheduler.nextTrigger(7, 0, mask(dow), now)
            assertTrue("$h:15 on dow=$dow", next.timeInMillis > now.timeInMillis + 59_000)
            assertEquals(dow, next.get(Calendar.DAY_OF_WEEK))
        }
    }

    // Israel springs forward on Friday 2026-03-27: 02:00 becomes 03:00, so 02:30 does not exist that day.

    @Test
    fun steppingIntoTheSpringForwardGapIsNotAnHourEarly() {
        val now = at(2026, 3, 26, 23, 0)
        val next = AlarmScheduler.nextTrigger(2, 30, Prefs.ALL_DAYS_MASK, now)
        assertEquals(at(2026, 3, 27, 3, 30).timeInMillis, next.timeInMillis)
    }

    @Test
    fun theDayAfterTheGapIsBackAtTheSetTime() {
        // The alarm that rang at the resolved 03:30 re-arms for 02:30 tomorrow, not 03:30.
        val now = at(2026, 3, 27, 3, 30, 5)
        val next = AlarmScheduler.nextTrigger(2, 30, Prefs.ALL_DAYS_MASK, now)
        assertEquals(at(2026, 3, 28, 2, 30).timeInMillis, next.timeInMillis)
    }

    @Test
    fun skippedDaysAfterTheGapKeepTheSetTime() {
        val now = at(2026, 3, 27, 3, 30, 5) // Friday
        val next = AlarmScheduler.nextTrigger(2, 30, mask(Calendar.MONDAY), now)
        assertEquals(at(2026, 3, 30, 2, 30).timeInMillis, next.timeInMillis)
    }
}
