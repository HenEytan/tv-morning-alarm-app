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
 *     kept (plus the guard copies a restore takes, see [Kind]). It answers "I
 *     changed the wrong setting" and it goes away with the app, because
 *     Android deletes an app's files with the app.
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

    /** Where the on-device copies live, and how many daily ones are kept. */
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
        for (k in INTS) if (settings.has(k) && k !in EXCLUDED) e.putInt(k, sanitizeInt(k, settings.optInt(k, defaultInt(k))))
        for (k in BOOLS) if (settings.has(k) && k !in EXCLUDED) e.putBoolean(k, settings.optBoolean(k, true))
        e.apply()
    }

    /**
     * A restored number, made safe to store. A file could have been written by
     * anything: an hour of 25 would roll the lenient Calendar into the next
     * day, a days mask of 0 fires every day while the screen says "no days
     * selected", and a volume of 500 is a volume of 500. Pure; tested.
     */
    fun sanitizeInt(key: String, value: Int): Int = when (key) {
        "alarm_hour" -> value.coerceIn(0, 23)
        "alarm_minute" -> value.coerceIn(0, 59)
        "alarm_days_mask" -> (value and Prefs.ALL_DAYS_MASK).let { if (it == 0) Prefs.ALL_DAYS_MASK else it }
        "wake_volume" -> value.coerceIn(0, 100)
        else -> value
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
            readBounded(requireNotNull(input), MAX_FILE_BYTES).toString(Charsets.UTF_8)
        }
        decode(text)
    } catch (e: Exception) {
        Read.Bad(Problem.UNREADABLE)
    }

    /** A settings file is under a kilobyte; anything past this is not one. */
    private const val MAX_FILE_BYTES = 256 * 1024

    /**
     * At most [limit] bytes of [input]. The old read pulled the WHOLE stream into
     * memory and then kept the first 256 KB, so picking a large file by mistake
     * (a video) could run the app out of memory before the limit applied.
     */
    fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** `alarm-settings-<date>.json` — a day, and nothing about the TV. */
    fun suggestedName(atMs: Long = System.currentTimeMillis()): String {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(atMs))
        return "alarm-settings-$d.json"
    }

    // --- the on-device copies ----------------------------------------------

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    /**
     * Two kinds of copy share the folder, and are kept apart by name:
     *
     *   · `auto-<ms>.json`, the daily copy — [KEEP] kept;
     *   · `guard-<ms>.json`, taken right before a restore — [KEEP_GUARDS] kept.
     *
     * They used to be one kind. Then the guard taken by a restore also counted as
     * "today's copy" — the next daily copy moved a day later — and it evicted the
     * oldest daily copy, the one from before the mistake being undone.
     */
    enum class Kind(val prefix: String) { DAILY("auto-"), GUARD("guard-") }

    const val KEEP_GUARDS = 2

    fun nameFor(atMs: Long, kind: Kind = Kind.DAILY) = "${kind.prefix}$atMs.json"

    /** Which kind [name] is, or null for a file this code did not write. */
    fun kindOf(name: String): Kind? =
        Kind.values().firstOrNull { name.startsWith(it.prefix) && name.endsWith(".json") }
            ?.takeIf { instantOf(name) != null }

    /** The instant [nameFor] encoded, or null for a file this did not write. */
    fun instantOf(name: String): Long? {
        val kind = Kind.values().firstOrNull { name.startsWith(it.prefix) } ?: return null
        if (!name.endsWith(".json")) return null
        return name.substring(kind.prefix.length, name.length - 5).toLongOrNull()
    }

    /**
     * Is a copy due? A clock that moved BACKWARDS answers true — a phone whose
     * date was wrong and was then corrected must not be refused a copy until
     * real time catches up.
     */
    fun due(lastMs: Long, nowMs: Long, everyMs: Long = EVERY_MS): Boolean =
        lastMs <= 0L || nowMs < lastMs || nowMs - lastMs >= everyMs

    /**
     * Which names to delete, newest first within each kind: [keep] daily copies
     * and [keepGuards] guard copies stay. A guard never evicts a daily copy.
     */
    fun evict(names: List<String>, keep: Int = KEEP, keepGuards: Int = KEEP_GUARDS): List<String> =
        Kind.values().flatMap { kind ->
            names.filter { kindOf(it) == kind }
                .sortedByDescending { instantOf(it)!! }
                .drop(if (kind == Kind.GUARD) keepGuards else keep)
        }

    /** The copies on disk, both kinds, newest first. */
    fun copies(context: Context): List<File> =
        dir(context).listFiles().orEmpty()
            .filter { kindOf(it.name) != null }
            .sortedByDescending { instantOf(it.name)!! }

    /**
     * Take today's copy if one is due. Called once from the main screen.
     * Never throws: a backup that failed must not colour a launch.
     */
    fun autoBackupIfDue(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = Prefs.get(context)
        if (!due(prefs.getLong(LAST_KEY, 0L), nowMs)) return false
        return takeNow(context, nowMs, Kind.DAILY)
    }

    /**
     * Take one regardless of the schedule. Only a DAILY copy moves the daily
     * schedule: a guard is extra, and must not postpone tomorrow's copy.
     */
    @Synchronized
    fun takeNow(context: Context, nowMs: Long = System.currentTimeMillis(), kind: Kind = Kind.GUARD): Boolean = try {
        val d = dir(context)
        val text = encode(context)
        // Written then renamed: nothing named *.json here was ever a partial write.
        val target = File(d, nameFor(nowMs, kind))
        val tmp = File(d, "${target.name}.part")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.writeText(text); tmp.delete() }
        if (kind == Kind.DAILY) Prefs.get(context).edit().putLong(LAST_KEY, nowMs).apply()
        // Evict AFTER the new one lands: deleting first and then failing to
        // write would have spent a copy for nothing.
        for (old in evict(d.list().orEmpty().toList())) File(d, old).delete()
        true
    } catch (e: Exception) {
        false
    }

    /** The settings a copy holds, or null if it cannot be read. */
    fun settingsIn(file: File): JSONObject? = try {
        (decode(file.readText()) as? Read.Ok)?.settings
    } catch (e: Exception) {
        null
    }

    /** The settings as they are on this device now, in the form a copy holds them. */
    fun currentSettings(context: Context): JSONObject =
        JSONObject(encode(context)).getJSONObject("settings")

    /**
     * Would restoring [a] over [b] change anything? Compared field by field, over
     * exactly what a backup carries, after the same clamping [apply] does — so a
     * copy that differs only in a value [apply] would correct reads as the same.
     * Pure; tested.
     */
    fun sameSettings(a: JSONObject, b: JSONObject): Boolean =
        STRINGS.all { a.optString(it, "") == b.optString(it, "") } &&
            INTS.all { sanitizeInt(it, a.optInt(it, defaultInt(it))) == sanitizeInt(it, b.optInt(it, defaultInt(it))) } &&
            BOOLS.all { a.optBoolean(it, true) == b.optBoolean(it, true) }

    /**
     * The copy worth offering: the newest one whose settings DIFFER from what is
     * here now. The newest copy is usually the one taken at this launch — the
     * current settings — and restoring it changes nothing, which is what the
     * button used to do for most of the day. After a restore the guard copy is
     * the newest that differs, so a second tap undoes the first.
     */
    fun restoreCandidate(context: Context): File? {
        val now = currentSettings(context)
        return copies(context).firstOrNull { f -> settingsIn(f)?.let { !sameSettings(it, now) } ?: false }
    }

    /**
     * Put [file] back, after taking a guard copy of what is here now — so
     * restoring the wrong thing is itself reversible. Returns false, and changes
     * nothing, when the copy cannot be read or the guard could not be written.
     */
    fun restore(context: Context, file: File): Boolean {
        val settings = settingsIn(file) ?: return false
        if (!takeNow(context, kind = Kind.GUARD)) return false
        apply(context, settings)
        return true
    }
}
