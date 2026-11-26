package com.vincenthzr.locationspoofer.xposed.utils

/** Bounded, atomic event throttling; no worker and no wall-clock dependency. */
internal class EventLogLimiter(private val nowMillis: () -> Long, private val capacity: Int = 128) {
    init { require(capacity > 0) }
    private val last = LinkedHashMap<String, Long>(capacity, 0.75f, true)

    @Synchronized fun allow(key: String, intervalMillis: Long): Boolean {
        require(intervalMillis >= 0)
        val now = nowMillis()
        val previous = last[key]
        if (previous != null && now >= previous && now - previous < intervalMillis) return false
        if (!last.containsKey(key) && last.size >= capacity) last.remove(last.keys.first())
        last[key] = now
        return true
    }
}
