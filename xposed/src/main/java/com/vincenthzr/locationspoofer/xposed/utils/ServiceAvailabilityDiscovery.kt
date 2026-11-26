package com.vincenthzr.locationspoofer.xposed.utils

/** An initial reconciliation and follow-up events share one discovery pass at a time. */
internal class ServiceAvailabilityDiscovery(private val discover: () -> Unit) {
    private var ready = false
    private var running = false
    private var pending = false
    private var closed = false

    fun start() = signal(initial = true)

    fun request() = signal(initial = false)

    private fun signal(initial: Boolean) {
        synchronized(this) {
            if (closed || (initial && ready)) return
            if (initial) ready = true
            if (!ready) return
            if (running) {
                pending = true
                return
            }
            running = true
        }
        try {
            while (true) {
                discover()
                synchronized(this) {
                    if (!pending) {
                        running = false
                        return
                    }
                    pending = false
                }
            }
        } catch (error: Throwable) {
            synchronized(this) {
                pending = false
                running = false
            }
            throw error
        }
    }

    @Synchronized fun closeIfIdle(): Boolean {
        if (running) return false
        closed = true
        return true
    }
}
