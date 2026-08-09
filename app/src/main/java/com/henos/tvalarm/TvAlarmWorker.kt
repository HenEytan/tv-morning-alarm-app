package com.henos.tvalarm

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TvAlarmWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        /** How long a backup device waits after the scheduled time before checking whether
         *  the active device already handled today's alarm. Long enough to cover the active
         *  device's own worst case (a 90s wait-for-TV timeout plus setup), short enough to
         *  leave room for the backup's own run within WorkManager's execution budget. */
        private const val BACKUP_GRACE_PERIOD_MS = 180_000L
    }

    override fun doWork(): Result {
        DebugLog.section("ALARM RUN START")
        val ctx = applicationContext
        val ip = Prefs.tvIp(ctx)
        val mac = Prefs.tvMac(ctx)
        val playlist = Prefs.playlistUri(ctx)
        val appId = Prefs.spotifyAppId(ctx)
        val clientKey = Prefs.clientKey(ctx)
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        DebugLog.log("TvAlarmWorker", "config: ip=$ip mac=$mac appId=$appId playlist=$playlist hasClientKey=${clientKey != null}")

        if (ip.isBlank() || playlist.isBlank() || clientKey == null) {
            DebugLog.log("TvAlarmWorker", "ABORT: missing required config")
            Prefs.markRunResult(ctx, "failed")
            return Result.failure()
        }

        val myDeviceId = Prefs.deviceId(ctx)
        val activeDeviceId = Prefs.activeDeviceId(ctx)
        val isActive = activeDeviceId == null || activeDeviceId == myDeviceId

        if (isActive) {
            val status = performWakeSequence(ctx, ip, mac, playlist, appId, clientKey)
            Prefs.markRunResult(ctx, status)
            SettingsSync.markAttempt(ctx, todayStr, status)
            return if (status == "success") Result.success() else Result.retry()
        }

        // Backup device: give the active device time to complete its own run and report in,
        // then only step in if there's no record of it having tried at all today.
        DebugLog.log("TvAlarmWorker", "backup device - waiting ${BACKUP_GRACE_PERIOD_MS / 1000}s to see if active device ($activeDeviceId) already handled today's alarm")
        Thread.sleep(BACKUP_GRACE_PERIOD_MS)

        if (SettingsSync.hasAttemptToday(todayStr)) {
            DebugLog.log("TvAlarmWorker", "SKIP: active device already attempted today - standing down")
            Prefs.markRunResult(ctx, "skipped")
            return Result.success()
        }

        DebugLog.log("TvAlarmWorker", "active device did not attempt today (looks offline) - this backup device is taking over")
        val status = performWakeSequence(ctx, ip, mac, playlist, appId, clientKey)
        val fallbackStatus = "fallback_$status"
        Prefs.markRunResult(ctx, fallbackStatus)
        SettingsSync.markAttempt(ctx, todayStr, fallbackStatus)
        return if (status == "success") Result.success() else Result.retry()
    }

    /** Wake-on-LAN, wait for the TV, set volume, launch Spotify. Returns "success" | "failed" | "unreachable". */
    private fun performWakeSequence(ctx: Context, ip: String, mac: String, playlist: String, appId: String, clientKey: String): String {
        if (mac.isNotBlank()) {
            WebOsClient.sendWol(mac)
        } else {
            DebugLog.log("TvAlarmWorker", "no MAC configured - skipping Wake-on-LAN (only works if the TV is already on)")
        }

        if (!WebOsClient.waitForTv(ip, timeoutMs = 90_000)) {
            DebugLog.log("TvAlarmWorker", "ABORT: TV never came online after WOL")
            return "unreachable"
        }

        DebugLog.log("TvAlarmWorker", "TV is reachable, waiting 8s for webOS home screen to finish loading")
        Thread.sleep(8000)

        val wakeVolume = Prefs.wakeVolume(ctx)
        val volumeOk = WebOsClient.setVolume(ip, clientKey, wakeVolume)
        DebugLog.log("TvAlarmWorker", "setVolume($wakeVolume): ${if (volumeOk) "ok" else "failed, continuing anyway"}")

        val ok = WebOsClient.launchApp(ip, clientKey, appId, playlist)
        DebugLog.log("TvAlarmWorker", "RUN COMPLETE: ${if (ok) "success" else "failed"}")
        return if (ok) "success" else "failed"
    }
}
