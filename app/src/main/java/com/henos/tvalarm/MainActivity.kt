package com.henos.tvalarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.henos.tvalarm.databinding.ActivityMainBinding
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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
    }

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
                    toast("No TVs found — make sure the TV is on and on the same network")
                    return@runOnUiThread
                }
                val labels = devices.map { "${it.name}  (${it.ip})" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Select your TV")
                    .setItems(labels) { _, which ->
                        val picked = devices[which]
                        binding.inputTvIp.setText(picked.ip)
                        val mac = ArpUtil.lookupMac(picked.ip)
                        if (mac != null) {
                            binding.inputTvMac.setText(mac)
                            toast("Filled in IP and MAC for ${picked.name}")
                        } else {
                            toast("Filled in IP for ${picked.name} — MAC address couldn't be auto-detected, enter it manually")
                        }
                    }
                    .show()
            }
        }
    }

    private fun currentIp() = binding.inputTvIp.text.toString().trim()

    private fun doPair() {
        val ip = currentIp()
        if (ip.isBlank()) {
            toast("Enter the TV's IP address first")
            return
        }
        toast("Connecting to TV...")
        thread {
            if (!WebOsClient.waitForTv(ip, timeoutMs = 15_000)) {
                runOnUiThread { toast("Can't reach the TV — make sure it's on for pairing") }
                return@thread
            }
            val key = WebOsClient.pair(ip) {
                runOnUiThread { toast("Accept the pairing prompt on the TV screen") }
            }
            runOnUiThread {
                if (key != null) {
                    Prefs.saveClientKey(this, key)
                    toast("Paired successfully")
                } else {
                    toast("Pairing failed or timed out — try again")
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
        binding.statusText.text = "Scheduled daily at %02d:%02d".format(hour, minute)
        toast("Alarm scheduled")
    }

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
        WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<TvAlarmWorker>().build())
        toast("Running now — TV should wake shortly")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
