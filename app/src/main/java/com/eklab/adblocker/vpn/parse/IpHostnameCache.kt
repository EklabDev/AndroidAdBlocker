package com.eklab.adblocker.vpn.parse

/**
 * Thread-safe bounded LRU map from IP address string to hostname.
 *
 * Filled from DNS answers so that flows to previously resolved IPs can be
 * attributed a hostname even when the flow itself carries no SNI. The eldest
 * (least recently used) entries are evicted once [maxCapacity] is exceeded.
 */
class IpHostnameCache(private val maxCapacity: Int = DEFAULT_CAPACITY) {

    private val map = object : LinkedHashMap<String, String>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > maxCapacity
    }

    /** Records that [ip] resolved to [hostname]. Blank inputs are ignored. */
    fun put(ip: String, hostname: String) {
        if (ip.isBlank() || hostname.isBlank()) return
        synchronized(map) { map[ip] = hostname }
    }

    /** Returns the hostname last seen for [ip], or `null` if unknown. */
    fun get(ip: String): String? = synchronized(map) { map[ip] }

    val size: Int get() = synchronized(map) { map.size }

    fun clear() = synchronized(map) { map.clear() }

    companion object {
        const val DEFAULT_CAPACITY = 4096
    }
}
