package com.vincenthzr.locationspoofer.xposed.hooks

/** Published maps contain immutable copies of one native provider's registrations. */
internal class NativeSignalSnapshots<K, V> : AutoCloseable {
    private data class Open<K, V>(
        val statuses: Map<K, V> = emptyMap(),
        val nmeas: Map<K, V> = emptyMap()
    )

    @Volatile private var open: Open<K, V>? = Open()

    val closed: Boolean get() = open == null

    fun entries(nmea: Boolean): Map<K, V> {
        val snapshot = open ?: return emptyMap()
        return if (nmea) snapshot.nmeas else snapshot.statuses
    }

    fun refresh(nativeLock: Any, nmea: Boolean, capture: () -> Map<K, V>) {
        synchronized(nativeLock) {
            if (closed) return
            // Publish before releasing the framework lock so a later inactivation or
            // removal cannot be overwritten by an older explicit refresh.
            val snapshot = try {
                capture()
            } catch (error: Throwable) {
                publish(nmea, emptyMap())
                throw error
            }
            publish(nmea, snapshot)
        }
    }

    // Only refresh takes both locks, always with the framework lock first.
    @Synchronized
    private fun publish(nmea: Boolean, snapshot: Map<K, V>) {
        val previous = open ?: return
        open = if (nmea) previous.copy(nmeas = snapshot) else previous.copy(statuses = snapshot)
    }

    @Synchronized
    fun clear() {
        if (open != null) open = Open()
    }

    @Synchronized
    override fun close() {
        open = null
    }
}
