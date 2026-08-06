package com.henos.tvalarm

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class TvAlarmWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val ip = Prefs.tvIp(ctx)
        val mac = Prefs.tvMac(ctx)
        val playlist = Prefs.playlistUri(ctx)
        val appId = Prefs.spotifyAppId(ctx)
        val clientKey = Prefs.clientKey(ctx)

        if (ip.isBlank() || mac.isBlank() || playlist.isBlank() || clientKey == null) {
            Prefs.markRunResult(ctx, "failed")
            return Result.failure()
        }

        WebOsClient.sendWol(mac)

        if (!WebOsClient.waitForTv(ip, timeoutMs = 90_000)) {
            Prefs.markRunResult(ctx, "unreachable")
            return Result.retry()
        }

        Thread.sleep(8000) // let webOS finish booting to the home screen

        val ok = WebOsClient.launchApp(ip, clientKey, appId, playlist)
        Prefs.markRunResult(ctx, if (ok) "success" else "failed")
        return if (ok) Result.success() else Result.retry()
    }
}
