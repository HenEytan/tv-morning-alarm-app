package com.henos.tvalarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.henos.tvalarm.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val timeFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.inputTvIp.setText(Prefs.tvIp(this))
        binding.inputTvMac.setText(Prefs.tvMac(this))
        binding.inputPlaylistUri.setText(Prefs.playlistUri(this))
        binding.inputSpotifyAppId.setText(Prefs.spotifyAppId(this))
        binding.timePicker.setIs24HourView(true)
        binding.timePicker.hour = Prefs.alarmHour(this)
        binding.timePicker.minute = Prefs.alarmMinute(this)

        binding.btnScan.setOnClickListener { doScan() }
        binding.btnPair.setOnClickListener { doPair() }
        binding.btnSave.setOnClickListener { doSaveAndSchedule() }
        binding.btnRunNow.setOnClickListener { doRunNow() }

        refreshAllStatuses()
    }

    override fun onResume() {
        super.onResume()
        refreshAllStatuses()
    }

    @SuppressLint("SetTextI18n")
    private fun refreshAllStatuses() {
        val pairedAt = Prefs.pairedAt(this)
        binding.statusPair.text = if (Prefs.clientKey(this) != null && pairedAt > 0)
            "\u2713 Paired on ${timeFmt.format(Date(pairedAt))}"
        else
            "Not paired yet"

        val pairError = WebOsClient.lastPairError
        if (Prefs.clientKey(this) == null && pairError != null) {
            binding.statusPair.text = "\u2717 $pairError"
        }

        if (Prefs.isScheduled(this)) {
            val h = Prefs.alarmHour(this)
            val m = Prefs.alarmMinute(this)
            binding.statusSchedule.text = "\u2713 Scheduled daily at %02d:%02d".format(h, m)
        } else {
            binding.statusSchedule.text = "Not scheduled yet"
        }

        val lastRunAt = Prefs.lastRunAt(this)
        val lastRunStatus = Prefs.lastRunStatus(this)
        binding.statusRun.text = when {
            lastRunAt == 0L -> "Not run yet"
            lastRunStatus == "success" -> "\u2713 Last run succeeded \u2014 ${timeFmt.format(Date(lastRunAt))}"
            lastRunStatus == "unreachable" -> "\u2717 Last run failed \u2014 TV unreachable (${timeFmt.format(Date(lastRunAt))})"
            else -> "\u2717 Last run failed \u2014 ${timeFmt.format(Date(lastRunAt))}"
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

    private fun doSaveAndSchedule() {
        val ip = currentIp()
        val mac = binding.inputTvMac.text.toString().trim()
        val playlist = binding.inputPlaylistUri.text.toString().trim()
        val appId = binding.inputSpotifyAppId.text.toString().trim().ifBlank { "spotify-beehive" }
        val hour = binding.timePicker.hour
        val minute = binding.timePicker.minute

        if (ip.isBlank() || mac.isBlank() || playlist.isBlank()) {
            toast("Fill in TV IP, MAC, and playlist URI")
            return
        }
        if (Prefs.clientKey(this) == null) {
            toast("Pair with the TV first (step 1)")
            return
        }

        Prefs.save(this, ip, mac, playlist, appId, hour, minute)

        if (!AlarmScheduler.canScheduleExact(this)) {
            toast("Grant \"Alarms & reminders\" permission, then tap Save again")
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            return
        }

        AlarmScheduler.scheduleNext(this)
        Prefs.markScheduled(this)
        refreshAllStatuses()
        toast("Alarm scheduled")
        requestIgnoreBatteryOptimizations()
    }

    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun doRunNow() {
        val ip = currentIp()
        if (ip.isBlank() || Prefs.clientKey(this) == null) {
            toast("Save your settings and pair with the TV first")
            return
        }
        Prefs.save(
            this,
            ip,
            binding.inputTvMac.text.toString().trim(),
            binding.inputPlaylistUri.text.toString().trim(),
            binding.inputSpotifyAppId.text.toString().trim().ifBlank { "spotify-beehive" },
            binding.timePicker.hour,
            binding.timePicker.minute
        )
        binding.statusRun.text = "Running\u2026"
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
}
