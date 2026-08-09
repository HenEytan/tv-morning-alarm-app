package com.henos.tvalarm

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
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
    private val countdownHandler = Handler(Looper.getMainLooper())
    private val countdownTicker = object : Runnable {
        override fun run() {
            updateCountdown()
            countdownHandler.postDelayed(this, 30_000)
        }
    }

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

        binding.timePicker.setIs24HourView(true)
        populateFieldsFromPrefs()

        binding.seekVolume.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.labelVolume.text = "Wake-up volume: $progress"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.btnConnect.setOnClickListener { doConnect() }
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

        thread {
            SettingsSync.pullOrBootstrap(this)
            runOnUiThread {
                populateFieldsFromPrefs()
                refreshAllStatuses()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun populateFieldsFromPrefs() {
        binding.inputTvIp.setText(Prefs.tvIp(this))
        binding.inputTvMac.setText(Prefs.tvMac(this))
        binding.inputPlaylistUri.setText(Prefs.playlistUri(this))
        binding.inputSpotifyAppId.setText(Prefs.spotifyAppId(this))
        binding.timePicker.hour = Prefs.alarmHour(this)
        binding.timePicker.minute = Prefs.alarmMinute(this)

        val savedMask = Prefs.alarmDaysMask(this)
        dayChips.forEach { (chip, dayOfWeek) ->
            chip.isChecked = (savedMask and (1 shl (dayOfWeek - Calendar.SUNDAY))) != 0
        }

        binding.seekVolume.progress = Prefs.wakeVolume(this)
        binding.labelVolume.text = "Wake-up volume: ${Prefs.wakeVolume(this)}"
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
        if (::binding.isInitialized) {
            refreshAllStatuses()
            countdownHandler.removeCallbacks(countdownTicker)
            countdownHandler.post(countdownTicker)
        }
    }

    override fun onPause() {
        super.onPause()
        countdownHandler.removeCallbacks(countdownTicker)
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

        updateCountdown()
    }

    @SuppressLint("SetTextI18n")
    private fun updateCountdown() {
        if (!Prefs.isScheduled(this)) {
            binding.statusCountdown.text = ""
            return
        }
        val nextAt = Prefs.nextAlarmAt(this)
        val diffMs = nextAt - System.currentTimeMillis()
        binding.statusCountdown.text = when {
            nextAt <= 0L -> ""
            diffMs <= 0L -> "Ringing any moment\u2026"
            else -> {
                val totalMinutes = (diffMs / 60_000).toInt()
                val h = totalMinutes / 60
                val m = totalMinutes % 60
                val parts = mutableListOf<String>()
                if (h > 0) parts.add("${h}h")
                parts.add("${m}m")
                "Next alarm in ${parts.joinToString(" ")}"
            }
        }
    }

    private fun currentIp() = binding.inputTvIp.text.toString().trim()

    /**
     * Single-button flow: scans the network, auto-picks the TV if there's
     * exactly one (or lets the user pick if there are several), connects,
     * and stores whatever MAC address it can find along the way. IP and
     * MAC are stored automatically \u2014 nothing to type for the normal case.
     */
    private fun doConnect() {
        DebugLog.section("USER TAPPED: Connect to TV")
        binding.btnConnect.isEnabled = false
        setStatus(binding.statusPair, "Searching for your TV\u2026", StatusKind.NEUTRAL)
        thread {
            val devices = try {
                SsdpDiscovery.discover(this)
            } catch (e: Exception) {
                emptyList()
            }
            when {
                devices.size == 1 -> proceedWithIp(devices[0].ip)
                devices.size > 1 -> runOnUiThread {
                    val labels = devices.map { "${it.name}  (${it.ip})" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Select your TV")
                        .setCancelable(false)
                        .setItems(labels) { _, which -> proceedWithIp(devices[which].ip) }
                        .setOnCancelListener { binding.btnConnect.isEnabled = true; binding.btnConnect.text = "Try Again"; refreshAllStatuses() }
                        .show()
                }
                else -> {
                    // Nothing found via scan \u2014 fall back to the last IP that worked, if any.
                    val savedIp = Prefs.tvIp(this)
                    if (savedIp.isNotBlank() && WebOsClient.waitForTv(savedIp, timeoutMs = 8_000)) {
                        proceedWithIp(savedIp)
                    } else {
                        runOnUiThread {
                            binding.btnConnect.isEnabled = true
                            binding.btnConnect.text = "Try Again"
                            setStatus(binding.statusPair, "\u2717 No TV found on the network", StatusKind.ERROR)
                            toast("No TVs found \u2014 make sure the TV is on and on the same Wi-Fi network")
                        }
                    }
                }
            }
        }
    }

    /** Connects to a known IP: waits for it to be reachable, pairs, and fetches the MAC if possible. */
    private fun proceedWithIp(ip: String) {
        runOnUiThread {
            binding.inputTvIp.setText(ip)
            setStatus(binding.statusPair, "Connecting to $ip\u2026", StatusKind.NEUTRAL)
        }
        thread {
            if (!WebOsClient.waitForTv(ip, timeoutMs = 15_000)) {
                runOnUiThread {
                    binding.btnConnect.isEnabled = true
                    binding.btnConnect.text = "Try Again"
                    setStatus(binding.statusPair, "\u2717 Can't reach the TV", StatusKind.ERROR)
                    toast("Can't reach the TV \u2014 make sure it's turned on")
                }
                return@thread
            }
            val key = WebOsClient.pair(ip) {
                runOnUiThread { toast("Accept the pairing prompt on the TV screen") }
            }
            if (key != null) {
                Prefs.saveClientKey(this, key)
                // Ask the TV for its own MAC first; if the firmware doesn't expose that,
                // WebOsClient falls back to reading it from the local ARP table.
                val mac = WebOsClient.getMacAddress(ip, key)
                runOnUiThread {
                    binding.btnConnect.isEnabled = true
                    binding.btnConnect.text = "Reconnect"
                    if (mac != null) {
                        binding.inputTvMac.setText(mac)
                        toast("Connected \u2014 ready to go, Wake-on-LAN is set up")
                    } else {
                        toast("Connected, but this TV won't hand over its MAC automatically \u2014 open Advanced and enter it manually (find it in the TV's own Settings \u2192 Network menu) for Wake-on-LAN to work")
                    }
                    refreshAllStatuses()
                }
            } else {
                runOnUiThread {
                    binding.btnConnect.isEnabled = true
                    binding.btnConnect.text = "Try Again"
                    toast("Connection failed: ${WebOsClient.lastPairError ?: "unknown error"}")
                    refreshAllStatuses()
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
            toast("Connect to the TV first (step 1)")
            return
        }
        if (daysMask == 0) {
            toast("Select at least one day")
            return
        }

        Prefs.save(this, ip, mac, playlist, appId, hour, minute, daysMask, binding.seekVolume.progress)

        if (!AlarmScheduler.canScheduleExact(this)) {
            toast("Grant \"Alarms & reminders\" permission, then tap Save again")
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            return
        }

        AlarmScheduler.scheduleNext(this)
        Prefs.markScheduled(this)
        refreshAllStatuses()
        thread { SettingsSync.push(this) }
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
            toast("Save your settings and connect to the TV first")
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
            currentDaysMask(),
            binding.seekVolume.progress
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
