package com.henos.tvalarm

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID

/**
 * One long-lived, registered SSAP socket for live volume control.
 *
 * The one-shot commands in [WebOsClient] open a socket, register, send one request
 * and tear the socket down - right for an alarm that fires once a day, wrong for a
 * slider: every notch would cost a fresh TCP handshake, a TLS handshake on 3001 and
 * a register round-trip, and the TV would lag a second behind the thumb. This keeps
 * one socket open for as long as the screen is showing, subscribes to the TV's
 * volume so the slider follows the physical remote too, and coalesces slider moves
 * so at most one setVolume is ever in flight - the newest value always wins.
 *
 * Callbacks arrive on OkHttp threads; callers marshal to the UI thread themselves.
 */
class TvVolumeSession(
    private val ip: String,
    private val clientKey: String,
    private val listener: Listener,
) {
    interface Listener {
        // Every callback names its session so a UI that has moved on to a newer
        // session can ignore a stale socket's last words.

        /** Registered with the TV; controls can be enabled. */
        fun onConnected(session: TvVolumeSession)

        /** The TV reported its volume - on subscribe, after every change, and after our own sets. */
        fun onVolume(session: TvVolumeSession, volume: Int, muted: Boolean)

        /** The socket is gone. [unpaired] means the TV wants a fresh pairing, not that it is off. */
        fun onDisconnected(session: TvVolumeSession, reason: String, unpaired: Boolean)
    }

    private companion object {
        const val TAG = "TvVolumeSession"
        const val URI_GET_VOLUME = "ssap://audio/getVolume"
        const val URI_SET_VOLUME = "ssap://audio/setVolume"
        const val URI_SET_MUTE = "ssap://audio/setMute"
        const val URI_VOLUME_UP = "ssap://audio/volumeUp"
        const val URI_VOLUME_DOWN = "ssap://audio/volumeDown"
    }

    private val lock = Any()
    private var ws: WebSocket? = null
    private var registered = false
    private var closed = false
    private var endpointIndex = 0
    private var subscriptionId: String? = null

    /** The id of the setVolume awaiting its response, or null when the line is free. */
    private var inFlightSetId: String? = null
    private var pendingVolume: Int? = null

    /** True once [onDisconnected] has fired for this session, so it fires exactly once. */
    private var reported = false

    /** Set by [close]: the caller tore the session down, so the socket going away is not news. */
    private var userClosed = false

    /** Connects to the TV. Safe to call once per instance; make a new instance to reconnect. */
    fun open() {
        connect(WebOsClient.endpointsFor(ip)[endpointIndex])
    }

    /** Drops the socket without reporting a disconnect - the caller asked for it. */
    fun close() {
        val socket: WebSocket?
        val graceful: Boolean
        synchronized(lock) {
            userClosed = true
            closed = true
            socket = ws
            ws = null
            graceful = registered
        }
        socket?.let { WebOsClient.release(it, graceful) }
    }

    val isConnected: Boolean
        get() = synchronized(lock) { registered && !closed && ws != null }

    /** Sets the volume (0-100). Rapid calls collapse into the latest value. */
    fun setVolume(volume: Int) {
        val v = volume.coerceIn(0, 100)
        synchronized(lock) {
            if (inFlightSetId != null) {
                pendingVolume = v
                return
            }
            inFlightSetId = send(URI_SET_VOLUME, JSONObject().put("volume", v))
        }
    }

    fun setMute(muted: Boolean) {
        synchronized(lock) { send(URI_SET_MUTE, JSONObject().put("mute", muted)) }
    }

    fun volumeUp() {
        synchronized(lock) { send(URI_VOLUME_UP, JSONObject()) }
    }

    fun volumeDown() {
        synchronized(lock) { send(URI_VOLUME_DOWN, JSONObject()) }
    }

    // ---- wire ---------------------------------------------------------------

    /** Caller holds [lock]. Returns the request id, or null if there is no registered socket. */
    private fun send(uri: String, payload: JSONObject, subscribe: Boolean = false): String? {
        val socket = ws ?: return null
        if (!registered || closed) return null
        val id = UUID.randomUUID().toString()
        val req = JSONObject().apply {
            put("type", if (subscribe) "subscribe" else "request")
            put("id", id)
            put("uri", uri)
            put("payload", payload)
        }
        socket.send(req.toString())
        return id
    }

    private fun connect(endpoint: WebOsClient.Endpoint) {
        DebugLog.log(TAG, "connecting ${endpoint.url}")
        val client = WebOsClient.clientFor(endpoint.secure)
        val request = Request.Builder().url(endpoint.url).build()
        val socket = try {
            client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val payload = JSONObject().apply {
                        put("forcePairing", false)
                        put("pairingType", "PROMPT")
                        put("manifest", WebOsClient.manifest())
                        put("client-key", clientKey)
                    }
                    val msg = JSONObject().apply {
                        put("type", "register")
                        put("id", UUID.randomUUID().toString())
                        put("payload", payload)
                    }
                    webSocket.send(msg.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(webSocket, endpoint, text)

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    DebugLog.log(TAG, "${endpoint.url}: onClosed code=$code reason=$reason")
                    disconnected("TV closed the connection", unpaired = false)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val wasRegistered = synchronized(lock) { registered }
                    DebugLog.log(TAG, "${endpoint.url}: onFailure - ${t.javaClass.simpleName}: ${t.message}")
                    if (!wasRegistered && tryNextEndpoint()) return
                    disconnected("${t.javaClass.simpleName}: ${t.message}", unpaired = false)
                }
            })
        } catch (e: Exception) {
            DebugLog.log(TAG, "${endpoint.url}: exception opening socket - ${e.javaClass.simpleName}: ${e.message}")
            if (!tryNextEndpoint()) disconnected(e.message ?: "could not open socket", unpaired = false)
            return
        }
        synchronized(lock) {
            if (closed) {
                WebOsClient.release(socket, graceful = false)
            } else {
                ws = socket
            }
        }
    }

    /** ws:// refused before registering -> try wss://. Returns false when there is nothing left to try. */
    private fun tryNextEndpoint(): Boolean {
        synchronized(lock) {
            if (closed) return false
            val endpoints = WebOsClient.endpointsFor(ip)
            if (endpointIndex + 1 >= endpoints.size) return false
            endpointIndex++
            ws = null
        }
        connect(WebOsClient.endpointsFor(ip)[endpointIndex])
        return true
    }

    private fun handleMessage(webSocket: WebSocket, endpoint: WebOsClient.Endpoint, text: String) {
        val resp = try { JSONObject(text) } catch (e: Exception) { return }
        val type = resp.optString("type")
        val id = resp.optString("id")
        var notifyConnected = false
        var volumeUpdate: Pair<Int, Boolean>? = null
        var refused: String? = null
        synchronized(lock) {
            if (closed) return
            when {
                type == "registered" && !registered -> {
                    registered = true
                    notifyConnected = true
                    subscriptionId = send(URI_GET_VOLUME, JSONObject(), subscribe = true)
                    DebugLog.log(TAG, "${endpoint.url}: registered, subscribed to volume")
                }
                !registered && type == "response" -> {
                    // A pairing prompt where "registered" belongs: the stored key is no longer trusted.
                    DebugLog.log(TAG, "${endpoint.url}: TV asked to pair again")
                    refused = "TV no longer recognizes this app"
                }
                !registered && type == "error" -> {
                    DebugLog.log(TAG, "${endpoint.url}: register error: ${resp.optString("error", text)}")
                    refused = "TV refused: ${resp.optString("error", text)}"
                }
                type == "response" || type == "error" -> {
                    if (id == subscriptionId) {
                        volumeUpdate = parseVolume(resp.optJSONObject("payload"))
                        if (volumeUpdate == null) DebugLog.log(TAG, "${endpoint.url}: volume payload not understood: $text")
                    } else if (id == inFlightSetId) {
                        inFlightSetId = null
                        if (type == "error") DebugLog.log(TAG, "${endpoint.url}: setVolume error: ${resp.optString("error", text)}")
                        val next = pendingVolume
                        pendingVolume = null
                        if (next != null) inFlightSetId = send(URI_SET_VOLUME, JSONObject().put("volume", next))
                    } else if (type == "error") {
                        DebugLog.log(TAG, "${endpoint.url}: error: ${resp.optString("error", text)}")
                    }
                }
            }
        }
        if (notifyConnected) listener.onConnected(this)
        volumeUpdate?.let { (v, m) -> listener.onVolume(this, v, m) }
        refused?.let { reason ->
            // Registration was refused; tell the UI once and drop the socket. A
            // "response" in place of "registered" is the TV's pairing prompt, i.e.
            // our key is stale - the one case where "reconnect later" is pointless.
            disconnected(reason, unpaired = type == "response")
            WebOsClient.release(webSocket, graceful = true)
        }
    }

    /**
     * Older firmware answers {"volume":12,"muted":false}; newer webOS nests it as
     * {"volumeStatus":{"volume":12,"muteStatus":false}}. Accept both.
     */
    private fun parseVolume(payload: JSONObject?): Pair<Int, Boolean>? {
        payload ?: return null
        val status = payload.optJSONObject("volumeStatus") ?: payload
        if (!status.has("volume")) return null
        val volume = status.optInt("volume", -1).takeIf { it >= 0 } ?: return null
        val muted = when {
            status.has("muteStatus") -> status.optBoolean("muteStatus", false)
            status.has("muted") -> status.optBoolean("muted", false)
            payload.has("muted") -> payload.optBoolean("muted", false)
            else -> false
        }
        return volume to muted
    }

    private fun disconnected(reason: String, unpaired: Boolean) {
        val report: Boolean
        synchronized(lock) {
            report = !reported && !userClosed
            reported = true
            ws = null
            registered = false
            closed = true
            inFlightSetId = null
            pendingVolume = null
        }
        if (report) listener.onDisconnected(this, reason, unpaired)
    }
}
