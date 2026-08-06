package com.henos.tvalarm

import android.content.Context
import android.net.wifi.WifiManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

data class DiscoveredTv(val name: String, val ip: String)

object SsdpDiscovery {

    private const val TAG = "SsdpDiscovery"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    /** Blocking. Call from a background thread. Scans the LAN for ~4s and returns discovered devices. */
    fun discover(context: Context, timeoutMs: Int = 4000): List<DiscoveredTv> {
        DebugLog.section("SSDP SCAN START (${timeoutMs}ms)")
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("tvalarm-ssdp-lock")
        lock.setReferenceCounted(true)
        lock.acquire()

        val foundIps = LinkedHashMap<String, DiscoveredTv>()
        try {
            val socket = DatagramSocket()
            socket.soTimeout = 1000
            val group = InetAddress.getByName("239.255.255.250")
            val searchMsg = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: ssdp:all\r\n\r\n"
            val sendData = searchMsg.toByteArray(Charsets.UTF_8)
            // Send a couple of times — UDP is unreliable and TVs are sometimes slow to answer.
            repeat(2) {
                socket.send(DatagramPacket(sendData, sendData.size, group, 1900))
                Thread.sleep(150)
            }
            DebugLog.log(TAG, "M-SEARCH broadcast sent, listening for responses...")

            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            var totalResponses = 0
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val ip = packet.address?.hostAddress ?: continue
                    totalResponses++
                    if (foundIps.containsKey(ip)) continue

                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val location = Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)
                    val server = Regex("(?im)^server:\\s*(.+)$").find(text)?.groupValues?.get(1) ?: ""

                    var friendlyName: String? = null
                    var isLg = server.contains("LG", ignoreCase = true) ||
                        server.contains("webOS", ignoreCase = true)

                    if (location != null) {
                        try {
                            val xml = fetchXml(location)
                            if (xml != null) {
                                friendlyName = Regex("<friendlyName>(.*?)</friendlyName>").find(xml)?.groupValues?.get(1)
                                val manufacturer = Regex("<manufacturer>(.*?)</manufacturer>").find(xml)?.groupValues?.get(1) ?: ""
                                if (manufacturer.contains("LG", ignoreCase = true)) isLg = true
                            }
                        } catch (e: Exception) {
                            DebugLog.log(TAG, "device $ip: failed fetching description XML from $location - ${e.message}")
                        }
                    }

                    DebugLog.log(TAG, "device $ip: server=\"$server\" location=$location friendlyName=$friendlyName classifiedAsLg=$isLg")

                    if (isLg) {
                        foundIps[ip] = DiscoveredTv(friendlyName ?: "LG TV", ip)
                    }
                } catch (e: SocketTimeoutException) {
                    // keep looping until deadline
                }
            }
            socket.close()
            DebugLog.log(TAG, "scan complete: $totalResponses total SSDP responses, ${foundIps.size} classified as LG TVs")
        } finally {
            lock.release()
        }
        return foundIps.values.toList()
    }

    private fun fetchXml(url: String): String? {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }
}
