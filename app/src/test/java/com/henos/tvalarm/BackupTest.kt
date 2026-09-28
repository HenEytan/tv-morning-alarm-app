package com.henos.tvalarm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    // --- what a restored number becomes ------------------------------------

    @Test
    fun hourAndMinuteAreClamped() {
        assertEquals(23, Backup.sanitizeInt("alarm_hour", 25))
        assertEquals(0, Backup.sanitizeInt("alarm_hour", -1))
        assertEquals(7, Backup.sanitizeInt("alarm_hour", 7))
        assertEquals(59, Backup.sanitizeInt("alarm_minute", 90))
        assertEquals(30, Backup.sanitizeInt("alarm_minute", 30))
    }

    @Test
    fun emptyOrOverflowingDaysMaskFallsBackToEveryDay() {
        assertEquals(Prefs.ALL_DAYS_MASK, Backup.sanitizeInt("alarm_days_mask", 0))
        assertEquals(Prefs.ALL_DAYS_MASK, Backup.sanitizeInt("alarm_days_mask", 0b10000000))
        assertEquals(0b0111110, Backup.sanitizeInt("alarm_days_mask", 0b0111110))
        assertEquals(0b0111110, Backup.sanitizeInt("alarm_days_mask", 0b10111110))
    }

    @Test
    fun volumeIsBounded() {
        assertEquals(100, Backup.sanitizeInt("wake_volume", 500))
        assertEquals(0, Backup.sanitizeInt("wake_volume", -3))
        assertEquals(15, Backup.sanitizeInt("wake_volume", 15))
    }

    // --- the file ----------------------------------------------------------

    private fun file(app: String = Backup.APP, v: Int = Backup.VERSION, settings: String = """{"alarm_hour":6}""") =
        """{"app":"$app","v":$v,"at":1700000000000,"settings":$settings}"""

    @Test
    fun aGoodFileReads() {
        val read = Backup.decode(file())
        assertTrue(read is Backup.Read.Ok)
        assertEquals(6, (read as Backup.Read.Ok).settings.getInt("alarm_hour"))
        assertEquals(1700000000000L, read.at)
    }

    @Test
    fun refusalsAreNamed() {
        assertEquals(Backup.Read.Bad(Backup.Problem.NOT_A_BACKUP), Backup.decode("not json"))
        assertEquals(Backup.Read.Bad(Backup.Problem.NOT_A_BACKUP), Backup.decode("""{"settings":{}}"""))
        assertEquals(Backup.Read.Bad(Backup.Problem.WRONG_APP), Backup.decode(file(app = "other")))
        assertEquals(Backup.Read.Bad(Backup.Problem.TOO_NEW), Backup.decode(file(v = Backup.VERSION + 1)))
        assertEquals(Backup.Read.Bad(Backup.Problem.UNREADABLE), Backup.decode(file(v = 0)))
        assertEquals(Backup.Read.Bad(Backup.Problem.UNREADABLE), Backup.decode(file(settings = "3")))
    }

    @Test
    fun theKeyIsNeverACarriedField() {
        assertTrue("client_key" in Backup.EXCLUDED)
        assertTrue("is_scheduled" in Backup.EXCLUDED)
        val settings = JSONObject(file(settings = """{"client_key":"abc"}""")).getJSONObject("settings")
        assertTrue(settings.has("client_key")) // it is in the file; apply() must skip it — see Backup.apply
    }

    // --- the daily copy ----------------------------------------------------

    @Test
    fun dueHandlesAClockThatWentBackwards() {
        assertTrue(Backup.due(0L, 1000L))
        assertTrue(Backup.due(2000L, 1000L))
        assertFalse(Backup.due(1000L, 1000L + Backup.EVERY_MS - 1))
        assertTrue(Backup.due(1000L, 1000L + Backup.EVERY_MS))
    }

    @Test
    fun namesRoundTripAndForeignFilesAreIgnored() {
        assertEquals(123L, Backup.instantOf(Backup.nameFor(123L)))
        assertNull(Backup.instantOf("auto-abc.json"))
        assertNull(Backup.instantOf("settings.json"))
        assertNull(Backup.instantOf("auto-5.json.part"))
    }

    @Test
    fun evictionKeepsTheNewest() {
        val names = listOf("auto-1.json", "auto-3.json", "auto-2.json", "stray.txt", "auto-4.json")
        assertEquals(listOf("auto-1.json"), Backup.evict(names, keep = 3))
        assertEquals(emptyList<String>(), Backup.evict(names.take(2), keep = 3))
    }
}
