package com.henos.tvalarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Updating the app in place, so a new build is never "uninstall, download,
 * install".
 *
 * The repository is PUBLIC, which is what makes this simple: the phone reads
 * `releases/latest` from GitHub directly. There is no server, no token, and
 * nothing to configure.
 *
 * THE VERSION COMPARISON is the CI run number, and that is not a coincidence:
 * `app/build.gradle` sets `versionCode` from `BUILD_NUMBER`, which the workflow
 * passes as `github.run_number`, and it publishes the APK under the tag
 * `build-<run_number>`. So the number in the tag IS the versionCode Android
 * will compare. A locally built APK has versionCode 1 and versionName
 * "1.0 (dev)", so it sees every published build as newer — which is correct.
 *
 * THE REFUSAL IS THE IMPORTANT PART. [installability] reads the DOWNLOADED
 * file's own package name and signing certificate before anything is
 * installed. Until the workflow is given release-signing secrets it publishes
 * `assembleDebug` output signed with a keystore the runner generates fresh on
 * every run — so two published builds do not share a signature, and Android
 * will refuse to replace one with the other. That case is reported as
 * "install fresh", not as an error to retry, because no number of retries
 * changes it.
 */
object Updater {

    /** Where the release feed lives. Public, so no credential is involved. */
    const val REPO = "HenEytan/tv-morning-alarm-app"

    /** The app asks at most this often on its own. */
    const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000
    private const val LAST_CHECK_KEY = "last_update_check"

    private const val DIR = "updates"
    private const val MAX_BYTES = 100L * 1024 * 1024

    /** A published build the phone could install. */
    data class Release(val versionCode: Long, val tag: String, val url: String, val sizeBytes: Long)

    /** What a check found. */
    sealed interface Check {
        data class Available(val release: Release) : Check
        data object UpToDate : Check
        data class Failed(val reason: String) : Check
    }

    /** Whether a downloaded file can replace the running app. */
    enum class Installability { OK, WRONG_PACKAGE, WRONG_SIGNER, DOWNGRADE }

    // --- what is running ---------------------------------------------------

    fun installedVersionCode(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: Exception) {
        0L
    }

    // --- the feed ----------------------------------------------------------

    /**
     * `build-118` → 118. Anything else answers null rather than a guess: a
     * wrong number either hides every update forever or offers a downgrade
     * Android refuses at the very end of a download.
     */
    fun runNumberOf(tag: String): Long? =
        Regex("^build-(\\d+)$").find(tag.trim())?.groupValues?.get(1)?.toLongOrNull()

