package com.henos.tvalarm

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64

/**
 * Shares Playlist/Schedule/Volume/IP/MAC settings across every install of this app via a
 * small JSON file in the app's own GitHub repo. The SSAP pairing client-key is deliberately
 * NOT synced \u2014 it's tied to each device's own identity with the TV, so every device still
 * pairs once for itself via "Connect to TV".
 *
 * Requires a fine-grained GitHub token (Contents: Read and write, scoped to this one repo)
 * baked in at build time via BuildConfig.SETTINGS_SYNC_TOKEN. If that's blank (e.g. a local
 * build with no token configured), sync silently no-ops.
 */
object SettingsSync {
    private const val TAG = "SettingsSync"
    private const val OWNER = "HenEytan"
    private const val REPO = "tv-morning-alarm-app"
    private const val PATH = "shared/settings.json"
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/contents/$PATH"

    private val client = OkHttpClient()

    private fun token(): String? =
        BuildConfig.SETTINGS_SYNC_TOKEN.takeIf { it.isNotBlank() }

    /**
     * Blocking. Call from a background thread. If the shared file already has real settings,
     * applies them to local Prefs. If it's still the empty seed, this device's current local
     * settings become the shared baseline instead (first-sync bootstrap).
     */
    fun pullOrBootstrap(context: Context) {
        val tok = token() ?: run {
            DebugLog.log(TAG, "no sync token baked into this build - skipping sync")
            return
        }
        try {
            val req = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $tok")
                .addHeader("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    DebugLog.log(TAG, "pull: HTTP ${resp.code}")
                    return
                }
                val respBody = resp.body?.string() ?: return
                val body = JSONObject(respBody)
                val contentB64 = body.getString("content").replace("\n", "")
                val decoded = String(Base64.getDecoder().decode(contentB64))
                val json = JSONObject(decoded)
                if (!json.optBoolean("initialized", false)) {
                    DebugLog.log(TAG, "shared settings not yet initialized - pushing this device's settings as the baseline")
                    push(context, knownSha = body.getString("sha"))
                    return
                }
                Prefs.save(
                    context,
                    json.optString("tvIp", Prefs.tvIp(context)),
                    json.optString("tvMac", Prefs.tvMac(context)),
                    json.optString("playlistUri", Prefs.playlistUri(context)),
                    json.optString("spotifyAppId", Prefs.spotifyAppId(context)),
                    json.optInt("alarmHour", Prefs.alarmHour(context)),
                    json.optInt("alarmMinute", Prefs.alarmMinute(context)),
                    json.optInt("alarmDaysMask", Prefs.alarmDaysMask(context)),
                    json.optInt("wakeVolume", Prefs.wakeVolume(context))
                )
                if (Prefs.isScheduled(context)) {
                    AlarmScheduler.scheduleNext(context)
                    Prefs.markScheduled(context)
                }
                DebugLog.log(TAG, "pull: applied shared settings")
            }
        } catch (e: Exception) {
            DebugLog.log(TAG, "pull failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Blocking. Call from a background thread. Pushes this device's current settings to the shared file. */
    fun push(context: Context, knownSha: String? = null) {
        val tok = token() ?: return
        try {
            val fileSha = knownSha ?: currentSha(tok)
            val json = JSONObject().apply {
                put("initialized", true)
                put("tvIp", Prefs.tvIp(context))
                put("tvMac", Prefs.tvMac(context))
                put("playlistUri", Prefs.playlistUri(context))
                put("spotifyAppId", Prefs.spotifyAppId(context))
                put("alarmHour", Prefs.alarmHour(context))
                put("alarmMinute", Prefs.alarmMinute(context))
                put("alarmDaysMask", Prefs.alarmDaysMask(context))
                put("wakeVolume", Prefs.wakeVolume(context))
            }
            val contentB64 = Base64.getEncoder().encodeToString(json.toString(2).toByteArray())
            val payload = JSONObject().apply {
                put("message", "Sync settings from device")
                put("content", contentB64)
                put("branch", "main")
                if (fileSha != null) put("sha", fileSha)
            }
            val req = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $tok")
                .addHeader("Accept", "application/vnd.github+json")
                .put(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                DebugLog.log(TAG, "push: HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            DebugLog.log(TAG, "push failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun currentSha(tok: String): String? {
        return try {
            val req = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $tok")
                .addHeader("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string() ?: return null).getString("sha")
            }
        } catch (e: Exception) {
            null
        }
    }
}
