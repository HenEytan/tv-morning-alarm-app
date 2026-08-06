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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object WebOsClient {

    private const val SSAP_PORT = 3000

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

    fun waitForTv(ip: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(ip, SSAP_PORT), 2000)
                    return true
                }
            } catch (e: Exception) {
                Thread.sleep(2000)
            }
        }
        return false
    }

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

    fun pair(ip: String, onNeedsTvPrompt: () -> Unit): String? {
        val client = OkHttpClient()
        val latch = CountDownLatch(1)
        var result: String? = null
        val request = Request.Builder().url("ws://$ip:$SSAP_PORT").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
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
                if (resp.optString("type") == "registered") {
                    result = resp.getJSONObject("payload").getString("client-key")
                    latch.countDown()
                } else if (resp.optString("type") == "error") {
                    latch.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                latch.countDown()
            }
        })
        latch.await(60, TimeUnit.SECONDS)
        ws.close(1000, null)
        client.dispatcher.executorService.shutdown()
        return result
    }

    fun launchApp(ip: String, clientKey: String, appId: String, contentUri: String): Boolean {
        val client = OkHttpClient()
        val latch = CountDownLatch(1)
        var success = false
        val request = Request.Builder().url("ws://$ip:$SSAP_PORT").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
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
        latch.await(20, TimeUnit.SECONDS)
        ws.close(1000, null)
        client.dispatcher.executorService.shutdown()
        return success
    }
}
