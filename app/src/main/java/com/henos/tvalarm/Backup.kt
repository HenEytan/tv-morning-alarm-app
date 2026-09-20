package com.henos.tvalarm

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * The alarm's settings, saved and put back.
 *
 * This app holds very little — a TV address, a MAC, a playlist, a time, which
 * days, a volume — but all of it was typed by hand and none of it was anywhere
 * but this phone. Reinstalling, or moving to a new phone, meant finding the
 * TV's IP again and re-entering the lot.
 *
 * TWO COPIES, and they answer different things:
 *
 *   · [autoBackupIfDue] keeps a copy in the app's own files, one a day, three
 *     kept. It answers "I changed the wrong setting" and it goes away with the
 *     app, because Android deletes an app's files with the app.
 *   · [encode] written to a file the user picks is the copy that survives the
 *     phone. That one is the point of the feature.
 *
 * WHAT IS NOT IN EITHER:
 *
 *   · `client_key` — the pairing token the TV issued to THIS install. It is a
 *     credential, and a backup file travels; carrying it would mean a file in
 *     a chat or a Drive folder is a key to someone's television. Re-pairing is
 *     one prompt on the TV, and the restore screen says to expect it.
 *   · the status fields (`last_run_at`, `is_scheduled`, `next_alarm_at`, …).
 *     They describe what THIS install has done, not what the user chose.
 *     Restoring `is_scheduled = true` onto a phone with no alarm scheduled
 *     would show a green "scheduled" line that is simply false — which is
 *     worse than showing nothing.
 */
object Backup {

    const val VERSION = 1
    const val APP = "tv-morning-alarm"

    /** Where the daily copies live, and how many. */
    private const val DIR = "auto_backups"
    const val KEEP = 3
    const val EVERY_MS = 24L * 60 * 60 * 1000

    private const val LAST_KEY = "last_auto_backup"

    /** Exactly what a backup carries. Anything not here is deliberately left behind. */
    private val STRINGS = listOf("tv_ip", "tv_mac", "playlist_uri", "spotify_app_id")
    private val INTS = listOf("alarm_hour", "alarm_minute", "alarm_days_mask", "wake_volume")
    private val BOOLS = listOf("alarm_enabled")

    /** Never backed up, whatever else is added. See the class note. */
    val EXCLUDED = setOf(
        "client_key",
        "paired_at", "scheduled_at", "is_scheduled",
        "last_run_at", "last_run_status", "next_alarm_at",
    )

    // --- the file ----------------------------------------------------------

    /** The settings as one JSON document. */
    fun encode(context: Context): String {
        val p = Prefs.get(context)
        val settings = JSONObject()
        for (k in STRINGS) settings.put(k, p.getString(k, "") ?: "")
        for (k in INTS) settings.put(k, p.getInt(k, defaultInt(k)))
        for (k in BOOLS) settings.put(k, p.getBoolean(k, true))
        return JSONObject()
            .put("app", APP)
            .put("v", VERSION)
            .put("at", System.currentTimeMillis())
            .put("settings", settings)
            .toString(2)
    }

    /** Why a file could not be used. One sentence each on screen. */
    enum class Problem { NOT_A_BACKUP, WRONG_APP, TOO_NEW, UNREADABLE }

    /** What came out of a file, or why it could not be read. */
    sealed interface Read {
        data class Ok(val settings: JSONObject, val at: Long) : Read
        data class Bad(val problem: Problem) : Read
    }

