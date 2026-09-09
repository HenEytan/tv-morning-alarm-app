package com.henos.tvalarm

import android.os.SystemClock
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
import java.net.NetworkInterface
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

    /**
     * Sends the Wake-on-LAN magic packet. [tvIp] is optional but strongly preferred:
     * it lets us derive the TV's own subnet broadcast address and unicast the packet
     * straight at the TV.
     *
     * Why more than one target: sending to the global broadcast address
     * 255.255.255.255 is refused outright on some Android builds - the debug log
     * recorded "sendto failed: EPERM (Operation not permitted)" - and the old code
     * treated that one failure as "no wake packet at all", so the TV never woke and
     * the run ended in a 90s unreachable timeout. Subnet broadcasts (192.168.1.255)
     * are not blocked, so fan the packet out across every broadcast address the
     * device actually has, plus a unicast to the TV, on both ports WoL listens on.
     */
    fun sendWol(mac: String, tvIp: String? = null) {
        val payload = magicPacket(mac)
        if (payload == null) {
            DebugLog.log(TAG, "sendWol: FAILED - '$mac' is not a valid MAC address")
            return
        }
        val targets = wolTargets(tvIp)
        DebugLog.log(TAG, "sendWol: sending magic packet for MAC=$mac to ${targets.joinToString { it.hostAddress ?: it.toString() }}")
        var delivered = 0
        var lastError: String? = null
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                TvNetwork.bind(socket)
                for (target in targets) {
                    for (port in intArrayOf(9, 7)) {
                        try {
                            socket.send(DatagramPacket(payload, payload.size, target, port))
                            delivered++
                        } catch (e: Exception) {
                            lastError = "${target.hostAddress}:$port ${e.javaClass.simpleName}: ${e.message}"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
        }
        if (delivered > 0) {
            DebugLog.log(TAG, "sendWol: packet sent OK ($delivered of ${targets.size * 2} target/port combinations accepted)")
        } else {
            DebugLog.log(TAG, "sendWol: FAILED - no target accepted the packet (last error: $lastError)")
        }
    }

    /** 6 bytes of 0xFF followed by the MAC repeated 16 times, or null if [mac] can't be parsed. */
    private fun magicPacket(mac: String): ByteArray? {
        val hex = mac.replace(":", "").replace("-", "").trim()
        if (!Regex("^[0-9A-Fa-f]{12}$").matches(hex)) return null
        val macBytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6).also { buf ->
            for (i in 0 until 16) macBytes.copyInto(buf, i * 6)
        }
    }

    /** Every address worth aiming a magic packet at, most likely to work first. */
    private fun wolTargets(tvIp: String?): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        // The TV's own subnet broadcast (192.168.1.189 -> 192.168.1.255): the address
        // a TV sitting in standby is listening on, and never EPERM-blocked.
        if (!tvIp.isNullOrBlank()) {
            runCatching { InetAddress.getByName(tvIp.substringBeforeLast('.') + ".255") }
                .getOrNull()?.let { targets.add(it) }
        }
        // Broadcast address of every live interface, which also covers non-/24 LANs.
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { !it.isLoopback && it.isUp }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
                .forEach { targets.add(it) }
        }
        // Unicast: still works while the router has the TV's ARP entry cached.
        if (!tvIp.isNullOrBlank()) {
            runCatching { InetAddress.getByName(tvIp) }.getOrNull()?.let { targets.add(it) }
        }
        // Global broadcast last: blocked on some builds, the only one that works on others.
        runCatching { InetAddress.getByName("255.255.255.255") }.getOrNull()?.let { targets.add(it) }
        return targets.toList()
    }

    // ---- Reachability ---------------------------------------------------

    fun waitForTv(ip: String, timeoutMs: Long): Boolean {
        DebugLog.log(TAG, "waitForTv: polling $ip ports [3000, 3001] for up to ${timeoutMs}ms")
        // elapsedRealtime, not currentTimeMillis: an NTP correction or a DST change
        // would otherwise move the deadline underneath us and burn the whole budget
        // in a single iteration.
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var attempt = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            attempt++
            for (port in listOf(3000, 3001)) {
                try {
                    TvNetwork.newSocket().use { s ->
                        s.connect(java.net.InetSocketAddress(ip, port), 2000)
                        DebugLog.log(TAG, "waitForTv: attempt $attempt - port $port is OPEN")
                        return true
                    }
                } catch (e: Exception) {
                    DebugLog.log(TAG, "waitForTv: attempt $attempt - port $port closed/unreachable (${e.javaClass.simpleName}: ${e.message})")
                }
                if (SystemClock.elapsedRealtime() >= deadline) break
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            Thread.sleep(minOf(1500L, remaining))
        }
        DebugLog.log(TAG, "waitForTv: TIMED OUT after ${timeoutMs}ms - neither port 3000 nor 3001 opened")
        return false
    }

    // ---- TLS client that trusts the TV's self-signed certificate --------

    /**
     * How long OkHttp may spend on the TCP/TLS handshake. This has to stay comfortably
     * below every latch timeout below. OkHttp's default is 10s, which is exactly the
     * latch timeout setVolume used, so the latch expired at the same instant the
     * connect did: we logged a misleading "TIMEOUT waiting for <uri> response", moved
     * on to the next endpoint while the first socket was still connecting, and its
     * onFailure then landed ten seconds later in the middle of an unrelated section
     * of the log.
     */
    private const val CONNECT_TIMEOUT_SECONDS = 5L

    // One shared client, and therefore one shared connection pool and dispatcher, for
    // the whole app. The old code built a fresh OkHttpClient for every single request
    // and then called dispatcher.executorService.shutdown() on it, which leaked a
    // thread pool per call while leaving the sockets themselves alive to fire
    // callbacks long after the request they belonged to had given up.
    private val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            // This bounds the HTTP upgrade handshake only, so it is safe to keep short
            // even though the pairing prompt can sit idle on the TV screen for a
            // minute: OkHttp sets the socket timeout to 0 once a connection is
            // upgraded to a WebSocket, so an established control socket is never
            // subject to it.
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val secureClient: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAll), SecureRandom())
        plainClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /**
     * newBuilder() shares the connection pool and dispatcher with [plainClient], so
     * pinning the socket to Wi-Fi costs nothing. Never shut the returned client down:
     * its executor is shared.
     */
    private fun clientFor(secure: Boolean): OkHttpClient {
        val base = if (secure) secureClient else plainClient
        val wifi = TvNetwork.socketFactory() ?: return base
        return base.newBuilder().socketFactory(wifi).build()
    }

    /**
     * Tears a WebSocket down for good. close() only queues a close frame and leaves the
     * socket - and its callbacks - alive if the TV never answers, which is how orphaned
     * sockets from a timed-out request ended up logging onFailure minutes later. cancel()
     * actually kills it.
     */
    private fun release(ws: WebSocket, graceful: Boolean) {
        if (graceful) ws.close(1000, null) else ws.cancel()
    }

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
        release(ws, graceful = completed)
        return result
    }

    // ---- Generic authenticated SSAP request --------------------------------

    enum class RequestResult { OK, ERROR, UNPAIRED, UNREACHABLE }

    /** Human-readable detail for the most recent command failure, if any. */
    @Volatile
    var lastCommandError: String? = null
        private set

    /**
     * Opens a socket, registers with the stored client-key, sends ONE request and
     * returns how it went. Tries ws:// then wss://.
     *
     * Important: a "response" that arrives BEFORE we are "registered" is the TV asking
     * us to pair again (our key is no longer trusted). Older code treated that as a
     * successful command, which silently did nothing. That case is now UNPAIRED.
     *
     * [successOnDropAfterSend]: for commands like turnOff, the TV often drops the socket
     * right after accepting and never sends a response frame; treat that as success.
     */
    private fun sendRequest(
        ip: String,
        clientKey: String,
        uri: String,
        payload: JSONObject,
        timeoutSeconds: Long = 15,
        successOnDropAfterSend: Boolean = false
    ): RequestResult {
        lastCommandError = null
        var lastResult = RequestResult.UNREACHABLE
        for (endpoint in endpointsFor(ip)) {
            val r = sendRequestOverEndpoint(endpoint, clientKey, uri, payload, timeoutSeconds, successOnDropAfterSend)
            DebugLog.log(TAG, "sendRequest $uri via ${endpoint.url}: $r")
            if (r == RequestResult.OK) return r
            // UNPAIRED / ERROR are definitive answers from the TV - no point trying the other port.
            if (r == RequestResult.UNPAIRED || r == RequestResult.ERROR) return r
            lastResult = r
        }
        return lastResult
    }

    private fun sendRequestOverEndpoint(
        endpoint: Endpoint,
        clientKey: String,
        uri: String,
        payload: JSONObject,
        timeoutSeconds: Long,
        successOnDropAfterSend: Boolean
    ): RequestResult {
        val client = clientFor(endpoint.secure)
        val latch = CountDownLatch(1)
        var result = RequestResult.UNREACHABLE
        val request = Request.Builder().url(endpoint.url).build()
        val ws = try {
            client.newWebSocket(request, object : WebSocketListener() {
                var registered = false
                var sent = false

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val reg = JSONObject().apply {
                        put("forcePairing", false)
                        put("pairingType", "PROMPT")
                        put("manifest", manifest())
                        put("client-key", clientKey)
                    }
                    val msg = JSONObject().apply {
                        put("type", "register")
                        put("id", UUID.randomUUID().toString())
                        put("payload", reg)
                    }
                    webSocket.send(msg.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    DebugLog.log(TAG, "${endpoint.url}: received: $text")
                    val resp = try { JSONObject(text) } catch (e: Exception) { return }
                    val type = resp.optString("type")
                    when {
                        type == "registered" && !registered -> {
                            registered = true
                            val req = JSONObject().apply {
                                put("type", "request")
                                put("id", UUID.randomUUID().toString())
                                put("uri", uri)
                                put("payload", payload)
                            }
                            sent = true
                            webSocket.send(req.toString())
                        }
                        !registered && type == "response" -> {
                            // Pairing prompt instead of "registered": our key is not trusted any more.
                            lastCommandError = "TV no longer recognizes this app - tap Connect to TV to pair again"
                            result = RequestResult.UNPAIRED
                            latch.countDown()
                        }
                        !registered && type == "error" -> {
                            lastCommandError = "${endpoint.url}: ${resp.optString("error", text)}"
                            result = RequestResult.ERROR
                            latch.countDown()
                        }
                        registered && type == "response" -> {
                            val ok = resp.optJSONObject("payload")?.optBoolean("returnValue", true) ?: true
                            if (!ok) lastCommandError = "${endpoint.url}: ${resp.optJSONObject("payload")?.optString("errorText") ?: text}"
                            result = if (ok) RequestResult.OK else RequestResult.ERROR
                            latch.countDown()
                        }
                        registered && type == "error" -> {
                            lastCommandError = "${endpoint.url}: ${resp.optString("error", text)}"
                            result = RequestResult.ERROR
                            latch.countDown()
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    DebugLog.log(TAG, "${endpoint.url}: onFailure - ${t.javaClass.simpleName}: ${t.message}")
                    if (sent && successOnDropAfterSend) {
                        result = RequestResult.OK
                    } else if (result == RequestResult.UNREACHABLE) {
                        lastCommandError = "${endpoint.url}: ${t.javaClass.simpleName}: ${t.message}"
                    }
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            DebugLog.log(TAG, "${endpoint.url}: exception opening socket - ${e.javaClass.simpleName}: ${e.message}")
            lastCommandError = "${endpoint.url}: ${e.message}"
            return RequestResult.UNREACHABLE
        }
        val completed = latch.await(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            DebugLog.log(TAG, "${endpoint.url}: TIMEOUT waiting for $uri response")
            lastCommandError = "${endpoint.url}: timed out waiting for the TV"
        }
        release(ws, graceful = completed)
        return result
    }

    // ---- Commands -----------------------------------------------------------

    /** Blocking. Launches [appId] with the given content URI. */
    fun launchApp(ip: String, clientKey: String, appId: String, contentUri: String): RequestResult {
        DebugLog.section("LAUNCH APP ip=$ip appId=$appId contentUri=$contentUri")
        val payload = JSONObject().apply {
            put("id", appId)
            put("contentId", contentUri)
            put("params", JSONObject().put("contentTarget", contentUri))
        }
        return sendRequest(ip, clientKey, "ssap://system.launcher/launch", payload, timeoutSeconds = 20)
    }

    /** Blocking. Sets the TV volume (0-100). */
    fun setVolume(ip: String, clientKey: String, volume: Int): RequestResult {
        DebugLog.section("SET VOLUME ip=$ip volume=$volume")
        return sendRequest(ip, clientKey, "ssap://audio/setVolume", JSONObject().put("volume", volume), timeoutSeconds = 10)
    }

    /** Blocking. Closes [appId] on the TV, which stops playback. */
    fun stopPlayback(ip: String, clientKey: String, appId: String): RequestResult {
        DebugLog.section("STOP PLAYBACK ip=$ip appId=$appId")
        return sendRequest(ip, clientKey, "ssap://system.launcher/close", JSONObject().put("id", appId), timeoutSeconds = 10)
    }

    /** Blocking. Powers the TV off. */
    fun turnOffTv(ip: String, clientKey: String): RequestResult {
        DebugLog.section("TURN OFF TV ip=$ip")
        return sendRequest(ip, clientKey, "ssap://system/turnOff", JSONObject(), timeoutSeconds = 10, successOnDropAfterSend = true)
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
     * Last-ditch fallback for firmware that doesn't expose its MAC over SSAP at all:
     * read the Android device's own ARP cache (/proc/net/arp) for a row matching this
     * IP, which the kernel populates after any TCP connection to it - something
     * waitForTv()/pair() have already done by the time this runs.
     *
     * Do not count on it. Android 10 and up refuse the read outright
     * ("/proc/net/arp: open failed: EACCES"), so on any current phone the SSAP
     * getinfo lookup above is the only thing that actually works, and if that fails
     * too the user has to type the MAC in by hand. Fails safe (returns null).
     */
    private fun getMacFromArpTable(ip: String): String? {
        return try {
            java.io.File("/proc/net/arp").readLines().drop(1)
                .map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { cols -> cols.firstOrNull() == ip }
                ?.getOrNull(3)
                ?.takeIf { mac -> mac != "00:00:00:00:00:00" && MAC_PATTERN.matches(mac) }
                ?.uppercase()
        } catch (e: Exception) {
            DebugLog.log(TAG, "getMacFromArpTable: unavailable - ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private val MAC_PATTERN = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    /**
     * getinfo splits its answer across two pairs of objects: "wired"/"wifi" carry the
     * connection state, while "wiredInfo"/"wifiInfo" carry the macAddress. Prefer the
     * MAC of whichever interface is actually connected - a TV on Ethernet still reports
     * a Wi-Fi MAC, and a magic packet aimed at the idle interface is simply ignored.
     */
    private fun extractMac(payload: JSONObject): String? {
        val wiredMac = macIn(payload.optJSONObject("wiredInfo")) ?: macIn(payload.optJSONObject("wired"))
        val wifiMac = macIn(payload.optJSONObject("wifiInfo")) ?: macIn(payload.optJSONObject("wifi"))
        val wiredConnected = payload.optJSONObject("wired")?.optString("state") == "connected"
        val wifiConnected = payload.optJSONObject("wifi")?.optString("state") == "connected"
        return when {
            wiredConnected && wiredMac != null -> wiredMac
            wifiConnected && wifiMac != null -> wifiMac
            else -> wiredMac ?: wifiMac
        }
    }

    private fun macIn(obj: JSONObject?): String? =
        obj?.optString("macAddress")?.takeIf { MAC_PATTERN.matches(it) }?.uppercase()

    private fun getMacOverEndpoint(endpoint: Endpoint, clientKey: String): String? {
        DebugLog.log(TAG, "getMacOverEndpoint: trying ${endpoint.url}")
        val client = clientFor(endpoint.secure)
        val latch = CountDownLatch(1)
        var result: String? = null
        // The method is getinfo, not getStatus. Both getStatus spellings the old code
        // tried came back "404 no such service or method" on every endpoint, which is
        // why this TV never handed over its MAC and Wake-on-LAN had to be typed in by
        // hand. Modern namespace first, legacy alias second, and the two old getStatus
        // names last in case some firmware really does answer to them.
        val uris = listOf(
            "ssap://com.webos.service.connectionmanager/getinfo",
            "ssap://system.connectionmanager/getinfo",
            "ssap://com.webos.service.connectionmanager/getStatus",
            "ssap://system.connectionmanager/getStatus"
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
                    } else if (type == "response" && !registered) {
                        // A pairing prompt where "registered" belongs: our stored key is
                        // no longer trusted, so there is nothing to read here.
                        DebugLog.log(TAG, "${endpoint.url}: TV asked to pair again - cannot read the MAC with this key")
                        latch.countDown()
                    } else if (type == "response") {
                        val payload = resp.optJSONObject("payload")
                        if (payload != null) {
                            result = extractMac(payload)
                            if (result == null) {
                                DebugLog.log(TAG, "${endpoint.url}: ${uris[uriIndex]} response carried no usable macAddress")
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
        release(ws, graceful = completed)
        return result
    }
}
