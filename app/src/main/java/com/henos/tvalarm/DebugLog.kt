package com.henos.tvalarm

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent in-app debug log. Every network step (endpoint tried, raw
 * response from the TV, exact exception) gets recorded here so failures
 * can be diagnosed from a single "Copy debug log" without back-and-forth
 * screenshots. Survives app restarts (stored in SharedPreferences) so a
 * failed overnight automatic run can still be inspected the next morning.
 */
object DebugLog {
    private const val PREF = "tvalarm_debug_log"
    private const val KEY = "log"
    private const val MAX_CHARS = 60_000

    @Volatile
    var appContext: Context? = null

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    @Synchronized
    fun log(tag: String, message: String) {
        val ctx = appContext ?: return
        val line = "[${timeFmt.format(Date())}] $tag: $message\n"
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        var updated = (prefs.getString(KEY, "") ?: "") + line
        if (updated.length > MAX_CHARS) {
            updated = "...(trimmed)...\n" + updated.takeLast(MAX_CHARS)
        }
        prefs.edit().putString(KEY, updated).apply()
    }

    /** Marks the start of a new operation with a clear divider, so log sections are easy to tell apart. */
    fun section(title: String) {
        log("====", title)
    }

    fun getLog(): String {
        val ctx = appContext ?: return "(log unavailable)"
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "") ?: "(empty \u2014 nothing has run yet)"
    }

    fun clear() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
