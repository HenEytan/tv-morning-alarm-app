package com.henos.tvalarm

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramSocket
import java.net.Socket
import javax.net.SocketFactory

/**
 * Pins outgoing sockets to the Wi-Fi network.
 *
 * The TV lives on the local LAN and is reachable from nowhere else, but Android
 * does not guarantee a socket leaves over Wi-Fi. When Wi-Fi is asleep, or the
 * "switch to mobile data on poor Wi-Fi" behaviour kicks in, the kernel happily
 * routes a connection to 192.168.1.x out of the cellular interface, where it can
 * only ever time out. The debug log caught exactly that: connect attempts sourced
 * "from /10.97.237.155" (a carrier-NAT address) instead of the phone's own
 * 192.168.1.125 Wi-Fi address, followed by ETIMEDOUT and EHOSTUNREACH.
 *
 * Every lookup here fails soft: with no Wi-Fi network to be found we return null
 * and the caller keeps the default routing, which is no worse than before.
 */
object TvNetwork {

    private const val TAG = "TvNetwork"

    /** The Wi-Fi network this device is currently attached to, or null if there is none. */
    fun wifiNetwork(): Network? {
        val ctx = DebugLog.appContext ?: return null
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val isWifi = { n: Network ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            @Suppress("DEPRECATION")
            cm.activeNetwork?.takeIf(isWifi) ?: cm.allNetworks.firstOrNull(isWifi)
        } catch (e: Exception) {
            DebugLog.log(TAG, "wifiNetwork: unavailable - ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Socket factory bound to Wi-Fi, for handing to OkHttp. Null when Wi-Fi isn't available. */
    fun socketFactory(): SocketFactory? = wifiNetwork()?.socketFactory

    /** An unconnected TCP socket bound to Wi-Fi where possible, otherwise a plain one. */
    fun newSocket(): Socket = try {
        socketFactory()?.createSocket() ?: Socket()
    } catch (e: Exception) {
        DebugLog.log(TAG, "newSocket: falling back to default routing - ${e.javaClass.simpleName}: ${e.message}")
        Socket()
    }

    /** Binds an unconnected [socket] to Wi-Fi. Returns whether the binding took effect. */
    fun bind(socket: DatagramSocket): Boolean = try {
        wifiNetwork()?.let { it.bindSocket(socket); true } ?: false
    } catch (e: Exception) {
        DebugLog.log(TAG, "bind: could not pin datagram socket to Wi-Fi - ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}
