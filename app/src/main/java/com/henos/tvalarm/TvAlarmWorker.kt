package com.henos.tvalarm

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class TvAlarmWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        DebugLog.section("ALARM RUN START")
        val ctx = applicationContext
        val ip = Prefs.tvIp(ctx)
        val mac = Prefs.tvMac(ctx)
        val playlist = Prefs.playlistUri(ctx)
        val appId = Prefs.spotifyAppId(ctx)
        val clientKey = Prefs.clientKey(ctx)

        DebugLog.log("TvAlarmWorker", "config: ip=$ip mac=$mac appId=$appId playlist=$playlist hasClientKey=${clientKey != null}")

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
            DebugLog.log("TvAlarmWorker", "ABORT: TV never came online after WOL")
            Prefs.markRunResult(ctx, "unreachable")
            return Result.retry()
        }

        DebugLog.log("TvAlarmWorker", "TV is reachable, waiting 8s for webOS home screen to finish loading")
        Thread.sleep(8000)

        val wakeVolume = Prefs.wakeVolume(ctx)
        val volumeOk = WebOsClient.setVolume(ip, clientKey, wakeVolume)
        DebugLog.log("TvAlarmWorker", "setVolume($wakeVolume): ${if (volumeOk) "ok" else "failed, continuing anyway"}")

        val ok = WebOsClient.launchApp(ip, clientKey, appId, playlist)
        DebugLog.log("TvAlarmWorker", "RUN COMPLETE: ${if (ok) "success" else "failed"}")
        Prefs.markRunResult(ctx, if (ok) "success" else "failed")
        return if (ok) Result.success() else Result.retry()
    }
}
