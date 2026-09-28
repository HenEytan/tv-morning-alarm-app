package com.henos.tvalarm

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent in-app debug log. Every network step (endpoint tried, raw
 * response from the TV, exact exception) gets recorded here so failures
 * can be diagnosed from a single "Copy debug log" without back-and-forth
 * screenshots. Survives app restarts (an append-only file in the app's
 * private storage) so a failed overnight automatic run can still be
 * inspected the next morning.
 *
 * TWO RULES, both because the log LEAVES the device (Copy, Share):
 *
 *   · The TV pairing key never enters it. Every line goes through [redact],
 *     which blanks the value of `client-key` wherever it appears — the TV's
 *     `registered` frame carries it, and so does every request this app
 *     sends. That key lets anyone on the LAN drive the television.
 *   · It is a file, appended to, not a SharedPreferences string rewritten
 *     on every line. The old form re-read and re-wrote up to 60 KB per line,
 *     on the main thread when called from the UI.
 */
object DebugLog {
    private const val FILE = "debug.log"
    private const val LEGACY_PREF = "tvalarm_debug_log"

    /** Rotation: when the file passes [MAX_BYTES] it is cut back to its last [KEEP_BYTES]. */
    private const val MAX_BYTES = 128L * 1024
    private const val KEEP_BYTES = 60 * 1024

    /** What stands in for a pairing key in the log. */
    const val REDACTED = "<redacted>"

    @Volatile
    var appContext: Context? = null

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // `"client-key":"…"` in a JSON frame, or `client-key=…` / `client-key: …` in
    // free text. Case-insensitive, and tolerant of spaces around the separator.
    private val keyPattern = Regex(
        """("client[-_]key"\s*:\s*")[^"]*(")|(client[-_]key\s*[:=]\s*)[^\s,}"']+""",
        RegexOption.IGNORE_CASE,
    )

    /** [text] with every pairing-key value replaced by [REDACTED]. Pure; tested. */
    fun redact(text: String): String = keyPattern.replace(text) { m ->
        if (m.groupValues[1].isNotEmpty()) "${m.groupValues[1]}$REDACTED${m.groupValues[2]}"
        else "${m.groupValues[3]}$REDACTED"
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val ctx = appContext ?: return
        val line = "[${timeFmt.format(Date())}] $tag: ${redact(message)}\n"
        try {
            val f = file(ctx)
            f.appendText(line)
            if (f.length() > MAX_BYTES) rotate(f)
        } catch (e: Exception) {
            // A log that cannot be written must never take the alarm down.
        }
    }

    /** Marks the start of a new operation with a clear divider, so log sections are easy to tell apart. */
    fun section(title: String) {
        log("====", title)
    }

    /**
     * The log, with the app version stamped on the front. A log gets copied out of the
     * app and read somewhere else, where the first question is always which build
     * produced it - so the answer travels with it instead of having to be asked for.
     */
    @Synchronized
    fun getLog(): String {
        val ctx = appContext ?: return "(log unavailable)"
        val body = try { file(ctx).takeIf { it.exists() }?.readText() } catch (e: Exception) { null }
        if (body.isNullOrBlank()) return "(empty — nothing has run yet)"
        return "TV Morning Alarm ${AppVersion.name(ctx)}\n\n" + body
    }

    @Synchronized
    fun clear() {
        val ctx = appContext ?: return
        try { file(ctx).delete() } catch (e: Exception) { }
        // The pre-file log lived in SharedPreferences, unredacted. Clearing
        // removes that copy too, so an old install stops carrying the key.
        ctx.getSharedPreferences(LEGACY_PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** Keep the tail. Written to a sibling and renamed, so a crash mid-rotation loses nothing. */
    private fun rotate(f: File) {
        val text = f.readText()
        val cut = text.length - KEEP_BYTES
        val tail = if (cut > 0) "...(trimmed)...\n" + text.substring(text.indexOf('\n', cut).let { if (it < 0) cut else it + 1 }) else text
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(tail)
        if (!tmp.renameTo(f)) { f.writeText(tail); tmp.delete() }
    }
}
