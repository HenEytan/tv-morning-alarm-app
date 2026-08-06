package com.henos.tvalarm

import android.content.Context

object Prefs {
    private const val NAME = "tvalarm_prefs"

    fun get(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun tvIp(context: Context) = get(context).getString("tv_ip", "") ?: ""
    fun tvMac(context: Context) = get(context).getString("tv_mac", "") ?: ""
    fun playlistUri(context: Context) = get(context).getString("playlist_uri", "") ?: ""
    fun spotifyAppId(context: Context) = get(context).getString("spotify_app_id", "spotify-beehive") ?: "spotify-beehive"
    fun clientKey(context: Context) = get(context).getString("client_key", null)
    fun alarmHour(context: Context) = get(context).getInt("alarm_hour", 7)
    fun alarmMinute(context: Context) = get(context).getInt("alarm_minute", 0)

    fun save(
        context: Context,
        tvIp: String,
        tvMac: String,
        playlistUri: String,
        spotifyAppId: String,
        alarmHour: Int,
        alarmMinute: Int
    ) {
        get(context).edit()
            .putString("tv_ip", tvIp)
            .putString("tv_mac", tvMac)
            .putString("playlist_uri", playlistUri)
            .putString("spotify_app_id", spotifyAppId)
            .putInt("alarm_hour", alarmHour)
            .putInt("alarm_minute", alarmMinute)
            .apply()
    }

    fun saveClientKey(context: Context, key: String) {
        get(context).edit().putString("client_key", key).apply()
    }
}