    fun decode(text: String): Read {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return Read.Bad(Problem.NOT_A_BACKUP)
        }
        if (root.optString("app") != APP) {
            return Read.Bad(if (root.has("app")) Problem.WRONG_APP else Problem.NOT_A_BACKUP)
        }
        val v = root.optInt("v", 0)
        if (v <= 0) return Read.Bad(Problem.UNREADABLE)
        if (v > VERSION) return Read.Bad(Problem.TOO_NEW)
        val settings = root.optJSONObject("settings") ?: return Read.Bad(Problem.UNREADABLE)
        return Read.Ok(settings, root.optLong("at", 0L))
    }

    /**
     * Write the settings a read produced.
     *
     * Only the keys this code knows about are written, and never an excluded
     * one — a file could have been written by anything, and "it said so" is not
     * a reason to put a pairing key or a fake "scheduled" flag onto a phone.
     */
    fun apply(context: Context, settings: JSONObject) {
        val e = Prefs.get(context).edit()
        for (k in STRINGS) if (settings.has(k) && k !in EXCLUDED) e.putString(k, settings.optString(k, ""))
        for (k in INTS) if (settings.has(k) && k !in EXCLUDED) e.putInt(k, settings.optInt(k, defaultInt(k)))
        for (k in BOOLS) if (settings.has(k) && k !in EXCLUDED) e.putBoolean(k, settings.optBoolean(k, true))
        e.apply()
    }

    private fun defaultInt(key: String) = when (key) {
        "alarm_hour" -> 7
        "alarm_minute" -> 0
        "alarm_days_mask" -> Prefs.ALL_DAYS_MASK
        "wake_volume" -> 15
        else -> 0
    }

    /** Save to a URI the system document picker returned. */
    fun writeTo(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openOutputStream(uri, "wt").use { out ->
            requireNotNull(out).write(encode(context).toByteArray())
            true
        }
    } catch (e: Exception) {
        false
    }

    /** Read a URI the picker returned. Bounded — a settings file is under a kilobyte. */
    fun readFrom(context: Context, uri: Uri): Read = try {
        val text = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input).readBytes().take(256 * 1024).toByteArray().toString(Charsets.UTF_8)
        }
        decode(text)
    } catch (e: Exception) {
        Read.Bad(Problem.UNREADABLE)
    }

    /** `alarm-settings-<date>.json` — a day, and nothing about the TV. */
    fun suggestedName(atMs: Long = System.currentTimeMillis()): String {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(atMs))
        return "alarm-settings-$d.json"
    }

    // --- the daily copy ----------------------------------------------------

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    fun nameFor(atMs: Long) = "auto-$atMs.json"

    /** The instant [nameFor] encoded, or null for a file this did not write. */
    fun instantOf(name: String): Long? =
        if (name.startsWith("auto-") && name.endsWith(".json")) {
            name.substring(5, name.length - 5).toLongOrNull()
        } else {
            null
        }

    /**
     * Is a copy due? A clock that moved BACKWARDS answers true — a phone whose
     * date was wrong and was then corrected must not be refused a copy until
     * real time catches up.
     */
    fun due(lastMs: Long, nowMs: Long, everyMs: Long = EVERY_MS): Boolean =
        lastMs <= 0L || nowMs < lastMs || nowMs - lastMs >= everyMs

    /** Which names to delete once [KEEP] are kept, newest first. */
    fun evict(names: List<String>, keep: Int = KEEP): List<String> =
        names.filter { instantOf(it) != null }
            .sortedByDescending { instantOf(it)!! }
            .drop(keep)

    /** The copies on disk, newest first. */
    fun copies(context: Context): List<File> =
        dir(context).listFiles().orEmpty()
            .filter { instantOf(it.name) != null }
            .sortedByDescending { instantOf(it.name)!! }

    /**
     * Take today's copy if one is due. Called once from the main screen.
     * Never throws: a backup that failed must not colour a launch.
     */
    fun autoBackupIfDue(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = Prefs.get(context)
        if (!due(prefs.getLong(LAST_KEY, 0L), nowMs)) return false
        return takeNow(context, nowMs)
    }

    /** Take one regardless of the schedule — the guard before a restore. */
    fun takeNow(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean = try {
        val d = dir(context)
        // Written then renamed: nothing named auto-*.json was ever a partial write.
        val tmp = File(d, "${nameFor(nowMs)}.part")
        tmp.writeText(encode(context))
        val target = File(d, nameFor(nowMs))
        if (!tmp.renameTo(target)) { target.writeText(encode(context)); tmp.delete() }
        Prefs.get(context).edit().putLong(LAST_KEY, nowMs).apply()
        // Evict AFTER the new one lands: deleting first and then failing to
        // write would have spent a copy for nothing.
        for (old in evict(d.list().orEmpty().toList())) File(d, old).delete()
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Put the newest daily copy back, after taking one of what is here now — so
     * restoring the wrong thing is itself reversible. Returns false when there
     * is nothing to restore, or when that guard copy could not be written.
     */
    fun restoreLatest(context: Context): Boolean {
        val newest = copies(context).firstOrNull() ?: return false
        if (!takeNow(context)) return false
        return when (val read = decode(newest.readText())) {
            is Read.Ok -> { apply(context, read.settings); true }
            is Read.Bad -> false
        }
    }
}
