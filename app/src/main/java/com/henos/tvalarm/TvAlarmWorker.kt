package com.henos.tvalarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters

class TvAlarmWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        /** Input flag: set to true for "Run Now" so the run happens even if the scheduled alarm is disabled. */
        const val INPUT_MANUAL = "manual"

        /**
         * Unique-work name. Both the scheduled alarm and the Run Now button enqueue under
         * this name so only one run is ever in flight. They used to call plain enqueue(),
         * and the debug log shows what that cost: runs starting at 18:30:01, 18:40:09 and
         * 18:52:57 all alive at once, their log lines interleaved, several of them talking
         * to the TV at the same time and trampling each other's shared error state.
         */
        const val UNIQUE_WORK_NAME = "tv_alarm_run"

        private const val CHANNEL_ID = "tvalarm_run"
        private const val NOTIFICATION_ID = 4242
        private const val WAKE_LOCK_TAG = "TvMorningAlarm:run"

        /** Generous upper bound on one run (90s wake wait + settle + two commands). */
        private const val WAKE_LOCK_TIMEOUT_MS = 5 * 60 * 1000L
    }

    override fun doWork(): Result {
        val ctx = applicationContext
        // This whole run happens while the phone sits asleep on a bedside table, and
        // every step of it is wall-clock sensitive. Without a wake lock the SoC
        // suspends part-way through: the debug log caught Thread.sleep(8000) taking
        // ten minutes, and a connect that reported "after 2000ms" while 30 seconds of
        // wall clock went by. The alarm fired on time and the music started six to ten
        // minutes late, or never. The Wi-Fi lock is the other half of the same problem:
        // a radio in power-save drops the association and Android silently reroutes the
        // LAN traffic over cellular, where it can only time out.
        val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        val wifiManager = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val wifiLockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        val wifiLock = wifiManager.createWifiLock(wifiLockMode, WAKE_LOCK_TAG)
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        wifiLock.acquire()
        try {
            return runAlarm(ctx)
        } finally {
            if (wifiLock.isHeld) wifiLock.release()
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun runAlarm(ctx: Context): Result {
        val manual = inputData.getBoolean(INPUT_MANUAL, false)
        DebugLog.section("ALARM RUN START (run ${id.toString().take(8)})")

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
            WebOsClient.sendWol(mac, ip)
        } else {
            DebugLog.log("TvAlarmWorker", "no MAC configured - skipping Wake-on-LAN (only works if the TV is already on)")
        }

        if (!WebOsClient.waitForTv(ip, timeoutMs = 90_000)) {
            DebugLog.log("TvAlarmWorker", "ABORT: TV never came online")
            Prefs.markRunResult(ctx, "unreachable")
            // Do NOT retry: a retry hours later would start music at a random time.
            return Result.failure()
        }

        if (isStopped) {
            DebugLog.log("TvAlarmWorker", "ABORT: superseded by a newer run")
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

        var launchResult = WebOsClient.launchApp(ip, clientKey, appId, playlist)
        if (launchResult == WebOsClient.RequestResult.UNREACHABLE && !isStopped) {
            // webOS opens the control port early in its boot and can drop it again
            // before it has finished coming up, so a TV that answered a moment ago
            // may refuse the launch. Give it one more chance rather than reporting a
            // failed alarm the user only discovers by oversleeping.
            DebugLog.log("TvAlarmWorker", "launch was UNREACHABLE - waiting for the TV to settle, then retrying once")
            if (WebOsClient.waitForTv(ip, timeoutMs = 30_000)) {
                Thread.sleep(5000)
                launchResult = WebOsClient.launchApp(ip, clientKey, appId, playlist)
            }
        }
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
