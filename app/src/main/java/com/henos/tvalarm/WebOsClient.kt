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
 */
object WebOsClient {

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
    }

    // ---- Reachability ---------------------------------------------------

    fun waitForTv(ip: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            for (port in listOf(3000, 3001)) {
                try {
                    Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(ip, port), 2000)
                        return true
                    }
                } catch (e: Exception) {
                    // try next port / retry
                }
            }
            Thread.sleep(1500)
        }
        return false
    }

    // ---- TLS client that trusts the TV's self-signed certificate --------
    // (LG webOS uses a self-signed cert for its local wss:// service; there
    // is no public CA to validate against, so trust-on-first-use over the
    // LAN is the standard approach used by webOS remote-control libraries.)

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

    // ---- Pairing manifest (standard local-pairing manifest used by most
    // open-source webOS remote-control clients; not a secret) -----------

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
            put("serial", "2f930e2d2cfe083771f68e4fe7bb07")
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
        lastPairError = null
        for (endpoint in endpointsFor(ip)) {
            val result = pairOverEndpoint(endpoint, onNeedsTvPrompt)
            if (result != null) return result
        }
        return null
    }

    private fun pairOverEndpoint(endpoint: Endpoint, onNeedsTvPrompt: () -> Unit): String? {
        val client = clientFor(endpoint.secure)
        val latch = CountDownLatch(1)
        var result: String? = null
        val request = Request.Builder().url(endpoint.url).build()
        val ws = try {
            client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
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
                    onNeedsTvPrompt()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
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
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    lastPairError = "${endpoint.url}: ${t.message ?: t.javaClass.simpleName}"
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            lastPairError = "${endpoint.url}: ${e.message}"
            return null
        }
        val completed = latch.await(20, TimeUnit.SECONDS)
        if (!completed && lastPairError == null) {
            lastPairError = "${endpoint.url}: timed out waiting for a response (check the TV screen for a pairing prompt)"
        }
        ws.close(1000, null)
        client.dispatcher.executorService.shutdown()
        return result
    }

    // ---- Launch app -------------------------------------------------------

    /** Blocking. Call from a background thread. Returns true on success. */
    fun launchApp(ip: String, clientKey: String, appId: String, contentUri: String): Boolean {
        for (endpoint in endpointsFor(ip)) {
            if (launchAppOverEndpoint(endpoint, clientKey, appId, contentUri)) return true
        }
        return false
    }

    private fun launchAppOverEndpoint(endpoint: Endpoint, clientKey: String, appId: String, contentUri: String): Boolean {
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
                        webSocket.send(launchMsg.toString())
                    } else if (type == "response" || type == "error") {
                        success = type == "response"
                        latch.countDown()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            return false
        }
        latch.await(20, TimeUnit.SECONDS)
        ws.close(1000, null)
        client.dispatcher.executorService.shutdown()
        return success
    }

    // ---- MAC address lookup (asks the TV for its own MAC, no OS-level network access needed) ----

    /** Blocking. Call from a background thread. Returns the TV's active MAC address, or null. */
    fun getMacAddress(ip: String, clientKey: String): String? {
        for (endpoint in endpointsFor(ip)) {
            val mac = getMacOverEndpoint(endpoint, clientKey)
            if (mac != null) return mac
        }
        return null
    }

    private fun getMacOverEndpoint(endpoint: Endpoint, clientKey: String): String? {
        val client = clientFor(endpoint.secure)
        val latch = CountDownLatch(1)
        var result: String? = null
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
                    val resp = JSONObject(text)
                    val type = resp.optString("type")
                    if (type == "registered" && !registered) {
                        registered = true
                        val statusMsg = JSONObject().apply {
                            put("type", "request")
                            put("id", UUID.randomUUID().toString())
                            put("uri", "ssap://com.webos.service.connectionmanager/getStatus")
                            put("payload", JSONObject())
                        }
                        webSocket.send(statusMsg.toString())
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
                        }
                        latch.countDown()
                    } else if (type == "error") {
                        latch.countDown()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            return null
        }
        latch.await(15, TimeUnit.SECONDS)
        ws.close(1000, null)
        client.dispatcher.executorService.shutdown()
        return result
    }
}
