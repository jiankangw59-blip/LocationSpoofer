package com.vincenthzr.locationspoofer.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

/** Single-thread-confined. A selection starts the lookup; starting the simulator is a separate action. */
class AutoPointHeightRequests<T : Any>(private val scope: CoroutineScope, private val debounceMs: Long = 450L) {
    private class Entry<T : Any>(val key: String) {
        lateinit var job: Deferred<T?>
        var succeeded = false
    }
    private var current: Entry<T>? = null

    fun invalidate() {
        val old = current
        current = null
        old?.job?.cancel()
    }

    fun select(key: String, load: suspend () -> T?) {
        if (current?.key != key) start(key, debounceMs, load)
    }

    /** If the selected lookup failed, an explicit retry may try again. Never double-fetch a pending point. */
    suspend fun await(key: String, load: suspend () -> T?): T? {
        val existing = current
        val entry = if (existing != null && existing.key == key &&
            (existing.job.isActive || existing.succeeded)) existing else start(key, 0L, load)
        return try {
            entry.job.await().takeIf { current === entry }
        } catch (cancelled: CancellationException) {
            // Changing targets cancels this request, not the caller's own job.
            if (current === entry) throw cancelled else null
        }
    }

    private fun start(key: String, settle: Long, load: suspend () -> T?): Entry<T> {
        invalidate()
        val entry = Entry<T>(key)
        entry.job = scope.async(start = CoroutineStart.LAZY) {
            if (settle > 0L) delay(settle)
            if (current !== entry) return@async null
            val value = load()
            if (current !== entry) null else value.also { entry.succeeded = it != null }
        }
        current = entry
        entry.job.start()
        return entry
    }
}
