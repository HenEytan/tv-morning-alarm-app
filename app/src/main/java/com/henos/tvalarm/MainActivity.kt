package com.henos.tvalarm

import android.annotation.SuppressLint
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.core.content.ContextCompat
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.chip.Chip
import com.henos.tvalarm.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val timeFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    /** Maps each day chip to its java.util.Calendar.DAY_OF_WEEK constant. */
    private val dayChips: List<Pair<Chip, Int>> by lazy {
        listOf(
            binding.chipSun to Calendar.SUNDAY,
            binding.chipMon to Calendar.MONDAY,
            binding.chipTue to Calendar.TUESDAY,
            binding.chipWed to Calendar.WEDNESDAY,
            binding.chipThu to Calendar.THURSDAY,
            binding.chipFri to Calendar.FRIDAY,
            binding.chipSat to Calendar.SATURDAY,
        )
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setupUi()
        } catch (t: Throwable) {
            showCrashScreen(t)
        }
    }

    /** Renders a scrollable, copyable screen showing exactly what crashed, instead of force-closing. */
    private fun showCrashScreen(t: Throwable) {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        val trace = sw.toString()
        try {
            DebugLog.section("STARTUP CRASH")
            DebugLog.log("MainActivity", trace)
        } catch (ignored: Throwable) {
            // Even logging must not be allowed to crash the fallback screen.
        }
        val textView = TextView(this).apply {
            text = "The app failed to start. Copy this and send it back:\n\n$trace"
            textSize = 12f
            setPadding(32, 48, 32, 48)
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }
        val copyButton = android.widget.Button(this).apply {
            text = "Copy error to clipboard"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TV Alarm crash", trace))
                Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(copyButton)
            addView(textView)
        }
        setContentView(android.widget.ScrollView(this).apply { addView(container) })
    }

    private enum class StatusKind { SUCCESS, ERROR, NEUTRAL }

    private fun setStatus(view: TextView, text: String, kind: StatusKind) {
        view.text = text
        val colorRes = when (kind) {
            StatusKind.SUCCESS -> R.color.status_success
            StatusKind.ERROR -> R.color.status_error
            StatusKind.NEUTRAL -> R.color.status_neutral
        }
        view.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun setupUi() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.inputTvIp.setText(Prefs.tvIp(this))
        binding.inputTvMac.setText(Prefs.tvMac(this))
        binding.inputPlaylistUri.setText(Prefs.playlistUri(this))
        binding.inputSpotifyAppId.setText(Prefs.spotifyAppId(this))
        binding.timePicker.setIs24HourView(true)
        binding.timePicker.hour = Prefs.alarmHour(this)
        binding.timePicker.minute = Prefs.alarmMinute(this)

        val savedMask = Prefs.alarmDaysMask(this)
        dayChips.forEach { (chip, dayOfWeek) ->
            chip.isChecked = (savedMask and (1 shl (dayOfWeek - Calendar.SUNDAY))) != 0
        }

        binding.btnScan.setOnClickListener { doScan() }
        binding.btnPair.setOnClickListener { doPair() }
        binding.btnSave.setOnClickListener { doSaveAndSchedule() }
        binding.btnRunNow.setOnClickListener { doRunNow() }
        binding.btnViewLog.setOnClickListener { showDebugLog() }
        binding.btnViewLog.setOnLongClickListener {
            DebugLog.clear()
            toast("Debug log cleared")
            true
        }

        binding.toggleMacAdvanced.setOnClickListener {
            val showing = binding.macAdvancedSection.visibility == View.VISIBLE
            binding.macAdvancedSection.visibility = if (showing) View.GONE else View.VISIBLE
            binding.toggleMacAdvanced.text = if (showing)
                "Advanced: set MAC for Wake-on-LAN (optional)" else "Hide advanced"
        }

        binding.togglePlaylistAdvanced.setOnClickListener {
            val showing = binding.playlistAdvancedSection.visibility == View.VISIBLE
            binding.playlistAdvancedSection.visibility = if (showing) View.GONE else View.VISIBLE
            binding.togglePlaylistAdvanced.text = if (showing)
                "Advanced: Spotify app id (optional)" else "Hide advanced"
        }

        binding.inputPlaylistUri.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshPlaylistStatus() }
        })

        refreshAllStatuses()
    }

    private fun refreshPlaylistStatus() {
        val playlist = binding.inputPlaylistUri.text.toString().trim()
        if (playlist.isNotBlank()) {
            setStatus(binding.statusPlaylist, "\u2713 Playlist set", StatusKind.SUCCESS)
        } else {
            setStatus(binding.statusPlaylist, "No playlist set yet", StatusKind.NEUTRAL)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) refreshAllStatuses()
    }

    private fun currentDaysMask(): Int {
        var mask = 0
        dayChips.forEach { (chip, dayOfWeek) ->
            if (chip.isChecked) mask = mask or (1 shl (dayOfWeek - Calendar.SUNDAY))
        }
        return mask
    }

    private fun formatDaysMask(mask: Int): String {
        if (mask == Prefs.ALL_DAYS_MASK) return "every day"
        if (mask == 0) return "no days selected"
        val weekdays = (1 shl (Calendar.MONDAY - Calendar.SUNDAY)) or (1 shl (Calendar.TUESDAY - Calendar.SUNDAY)) or
            (1 shl (Calendar.WEDNESDAY - Calendar.SUNDAY)) or (1 shl (Calendar.THURSDAY - Calendar.SUNDAY)) or
            (1 shl (Calendar.FRIDAY - Calendar.SUNDAY))
        if (mask == weekdays) return "weekdays"
        val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        return names.filterIndexed { i, _ -> (mask and (1 shl i)) != 0 }.joinToString(", ")
    }

    @SuppressLint("SetTextI18n")
    private fun refreshAllStatuses() {
        val pairedAt = Prefs.pairedAt(this)
        val pairError = WebOsClient.lastPairError
        when {
            Prefs.clientKey(this) != null && pairedAt > 0 ->
                setStatus(binding.statusPair, "\u2713 Connected on ${timeFmt.format(Date(pairedAt))}", StatusKind.SUCCESS)
            Prefs.clientKey(this) == null && pairError != null ->
                setStatus(binding.statusPair, "\u2717 $pairError", StatusKind.ERROR)
            else ->
                setStatus(binding.statusPair, "Not connected yet", StatusKind.NEUTRAL)
        }

        refreshPlaylistStatus()

        if (Prefs.isScheduled(this)) {
            val h = Prefs.alarmHour(this)
            val m = Prefs.alarmMinute(this)
            val days = formatDaysMask(Prefs.alarmDaysMask(this))
            setStatus(binding.statusSchedule, "\u2713 Scheduled at %02d:%02d \u2014 $days".format(h, m), StatusKind.SUCCESS)
        } else {
            setStatus(binding.statusSchedule, "Not scheduled yet", StatusKind.NEUTRAL)
        }

        val lastRunAt = Prefs.lastRunAt(this)
        val lastRunStatus = Prefs.lastRunStatus(this)
        when {
            lastRunAt == 0L -> setStatus(binding.statusRun, "Not run yet", StatusKind.NEUTRAL)
            lastRunStatus == "success" -> setStatus(binding.statusRun, "\u2713 Last run succeeded \u2014 ${timeFmt.format(Date(lastRunAt))}", StatusKind.SUCCESS)
            lastRunStatus == "unreachable" -> setStatus(binding.statusRun, "\u2717 Last run failed \u2014 TV unreachable (${timeFmt.format(Date(lastRunAt))})", StatusKind.ERROR)
            else -> setStatus(binding.statusRun, "\u2717 Last run failed \u2014 ${timeFmt.format(Date(lastRunAt))}", StatusKind.ERROR)
        }
    }

    private fun currentIp() = binding.inputTvIp.text.toString().trim()

    private fun doScan() {
        toast("Scanning network for TVs...")
        binding.btnScan.isEnabled = false
        thread {
            val devices = try {
                SsdpDiscovery.discover(this)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                binding.btnScan.isEnabled = true
                if (devices.isEmpty()) {
                    toast("No TVs found \u2014 make sure the TV is on and on the same network")
                    return@runOnUiThread
                }
                val labels = devices.map { "${it.name}  (${it.ip})" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Select your TV")
                    .setItems(labels) { _, which ->
                        val picked = devices[which]
                        binding.inputTvIp.setText(picked.ip)
                        toast("Filled in IP for ${picked.name} \u2014 now tap Pair with TV (MAC fills in automatically after pairing)")
                    }
                    .show()
            }
        }
    }

    private fun doPair() {
        DebugLog.section("USER TAPPED: Pair with TV")
        val ip = currentIp()
        if (ip.isBlank()) {
            toast("Enter the TV's IP address first")
            return
        }
        binding.btnPair.isEnabled = false
        toast("Connecting to TV...")
        thread {
            if (!WebOsClient.waitForTv(ip, timeoutMs = 15_000)) {
                runOnUiThread {
                    binding.btnPair.isEnabled = true
                    toast("Can't reach the TV \u2014 make sure it's on for pairing")
                }
                return@thread
            }
            val key = WebOsClient.pair(ip) {
                runOnUiThread { toast("Accept the pairing prompt on the TV screen") }
            }
            if (key != null) {
                Prefs.saveClientKey(this, key)
                // Ask the TV for its own MAC address (works regardless of Android's
                // network-privacy restrictions, since we ask the TV, not the OS).
                val mac = WebOsClient.getMacAddress(ip, key)
                runOnUiThread {
                    binding.btnPair.isEnabled = true
                    if (mac != null) {
                        binding.inputTvMac.setText(mac)
                        toast("Paired \u2014 MAC address filled in automatically")
                    } else {
                        toast("Paired \u2014 couldn't read the MAC automatically, enter it manually")
                    }
                    refreshAllStatuses()
                }
            } else {
                runOnUiThread {
                    binding.btnPair.isEnabled = true
                    toast("Pairing failed: ${WebOsClient.lastPairError ?: "unknown error"}")
                }
            }
        }
    }

    /**
     * Accepts either a proper Spotify URI (spotify:playlist:ID) or a web share
     * link (https://open.spotify.com/playlist/ID?...) and returns the compact
     * URI form the TV's Spotify app needs to launch directly to that playlist.
     * Anything else is returned unchanged.
     */
    private fun normalizePlaylistUri(input: String): String {
        val trimmed = input.trim()
        if (trimmed.startsWith("spotify:")) return trimmed
        val match = Regex("open\\.spotify\\.com/playlist/([A-Za-z0-9]+)").find(trimmed)
        return if (match != null) "spotify:playlist:${match.groupValues[1]}" else trimmed
    }

    /** Accepts 12 hex digits, with or without : or - separators every 2 chars. */
    private fun isValidMac(mac: String): Boolean =
        Regex("^([0-9A-Fa-f]{2}[:-]?){5}[0-9A-Fa-f]{2}$").matches(mac)

    private fun doSaveAndSchedule() {
        DebugLog.section("USER TAPPED: Save + Schedule Alarm")
        val ip = currentIp()
        val mac = binding.inputTvMac.text.toString().trim()
        val playlist = normalizePlaylistUri(binding.inputPlaylistUri.text.toString())
        binding.inputPlaylistUri.setText(playlist)
        val appId = binding.inputSpotifyAppId.text.toString().trim().ifBlank { "spotify-beehive" }
        val hour = binding.timePicker.hour
        val minute = binding.timePicker.minute
        val daysMask = currentDaysMask()

        if (ip.isBlank() || playlist.isBlank()) {
            toast("Fill in TV IP and playlist URI")
            return
        }
        if (mac.isNotBlank() && !isValidMac(mac)) {
            toast("That MAC address doesn't look right \u2014 should be 12 hex characters, e.g. AA:BB:CC:DD:EE:FF")
            return
        }
        if (Prefs.clientKey(this) == null) {
            toast("Pair with the TV first (step 1)")
            return
        }
        if (daysMask == 0) {
            toast("Select at least one day")
            return
        }

        Prefs.save(this, ip, mac, playlist, appId, hour, minute, daysMask)

        if (!AlarmScheduler.canScheduleExact(this)) {
            toast("Grant \"Alarms & reminders\" permission, then tap Save again")
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            return
        }

        AlarmScheduler.scheduleNext(this)
        Prefs.markScheduled(this)
        refreshAllStatuses()
        if (mac.isBlank()) {
            toast("Alarm scheduled \u2014 no MAC set, so this won't wake an already-off TV. Find the MAC in the TV's own Settings > Network menu and add it here when you can.")
        } else {
            toast("Alarm scheduled")
        }
        requestIgnoreBatteryOptimizations()
    }

    /** Asks the OS not to kill this app in the background, so the scheduled alarm keeps firing reliably. */
    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                // Some device firmwares don't support this intent — safe to ignore.
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun doRunNow() {
        DebugLog.section("USER TAPPED: Run Now")
        val ip = currentIp()
        if (ip.isBlank() || Prefs.clientKey(this) == null) {
            toast("Save your settings and pair with the TV first")
            return
        }
        val mac = binding.inputTvMac.text.toString().trim()
        if (mac.isNotBlank() && !isValidMac(mac)) {
            toast("That MAC address doesn't look right \u2014 should be 12 hex characters, e.g. AA:BB:CC:DD:EE:FF")
            return
        }
        val playlist = normalizePlaylistUri(binding.inputPlaylistUri.text.toString())
        binding.inputPlaylistUri.setText(playlist)
        Prefs.save(
            this,
            ip,
            mac,
            playlist,
            binding.inputSpotifyAppId.text.toString().trim().ifBlank { "spotify-beehive" },
            binding.timePicker.hour,
            binding.timePicker.minute,
            currentDaysMask()
        )
        setStatus(binding.statusRun, "Running\u2026", StatusKind.NEUTRAL)
        binding.btnRunNow.isEnabled = false

        val request = OneTimeWorkRequestBuilder<TvAlarmWorker>().build()
        val wm = WorkManager.getInstance(this)
        wm.enqueue(request)
        wm.getWorkInfoByIdLiveData(request.id).observe(this) { info ->
            if (info != null && info.state.isFinished) {
                binding.btnRunNow.isEnabled = true
                refreshAllStatuses()
            }
        }
        toast("Running now \u2014 TV should wake shortly")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun showDebugLog() {
        val logText = DebugLog.getLog()
        val textView = TextView(this).apply {
            text = logText
            textSize = 11f
            setPadding(32, 24, 32, 24)
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Debug Log (long-press button to clear)")
            .setView(textView)
            .setPositiveButton("Copy to Clipboard") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TV Alarm debug log", logText))
                toast("Copied \u2014 paste it wherever you need to share it")
            }
            .setNeutralButton("Share") { _, _ ->
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, logText)
                    putExtra(Intent.EXTRA_SUBJECT, "TV Morning Alarm debug log")
                }
                startActivity(Intent.createChooser(sendIntent, "Share debug log"))
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
