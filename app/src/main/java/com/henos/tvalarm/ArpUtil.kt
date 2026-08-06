package com.henos.tvalarm

import java.io.File

object ArpUtil {

    /** Best-effort: reads /proc/net/arp for the MAC matching an IP. Returns null if unavailable. */
    fun lookupMac(ip: String): String? {
        return try {
            val lines = File("/proc/net/arp").readLines()
            for (line in lines.drop(1)) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 4 && parts[0] == ip) {
                    val mac = parts[3]
                    if (mac.isNotBlank() && mac != "00:00:00:00:00:00") {
                        return mac.uppercase()
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
