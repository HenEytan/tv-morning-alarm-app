package com.henos.tvalarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters

class TvAlarmWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        /** Input flag: set to true for "Run Now" so the run happens even if the scheduled alarm is disabled. */
        const val INPUT_MANUAL = "manual"
        private const val CHANNEL_ID = "tvalarm_run"
        private const val NOTIFICATION_ID = 4242
    }

    override fun doWork(): Result {
        DebugLog.section("ALARM RUN START")
        val ctx = applicationContext
        val manual = inputData.getBoolean(INPUT_MANUAL, false)

        if (!manual && !Prefs.isAlarmEnabled(ctx)) {
            DebugLog.log("TvAlarmWorker", "SKIP: alarm is disabled")
            return Result.success()
        }

        val ip = Prefs.tvIp(ctx)
        val mac = Prefs.tvMac(ctx)
        val playlist = Prefs.playlistUri(ctx)
        val appId = Prefs.spotifyAppId(ctx)
        val clientKey = Prefs.clientKey(ctx)

        DebugLog.log("TvAlarmWorker", "config: ip=$ip mac=$mac appId=$appId playlist=$playlist hasClientKey=${clientKey != null} manual=$manual")

        if (ip.isBlank() || playlist.isBlank() || clientKey == null) {
            DebugLog.log("TvAlarmWorker", "ABORT: missing required config")
            Prefs.markRunResult(ctx, "failed")
            return Result.failure()
        }

        if (mac.isNotBlank()) {
            WebOsClient.sendWol(mac)
        } else {
            DebugLog.log("TvAlarmWorker", "no MAC configured - skipping Wake-on-LAN (only works if the TV is already on)")
        }

        if (!WebOsClient.waitForTv(ip, timeoutMs = 90_000)) {
            DebugLog.log("TvAlarmWorker", "ABORT: TV never came online")
            Prefs.markRunResult(ctx, "unreachable")
            // Do NOT retry: a retry hours later would start music at a random time.
            return Result.failure()
        }

        DebugLog.log("TvAlarmWorker", "TV is reachable, waiting 8s for webOS to finish loading")
        Thread.sleep(8000)

        val wakeVolume = Prefs.wakeVolume(ctx)
        val volumeResult = WebOsClient.setVolume(ip, clientKey, wakeVolume)
        DebugLog.log("TvAlarmWorker", "setVolume($wakeVolume): $volumeResult")
        if (volumeResult == WebOsClient.RequestResult.UNPAIRED) {
            DebugLog.log("TvAlarmWorker", "ABORT: TV no longer trusts our pairing - re-pair needed")
            Prefs.markRunResult(ctx, "unpaired")
            return Result.failure()
        }

        val launchResult = WebOsClient.launchApp(ip, clientKey, appId, playlist)
        val status = when (launchResult) {
            WebOsClient.RequestResult.OK -> "success"
            WebOsClient.RequestResult.UNPAIRED -> "unpaired"
            WebOsClient.RequestResult.UNREACHABLE -> "unreachable"
            WebOsClient.RequestResult.ERROR -> "failed"
        }
        DebugLog.log("TvAlarmWorker", "RUN COMPLETE: $status ${WebOsClient.lastCommandError?.let { "($it)" } ?: ""}")
        Prefs.markRunResult(ctx, status)
        return if (status == "success") Result.success() else Result.failure()
    }

    /** Needed for expedited work on Android < 12, where it runs as a foreground service. */
    override fun getForegroundInfo(): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Alarm run", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("TV Morning Alarm")
            .setContentText("Waking the TV and starting Spotify\u2026")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