    /** Parse GitHub's release JSON. Separated so it can be read without a network. */
    fun parseRelease(body: String): Release? = try {
        val root = JSONObject(body)
        val code = runNumberOf(root.optString("tag_name"))
        val assets = root.optJSONArray("assets")
        var apk: JSONObject? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { apk = a; break }
            }
        }
        val url = apk?.optString("browser_download_url").orEmpty()
        if (code == null || url.isEmpty()) null
        else Release(code, root.optString("tag_name"), url, apk?.optLong("size", 0L) ?: 0L)
    } catch (e: Exception) {
        null
    }

    /** Is a check due? A clock that went backwards answers true. */
    fun checkDue(lastMs: Long, nowMs: Long, everyMs: Long = CHECK_EVERY_MS): Boolean =
        lastMs <= 0L || nowMs < lastMs || nowMs - lastMs >= everyMs

    /**
     * Ask GitHub. Blocking — call it off the main thread. Never throws: an
     * update check that breaks the app is a far worse bug than one that
     * quietly fails.
     */
    fun check(context: Context, force: Boolean = false, nowMs: Long = System.currentTimeMillis()): Check {
        val prefs = Prefs.get(context)
        if (!force && !checkDue(prefs.getLong(LAST_CHECK_KEY, 0L), nowMs)) return Check.UpToDate
        prefs.edit().putLong(LAST_CHECK_KEY, nowMs).apply()
        val body = get("https://api.github.com/repos/$REPO/releases/latest") ?: return Check.Failed("offline")
        val release = parseRelease(body) ?: return Check.Failed("unreadable")
        // EQUAL is up to date, not available: Android accepts an install at the
        // same code, but offering one means asking to download the running build
        // every day forever.
        return if (release.versionCode > installedVersionCode(context)) Check.Available(release) else Check.UpToDate
    }

    private fun get(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "tv-morning-alarm")
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    // --- fetching ----------------------------------------------------------

    /**
     * Stream the APK into this app's cache. Blocking. Returns the file, or null.
     * Only https, and only into the app's own cache — never shared storage,
     * where another app could swap the file between the check and the install.
     */
    fun download(context: Context, release: Release, onProgress: (Long, Long) -> Unit = { _, _ -> }): File? {
        if (!release.url.lowercase(Locale.ROOT).startsWith("https://")) return null
        var conn: HttpURLConnection? = null
        return try {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val part = File(dir, "update.apk.part")

            conn = URL(release.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) return null
            val total = conn.contentLengthLong
            if (total > MAX_BYTES) return null

            var received = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        received += n
                        if (received > MAX_BYTES) return null
                        out.write(buf, 0, n)
                        onProgress(received, total)
                    }
                }
            }
            if (total > 0 && received != total) return null
            // Renamed only once the byte count agrees: nothing named update.apk
            // was ever a partial download.
            val apk = File(dir, "update.apk")
            if (!part.renameTo(apk)) return null
            apk
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    // --- whether it can be installed ---------------------------------------

    /**
     * Read the downloaded file as a package and compare it with the running
     * app. This is what stands between a user and
     * INSTALL_FAILED_UPDATE_INCOMPATIBLE at the end of a download.
     */
    fun installability(context: Context, apk: File): Installability {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val downloaded = context.packageManager.getPackageArchiveInfo(apk.path, flags)
            ?: return Installability.WRONG_PACKAGE
        if (downloaded.packageName != context.packageName) return Installability.WRONG_PACKAGE

        val mine = try {
            signerOf(context.packageManager.getPackageInfo(context.packageName, flags))
        } catch (e: Exception) {
            ""
        }
        val theirs = signerOf(downloaded)
        // An empty signer on either side means the platform would not tell us.
        // Treat that as a mismatch: proceeding on "we could not check" is how
        // the one failure this exists to prevent gets through.
        if (mine.isEmpty() || theirs.isEmpty() || mine != theirs) return Installability.WRONG_SIGNER

        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) downloaded.longVersionCode
        else @Suppress("DEPRECATION") downloaded.versionCode.toLong()
        if (code < installedVersionCode(context)) return Installability.DOWNGRADE
        return Installability.OK
    }

    @Suppress("DEPRECATION")
    private fun signerOf(info: PackageInfo): String = try {
        val sigs: Array<Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
                if (info.signingInfo!!.hasMultipleSigners()) info.signingInfo!!.apkContentsSigners
                else info.signingInfo!!.signingCertificateHistory
            } else {
                info.signatures
            }
        if (sigs.isNullOrEmpty()) "" else {
            // The CURRENT certificate: with a rotated key the history's last
            // entry is the one in force, and the one Android compares against.
            val sha = MessageDigest.getInstance("SHA-256")
            sha.digest(sigs.last().toByteArray()).joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        ""
    }

    // --- installing --------------------------------------------------------

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Hand the file to PackageInstaller. A session rather than ACTION_VIEW for
     * one reason: the session reports WHY an install failed, so a refused
     * update and a user who changed their mind do not look identical.
     *
     * Success is delivered to the process that REPLACES this one, so nothing
     * here observes it.
     */
    fun install(context: Context, apk: File): Boolean {
        val installer = context.packageManager.packageInstaller
        var session: PackageInstaller.Session? = null
        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            session = installer.openSession(id)
            session.openWrite("tvalarm", 0, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, id, intent, flags)
            session.commit(pi.intentSender)
            true
        } catch (e: Exception) {
            session?.abandon()
            false
        } finally {
            session?.close()
        }
    }
}
