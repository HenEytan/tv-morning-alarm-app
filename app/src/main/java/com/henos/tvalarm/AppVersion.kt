package com.henos.tvalarm

import android.content.Context

/**
 * The installed APK's own version string, e.g. "1.0 (build 118)" for the APK attached
 * to the build-118 release, or "1.0 (dev)" for one built locally.
 *
 * Read from the package manager rather than a compiled-in constant, so what the app
 * displays is by construction the version of the APK actually running.
 */
object AppVersion {

    fun name(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}
