package com.henos.tvalarm

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Wake-on-LAN + LG webOS SSAP (System Service Access Protocol) client.
 * Handles the one-time pairing handshake and app-launch requests over the
 * TV's local WebSocket control port.
 *
 * Older webOS firmware accepts plain ws:// on port 3000. Many updated
 * firmwares (including older TVs after a software update) only accept
 * wss:// (TLS, self-signed cert) on port 3001. We try both, in order.
 *
 * Every step is written to DebugLog so a failure can be diagnosed from
 * one copy-pasted log instead of trial-and-error screenshots.
 */
object WebOsClient {

    private const val TAG = "WebOsClient"

    /** Human-readable reason for the most recent pairing failure, if any. */
    @Volatile
    var lastPairError: String? = null
        private set

    private data class Endpoint(val url: String, val secure: Boolean)

    private fun endpointsFor(ip: String) = listOf(
        Endpoint("ws://$ip:3000", secure = false),
        Endpoint("wss://$ip:3001", secure = true),
    )

    // ---- Wake on LAN --------------------------------------------------

    fun sendWol(mac: String) {
        DebugLog.log(TAG, "sendWol: sending magic packet to MAC=$mac")
        try {
            val macBytes = mac.replace(":", "").replace("-", "")
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val bytes = ByteArray(6) { 0xFF.toByte() } + macBytes.copyOf(6).let { m ->
                ByteArray(16 * 6).also { buf ->
                    for (i in 0 until 16) m.copyInto(buf, i * 6)
                }
            }
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), 9)
                socket.send(packet)
            }
            DebugLog.log(TAG, "sendWol: packet sent OK")
        } catch (e: Exception) {
            DebugLog.log(TAG, "sendWol: FAILED - ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---- Reachability ---------------------------------------------------

    fun waitForTv(ip: String, timeoutMs: Long): Boolean {
        DebugLog.log(TAG, "waitForTv: polling $ip ports [3000, 3001] for up to ${timeoutMs}ms")
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            for (port in listOf(3000, 3001)) {
                try {
                    Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(ip, port), 2000)
                        DebugLog.log(TAG, "waitForTv: attempt $attempt - port $port is OPEN")
                        return true
                    }
                } catch (e: Exception) {
                    DebugLog.log(TAG, "waitForTv: attempt $attempt - port $port closed/unreachable (${e.javaClass.simpleName}: ${e.message})")
                }
            }
            Thread.sleep(1500)
        }
        DebugLog.log(TAG, "waitForTv: TIMED OUT after ${timeoutMs}ms - neither port 3000 nor 3001 opened")
        return false
    }

    // ---- TLS client that trusts the TV's self-signed certificate --------

    private fun trustAllClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAll), SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun clientFor(secure: Boolean) = if (secure) trustAllClient() else OkHttpClient()

    // ---- Device identity --------------------------------------------------
    // A unique-per-install serial, instead of the widely-shared public test
    // serial many hobbyist webOS projects use. If the TV has any stale/stuck
    // pairing record tied to a previously-seen appId+serial pair, reusing
    // that shared value can cause the TV to silently accept the socket
    // handshake without ever rendering the on-screen prompt. Generated once
    // and persisted so an already-accepted pairing keeps working.
    private fun deviceSerial(): String {
        val ctx = DebugLog.appContext
        val prefs = ctx?.getSharedPreferences("tvalarm_identity", android.content.Context.MODE_PRIVATE)
        val existing = prefs?.getString("serial", null)
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString().replace("-", "")
        prefs?.edit()?.putString("serial", fresh)?.apply()
        return fresh
    }

    // ---- Pairing manifest -------------------------------------------------

    private fun manifest(): JSONObject {
        val permissions = JSONArray(
            listOf(
                "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE",
                "TEST_OPEN", "TEST_PROTECTED", "CONTROL_AUDIO",
                "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
                "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_MEDIA_PLAYBACK",
                "CONTROL_INPUT_TV", "CONTROL_POWER", "READ_APP_STATUS",
                "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST",
                "READ_NETWORK_STATE", "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST",
                "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE", "READ_COUNTRY_INFO"
            )
        )
        val signed = JSONObject().apply {
            put("created", "20140509")
            put("appId", "com.henos.tvalarm")
            put("vendorId", "com.henos")
            put("localizedAppNames", JSONObject().put("", "TV Morning Alarm"))
            put("permissions", permissions)
            put("serial", deviceSerial())
        }
        val signature = JSONObject().apply {
            put("signatureVersion", 1)
            put(
                "signature",
                "eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmct" +
                    "a2V5Iiwic2lnbmF0dXJlVmVyc2lvbiI6MX0.hrVRgjCwXVvE2OOSpDZ58hR" +
                    "+59aFNwYDyD09z/N15hRLxMc9yWa0S8SEZ7SPn9qmc5wKbQCG" +
                    "yVLm4YZFDzYPqoK6UYVCLTZWpg30dOayQ2oJ1PdaBUS5PmoBLYRy2zplbayGJ8" +
                    "S89lqzeeh7B7GVCVN0lYtSOU+OGV3XW3TWlBofjZaXTALjq" +
                    "AK+ZzHiHzTV3Y0F5eD11kSSy9BjnzAgTP24bJVe3nnJvxrOBzxG2K33" +
                    "yGH3AqUyPmryUqUR4XkC3xEUJz6yHZs2rjOHc12PVmU05fpg9xnzP2M0" +
                    "GTLdlLASD1FZo7z5aFvERyIkgg1XY5FfaGTMoT0"
            )
        }
        return JSONObject().apply {
            put("manifestVersion", 1)
            put("appVersion", "1.1")
            put("signed", signed)
            put("permissions", permissions)
            put("signatures", JSONArray().put(signature))
        }
    }

    // ---- Pairing --------------------------------------------------------

    /** Blocking. Call from a background thread. Tries ws:// then wss://. Returns the client-key, or null. */
    fun pair(ip: String, onNeedsTvPrompt: () -> Unit): String? {
        DebugLog.section("PAIR START ip=$ip")
        lastPairError = null
        for (endpoint in endpointsFor(ip)) {
            val result = pairOverEndpoint(endpoint, onNeedsTvPrompt)
            if (result != null) {
                DebugLog.log(TAG, "pair: SUCCESS via ${endpoint.url}")
                return result
            }
        }
        DebugLog.log(TAG, "pair: FAILED over all endpoints. lastPairError=$lastPairError")
        return null
    }

    private fun pairOverEndpoint(endpoint: Endpoint, onNeedsTvPrompt: () -> Unit): String? {
        DebugLog.log(TAG, "pairOverEndpoint: trying ${endpoint.url}")
        val client = clientFor(endpoint.secure)
        val latch = CountDownLatch(1)
        var result: String? = null
        val request = Request.Builder().url(endpoint.url).build()
        val ws = try {
            client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    DebugLog.log(TAG, "${endpoint.url}: onOpen (HTTP ${response.code}) - sending register request")
                    val payload = JSONObject().apply {
                        put("forcePairing", false)
                        put("pairingType", "PROMPT")
                        put("manifest", manifest())
                    }
                    val msg = JSONObject().apply {
                        put("type", "register")
                        put("id", UUID.randomUUID().toString())
                        put("payload", payload)
                    }
                    webSocket.send(msg.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    DebugLog.log(TAG, "${endpoint.url}: received: $text")
                    val resp = JSONObject(text)
                    when (resp.optString("type")) {
                        "registered" -> {
                            result = resp.getJSONObject("payload").getString("client-key")
                            latch.countDown()
                        }
                        "error" -> {
                            lastPairError = "${endpoint.url}: ${resp.optString("error", resp.toString())}"
                            latch.countDown()
                        }
                        "response" -> {
                            // The TV acknowledged the register request and (should be) showing
                            // the on-screen accept prompt now. The final "registered" message
                            // only arrives after the user taps Accept on the TV.
                            onNeedsTvPrompt()
                        }
                        else -> {
                            // Unrecognized intermediate message - keep waiting.
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    DebugLog.log(TAG, "${endpoint.url}: onClosed code=$code reason=$reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val httpInfo = if (response != null) " (HTTP ${response.code})" else ""
                    lastPairError = "${endpoint.url}: ${t.javaClass.simpleName}: ${t.message}$httpInfo"
                    DebugLog.log(TAG, "${endpoint.url}: onFailure - ${t.javaClass.simpleName}: ${t.message}$httpInfo")
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            lastPairError = "${endpoint.url}: ${e.message}"
            DebugLog.log(TAG, "${endpoint.url}: exception opening socket - ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        val completed = latch.await(90, TimeUnit.SECONDS)
        if (!completed) {
            lastPairError = "${endpoint.url}: timed out after 90s \u2014 the TV never sent a final response. If no prompt appeared on screen at all, check the TV for an \"LG Connect Apps\" / \"Mobile TV On\" style setting, or a list of paired/blocked devices that may need clearing."
            DebugLog.log(TAG, "${endpoint.url}: TIMEOUT waiting for registered/error response")
        }
        ws.close(1000, null)
        client.dispatcher.executorService.shutdown()
        return result
    }

    // ---- Launch app -------------------------------------------------------

    /** Blocking. Call from a background thread. Returns true on success. */
    fun launchApp(ip: String, clientKey: String, appId: String, contentUri: String): Boolean {
        DebugLog.section("LAUNCH APP ip=$ip appId=$appId contentUri=$contentUri")
        for (endpoint in endpointsFor(ip)) {
            if (launchAppOverEndpoint(endpoint, clientKey, appId, contentUri)) {
                DebugLog.log(TAG, "launchApp: SUCCESS via ${endpoint.url}")
                return true
            }
        }
        DebugLog.log(TAG, "launchApp: FAILED over all endpoints")
        return false
    }

    private fun launchAppOverEndpoint(endpoint: Endpoint, clientKey: String, appId: String, contentUri: String): Boolean {
        DebugLog.log(TAG, "launchAppOverEndpoint: trying ${endpoint.url}")
        val client = clientFor(endpoint.secure)
        val latch = CountDownLatch(1)
        var success = false
        val request = Request.Builder().url(endpoint.url).build()
        val ws = try {
            client.newWebSocket(request, object : WebSocketListener() {
                var registered = false

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    DebugLog.log(TAG, "${endpoint.url}: onOpen (HTTP ${response.code})")
                    val payload = JSONObject().apply {
                        put("forcePairing", false)
                        put("pairingType", "PROMPT")
                        put("manifest", manifest())
                        put("client-key", clientKey)
                    }
                    val msg = JSONObject().apply {
                        put("type", "register")
                        put("id", UUID.randomUUID().toString())
                        put("payload", payload)
                    }
                    webSocket.send(msg.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    DebugLog.log(TAG, "${endpoint.url}: received: $text")
                    val resp = JSONObject(text)
                    val type = resp.optString("type")
                    if (type == "registered" && !registered) {
                        registered = true
                        val launchPayload = JSONObject().apply {
                            put("id", appId)
                            put("contentId", contentUri)
                            put("params", JSONObject().put("contentTarget", contentUri))
                        }
                        val launchMsg = JSONObject().apply {
                            put("type", "request")
                            put("id", UUID.randomUUID().toString())
                            put("uri", "ssap://system.launcher/launch")
                            put("payload", launchPayload)
                        }
                        DebugLog.log(TAG, "${endpoint.url}: registered OK, sending launch request")
                        webSocket.send(launchMsg.toString())
                    } else if (type == "response" || type == "error") {
                        success = type == "response"
                        latch.countDown()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    DebugLog.log(TAG, "${endpoint.url}: onFailure - ${t.javaClass.simpleName}: ${t.message}")
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            DebugLog.log(TAG, "${endpoint.url}: exception opening socket - ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        val completed = latch.await(20, TimeUnit.SECONDS)
        if (!completed) DebugLog.log(TAG, "${endpoint.url}: TIMEOUT waiting for launch response")
        ws.close(1000, null)
        client.dispatcher.executorService.shutdown()
        return success
    }

    // ---- Volume -------------------------------------------------------------

    /** Blocking. Call from a background thread. Sets the TV's volume (0-100). Returns true on success. */
    fun setVolume(ip: String, clientKey: String, volume: Int): Boolean {
        DebugLog.section("SET VOLUME ip=$ip volume=$volume")
        for (endpoint in endpointsFor(ip)) {
            if (setVolumeOverEndpoint(endpoint, clientKey, volume)) {
                DebugLog.log(TAG, "setVolume: SUCCESS via ${endpoint.url}")
                return true
            }
        }
        DebugLog.log(TAG, "setVolume: FAILED over all endpoints")
        return false
    }

    private fun setVolumeOverEndpoint(endpoint: Endpoint, clientKey: String, volume: Int): Boolean {
        DebugLog.log(TAG, "setVolumeOverEndpoint: trying ${endpoint.url}")
        val client = clientFor(endpoint.secure)
        val latch = CountDownLatch(1)
        var success = false
        val request = Request.Builder().url(endpoint.url).build()
        val ws = try {
            client.newWebSocket(request, object : WebSocketListener() {
                var registered = false

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val payload = JSONObject().apply {
                        put("forcePairing", false)
                        put("pairingType", "PROMPT")
                        put("manifest", manifest())
                        put("client-key", clientKey)
                    }
                    val msg = JSONObject().apply {
                        put("type", "register")
                        put("id", UUID.randomUUID().toString())
                        put("payload", payload)
                    }
                    webSocket.send(msg.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    DebugLog.log(TAG, "${endpoint.url}: received: $text")
                    val resp = JSONObject(text)
                    val type = resp.optString("type")
                    if (type == "registered" && !registered) {
                        registered = true
                        val volumeMsg = JSONObject().apply {
                            put("type", "request")
                            put("id", UUID.randomUUID().toString())
                            put("uri", "ssap://audio/setVolume")
                            put("payload", JSONObject().put("volume", volume))
                        }
                        webSocket.send(volumeMsg.toString())
                    } else if (type == "response" || type == "error") {
                        success = type == "response"
                        latch.countDown()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    DebugLog.log(TAG, "${endpoint.url}: onFailure - ${t.javaClass.simpleName}: ${t.message}")
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            DebugLog.log(TAG, "${endpoint.url}: exception opening socket - ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        val completed = latch.await(10, TimeUnit.SECONDS)
        if (!completed) DebugLog.log(TAG, "${endpoint.url}: TIMEOUT waiting for setVolume response")
        ws.close(1000, null)
        client.dispatcher.executorService.shutdown()
        return success
    }

    // ---- Stop -----------------------------------------------------------------

    /** Blocking. Call from a background thread. Closes the given app on the TV, stopping playback. Returns true on success. */
    fun stopPlayback(ip: String, clientKey: String, appId: String): Boolean {
        DebugLog.section("STOP PLAYBACK ip=$ip appId=$appId")
        for (endpoint in endpointsFor(ip)) {
            if (stopPlaybackOverEndpoint(endpoint, clientKey, appId)) {
                DebugLog.log(TAG, "stopPlayback: SUCCESS via ${endpoint.url}")
                return true
            }
        }
        DebugLog.log(TAG, "stopPlayback: FAILED over all endpoints")
        return false
    }

    private fun stopPlaybackOverEndpoint(endpoint: Endpoint, clientKey: String, appId: String): Boolean {
        DebugLog.log(TAG, "stopPlaybackOverEndpoint: trying ${endpoint.url}")
        val client = clientFor(endpoint.secure)
        val latch = CountDownLatch(1)
        var success = false
        val request = Request.Builder().url(endpoint.url).build()
        val ws = try {
            client.newWebSocket(request, object : WebSocketListener() {
                var registered = false

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val payload = JSONObject().apply {
                        put("forcePairing", false)
                        put("pairingType", "PROMPT")
                        put("manifest", manifest())
                        put("client-key", clientKey)
                    }
                    val msg = JSONObject().apply {
                        put("type", "register")
                        put("id", UUID.randomUUID().toString())
                        put("payload", payload)
                    }
                    webSocket.send(msg.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    DebugLog.log(TAG, "${endpoint.url}: received: $text")
                    val resp = JSONObject(text)
                    val type = resp.optString("type")
                    if (type == "registered" && !registered) {
                        registered = true
                        val closeMsg = JSONObject().apply {
                            put("type", "request")
                            put("id", UUID.randomUUID().toString())
                            put("uri", "ssap://system.launcher/close")
                            put("payload", JSONObject().put("id", appId))
                        }
                        webSocket.send(closeMsg.toString())
                    } else if (type == "response" || type == "error") {
                        success = type == "response"
                        latch.countDown()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    DebugLog.log(TAG, "${endpoint.url}: onFailure - ${t.javaClass.simpleName}: ${t.message}")
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            DebugLog.log(TAG, "${endpoint.url}: exception opening socket - ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        val completed = latch.await(10, TimeUnit.SECONDS)
        if (!completed) DebugLog.log(TAG, "${endpoint.url}: TIMEOUT waiting for stopPlayback response")
        ws.close(1000, null)
        client.dispatcher.executorService.shutdown()
        return success
    }

    // ---- Turn off TV ------------------------------------------------------

    /** Blocking. Call from a background thread. Powers the TV off entirely. Returns true on success. */
    fun turnOffTv(ip: String, clientKey: String): Boolean {
        DebugLog.section("TURN OFF TV ip=$ip")
        for (endpoint in endpointsFor(ip)) {
            if (turnOffOverEndpoint(endpoint, clientKey)) {
                DebugLog.log(TAG, "turnOffTv: SUCCESS via ${endpoint.url}")
                return true
            }
        }
        DebugLog.log(TAG, "turnOffTv: FAILED over all endpoints")
        return false
    }

    private fun turnOffOverEndpoint(endpoint: Endpoint, clientKey: String): Boolean {
        DebugLog.log(TAG, "turnOffOverEndpoint: trying ${endpoint.url}")
        val client = clientFor(endpoint.secure)
        val latch = CountDownLatch(1)
        var success = false
        val request = Request.Builder().url(endpoint.url).build()
        val ws = try {
            client.newWebSocket(request, object : WebSocketListener() {
                var registered = false

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val payload = JSONObject().apply {
                        put("forcePairing", false)
                        put("pairingType", "PROMPT")
                        put("manifest", manifest())
                        put("client-key", clientKey)
                    }
                    val msg = JSONObject().apply {
                        put("type", "register")
                        put("id", UUID.randomUUID().toString())
                        put("payload", payload)
                    }
                    webSocket.send(msg.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    DebugLog.log(TAG, "${endpoint.url}: received: $text")
                    val resp = JSONObject(text)
                    val type = resp.optString("type")
                    if (type == "registered" && !registered) {
                        registered = true
                        val offMsg = JSONObject().apply {
                            put("type", "request")
                            put("id", UUID.randomUUID().toString())
                            put("uri", "ssap://system/turnOff")
                            put("payload", JSONObject())
                        }
                        webSocket.send(offMsg.toString())
                    } else if (type == "response" || type == "error") {
                        success = type == "response"
                        latch.countDown()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    DebugLog.log(TAG, "${endpoint.url}: onFailure - ${t.javaClass.simpleName}: ${t.message}")
                    // Many TVs close the socket immediately after accepting turnOff, before a
                    // response frame arrives - treat that as a likely success rather than a failure.
                    success = true
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            DebugLog.log(TAG, "${endpoint.url}: exception opening socket - ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        val completed = latch.await(10, TimeUnit.SECONDS)
        if (!completed) DebugLog.log(TAG, "${endpoint.url}: TIMEOUT waiting for turnOff response")
        ws.close(1000, null)
        client.dispatcher.executorService.shutdown()
        return success
    }

    // ---- MAC address lookup ------------------------------------------------

    /** Blocking. Call from a background thread. Returns the TV's active MAC address, or null. */
    fun getMacAddress(ip: String, clientKey: String): String? {
        DebugLog.section("GET MAC ADDRESS ip=$ip")
        for (endpoint in endpointsFor(ip)) {
            val mac = getMacOverEndpoint(endpoint, clientKey)
            if (mac != null) {
                DebugLog.log(TAG, "getMacAddress: SUCCESS via ${endpoint.url} - mac=$mac")
                return mac
            }
        }
        DebugLog.log(TAG, "getMacAddress: SSAP lookup failed on all endpoints, trying local ARP table")
        val arpMac = getMacFromArpTable(ip)
        if (arpMac != null) {
            DebugLog.log(TAG, "getMacAddress: SUCCESS via local ARP table - mac=$arpMac")
            return arpMac
        }
        DebugLog.log(TAG, "getMacAddress: FAILED over SSAP and ARP table (couldn't determine MAC)")
        return null
    }

    /**
     * Some webOS firmware doesn't expose its MAC over SSAP at all. As a fallback,
     * read the Android device's own ARP cache (/proc/net/arp) for a row matching
     * this IP \u2014 the kernel populates this automatically after any TCP connection
     * to that IP, which waitForTv()/pair() have already done by the time this runs.
     * Not available on every device/Android version; fails safe (returns null).
     */
    private fun getMacFromArpTable(ip: String): String? {
        return try {
            java.io.File("/proc/net/arp").readLines().drop(1)
                .map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { cols -> cols.firstOrNull() == ip }
                ?.getOrNull(3)
                ?.takeIf { mac ->
                    mac != "00:00:00:00:00:00" &&
                        Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(mac)
                }
                ?.uppercase()
        } catch (e: Exception) {
            DebugLog.log(TAG, "getMacFromArpTable: unavailable - ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun getMacOverEndpoint(endpoint: Endpoint, clientKey: String): String? {
        DebugLog.log(TAG, "getMacOverEndpoint: trying ${endpoint.url}")
        val client = clientFor(endpoint.secure)
        val latch = CountDownLatch(1)
        var result: String? = null
        // Older webOS firmware (pre-~webOS 3.x unification) only exposes the
        // legacy "system.connectionmanager" namespace; newer firmware uses
        // "com.webos.service.connectionmanager". Try legacy first (matches
        // the "system.launcher/launch" naming this TV already confirmed
        // working for app-launch), then fall back to the modern name.
        val uris = listOf(
            "ssap://system.connectionmanager/getStatus",
            "ssap://com.webos.service.connectionmanager/getStatus"
        )
        var uriIndex = 0
        val request = Request.Builder().url(endpoint.url).build()
        val ws = try {
            client.newWebSocket(request, object : WebSocketListener() {
                var registered = false

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val payload = JSONObject().apply {
                        put("forcePairing", false)
                        put("pairingType", "PROMPT")
                        put("manifest", manifest())
                        put("client-key", clientKey)
                    }
                    val msg = JSONObject().apply {
                        put("type", "register")
                        put("id", UUID.randomUUID().toString())
                        put("payload", payload)
                    }
                    webSocket.send(msg.toString())
                }

                private fun sendStatusRequest(webSocket: WebSocket) {
                    val statusMsg = JSONObject().apply {
                        put("type", "request")
                        put("id", UUID.randomUUID().toString())
                        put("uri", uris[uriIndex])
                        put("payload", JSONObject())
                    }
                    DebugLog.log(TAG, "${endpoint.url}: trying MAC lookup via ${uris[uriIndex]}")
                    webSocket.send(statusMsg.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    DebugLog.log(TAG, "${endpoint.url}: received: $text")
                    val resp = JSONObject(text)
                    val type = resp.optString("type")
                    if (type == "registered" && !registered) {
                        registered = true
                        sendStatusRequest(webSocket)
                    } else if (type == "response") {
                        val payload = resp.optJSONObject("payload")
                        if (payload != null) {
                            val wifi = payload.optJSONObject("wifi")
                            val wired = payload.optJSONObject("wired")
                            result = when {
                                wifi != null && wifi.optString("state") == "connected" && wifi.has("macAddress") ->
                                    wifi.getString("macAddress")
                                wired != null && wired.optString("state") == "connected" && wired.has("macAddress") ->
                                    wired.getString("macAddress")
                                wifi != null && wifi.has("macAddress") -> wifi.getString("macAddress")
                                wired != null && wired.has("macAddress") -> wired.getString("macAddress")
                                else -> null
                            }
                            if (result == null) {
                                DebugLog.log(TAG, "${endpoint.url}: ${uris[uriIndex]} response had no macAddress field in wifi/wired")
                            }
                        }
                        latch.countDown()
                    } else if (type == "error") {
                        if (uriIndex < uris.size - 1) {
                            uriIndex++
                            sendStatusRequest(webSocket)
                        } else {
                            latch.countDown()
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    DebugLog.log(TAG, "${endpoint.url}: onFailure - ${t.javaClass.simpleName}: ${t.message}")
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            DebugLog.log(TAG, "${endpoint.url}: exception opening socket - ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        val completed = latch.await(15, TimeUnit.SECONDS)
        if (!completed) DebugLog.log(TAG, "${endpoint.url}: TIMEOUT waiting for getStatus response")
        ws.close(1000, null)
        client.dispatcher.executorService.shutdown()
        return result
    }
}
