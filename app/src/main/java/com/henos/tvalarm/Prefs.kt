package com.henos.tvalarm

import android.content.Context
import android.os.Build
import java.util.Calendar
import java.util.UUID

object Prefs {
    private const val NAME = "tvalarm_prefs"

    // Bitmask bit positions match java.util.Calendar.DAY_OF_WEEK (SUNDAY=1..SATURDAY=7),
    // stored as bit (dayOfWeek - 1) so SUNDAY=bit0 .. SATURDAY=bit6.
    const val ALL_DAYS_MASK = 0b1111111
    private const val DEFAULT_DAYS_MASK = ALL_DAYS_MASK // preserves old "every day" behavior

    fun get(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun tvIp(context: Context) = get(context).getString("tv_ip", "") ?: ""
    fun tvMac(context: Context) = get(context).getString("tv_mac", "") ?: ""
    fun playlistUri(context: Context) = get(context).getString("playlist_uri", "") ?: ""
    fun spotifyAppId(context: Context) = get(context).getString("spotify_app_id", "spotify-beehive") ?: "spotify-beehive"
    fun clientKey(context: Context) = get(context).getString("client_key", null)
    fun alarmHour(context: Context) = get(context).getInt("alarm_hour", 7)
    fun alarmMinute(context: Context) = get(context).getInt("alarm_minute", 0)
    fun alarmDaysMask(context: Context) = get(context).getInt("alarm_days_mask", DEFAULT_DAYS_MASK)
    fun wakeVolume(context: Context) = get(context).getInt("wake_volume", 15)
    fun nextAlarmAt(context: Context) = get(context).getLong("next_alarm_at", 0L)

    /**
     * A stable random identifier for THIS install, generated once and never synced.
     * Used to determine which physical device is the designated "active" alarm device
     * when multiple devices share the same synced settings.
     */
    fun deviceId(context: Context): String {
        val existing = get(context).getString("device_id", null)
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString()
        get(context).edit().putString("device_id", fresh).apply()
        return fresh
    }

    /** Human-readable label for this device, e.g. "Xiaomi Mi Box S". Not stored, just derived. */
    fun deviceLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** Which device's deviceId is designated to actually fire the alarm. Null = unrestricted (any device may fire). */
    fun activeDeviceId(context: Context) = get(context).getString("active_device_id", null)
    fun activeDeviceLabel(context: Context) = get(context).getString("active_device_label", null)

    fun setActiveDevice(context: Context, id: String, label: String) {
        get(context).edit()
            .putString("active_device_id", id)
            .putString("active_device_label", label)
            .apply()
    }

    /** True if the given Calendar.DAY_OF_WEEK (SUNDAY=1..SATURDAY=7) is selected. */
    fun isDaySelected(context: Context, calendarDayOfWeek: Int): Boolean {
        val mask = alarmDaysMask(context)
        val bit = 1 shl (calendarDayOfWeek - Calendar.SUNDAY)
        return (mask and bit) != 0
    }

    // --- Status tracking (for persistent "done" indicators in the UI) ---
    fun pairedAt(context: Context) = get(context).getLong("paired_at", 0L)
    fun scheduledAt(context: Context) = get(context).getLong("scheduled_at", 0L)
    fun isScheduled(context: Context) = get(context).getBoolean("is_scheduled", false)
    fun lastRunAt(context: Context) = get(context).getLong("last_run_at", 0L)
    fun lastRunStatus(context: Context) = get(context).getString("last_run_status", null) // "success" | "failed" | "unreachable" | "skipped"

    fun save(
        context: Context,
        tvIp: String,
        tvMac: String,
        playlistUri: String,
        spotifyAppId: String,
        alarmHour: Int,
        alarmMinute: Int,
        alarmDaysMask: Int,
        wakeVolume: Int
    ) {
        get(context).edit()
            .putString("tv_ip", tvIp)
            .putString("tv_mac", tvMac)
            .putString("playlist_uri", playlistUri)
            .putString("spotify_app_id", spotifyAppId)
            .putInt("alarm_hour", alarmHour)
            .putInt("alarm_minute", alarmMinute)
            .putInt("alarm_days_mask", alarmDaysMask)
            .putInt("wake_volume", wakeVolume)
            .apply()
    }

    fun saveClientKey(context: Context, key: String) {
        get(context).edit()
            .putString("client_key", key)
            .putLong("paired_at", System.currentTimeMillis())
            .apply()
    }

    fun markScheduled(context: Context) {
        get(context).edit()
            .putBoolean("is_scheduled", true)
            .putLong("scheduled_at", System.currentTimeMillis())
            .apply()
    }

    fun saveNextAlarmAt(context: Context, millis: Long) {
        get(context).edit().putLong("next_alarm_at", millis).apply()
    }

    fun markRunResult(context: Context, status: String) {
        get(context).edit()
            .putLong("last_run_at", System.currentTimeMillis())
            .putString("last_run_status", status)
            .apply()
    }
}
