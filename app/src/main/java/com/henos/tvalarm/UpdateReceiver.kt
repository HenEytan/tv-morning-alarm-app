package com.henos.tvalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Where PackageInstaller answers.
 *
 * STATUS_PENDING_USER_ACTION is the one that matters day to day: Android wants
 * its own confirmation screen, and this launches it. It is also what arrives
 * when "install unknown apps" has not been granted for this app, so the install
 * resumes by itself when the user comes back with it granted.
 *
 * STATUS_SUCCESS is delivered to the NEW process, after the old one is gone —
 * nothing in the running app can observe its own replacement, so the app finds
 * out at the next launch instead.
 *
 * FAILURE_CONFLICT on an upgrade is almost always a different signing key,
 * which is the one case that genuinely needs an uninstall first. It is
 * recorded so the screen can say that instead of offering a retry that will
 * fail identically forever.
 */
class UpdateReceiver : BroadcastReceiver() {

    companion object {
        /** The action on the PendingIntent an install session reports to. */
        const val ACTION = "com.henos.tvalarm.UPDATE_INSTALL_STATUS"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    // Starting an activity from a receiver is blocked for a
                    // background app from Android 14. The user's own install
                    // exempts this one, but a refused launch must not take the
                    // process down.
                    DebugLog.log("UPDATE", "could not show the install confirmation (${e.message})")
                }
            }
            return
        }

        val note = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "installed"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "dismissed by the user"
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "refused — the published build is signed with a different key, so it cannot replace " +
                    "this install. Uninstall first, then install the new APK. ($message)"
            else -> "failed ($status${if (message != null) " — $message" else ""})"
        }
        DebugLog.log("UPDATE", note)
    }
}
