package com.henos.tvalarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class PrefsTest {

    @Test
    fun bitPositionsFollowCalendarDayOfWeek() {
        val monday = 1 shl (Calendar.MONDAY - Calendar.SUNDAY)
        assertTrue(Prefs.isDaySelected(monday, Calendar.MONDAY))
        assertFalse(Prefs.isDaySelected(monday, Calendar.SUNDAY))
        assertFalse(Prefs.isDaySelected(monday, Calendar.TUESDAY))
    }

    @Test
    fun allDaysMaskSelectsEveryDay() {
        for (d in Calendar.SUNDAY..Calendar.SATURDAY) assertTrue(Prefs.isDaySelected(Prefs.ALL_DAYS_MASK, d))
    }
}
