package com.vincenthzr.locationspoofer.xposed.utils

import android.content.SharedPreferences
import com.vincenthzr.locationspoofer.utils.FrameworkConfigChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus

/** All framework access and JSON decoding happen outside the hooked call path. */
internal class FrameworkConfigReceiver(
    private val getPreferences: () -> SharedPreferences,
    private val readFile: (String) -> String,
    private val onConfig: (FrameworkConfigSnapshot) -> Unit,
    private val onError: (Exception) -> Unit,
    private val retryDelays: LongArray = longArrayOf(250L, 1_000L, 3_000L)
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "LocationSpoofer-ConfigReceiver").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    @Volatile private var preferences: SharedPreferences? = null
    private var lastSnapshot: String? = null
    private var lastErrorAt = 0L
    private var retry: ScheduledFuture<*>? = null
    private var retryAttempt = 0
    private val queued = AtomicBoolean(false)
    // Keep a strong reference: SharedPreferences implementations may store weak listeners.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == FrameworkConfigChannel.SNAPSHOT_KEY) {
            requestRefresh()
        }
    }

    fun start() {
        // Module lifecycle initialization precedes hook installation. Hook reads remain memory-only.
        refresh()
    }

    /** Lifecycle hints repair only a failed connection, without sampling healthy preferences. */
    fun onHostEvent() {
        if (!closed && preferences == null) requestRefresh()
    }

    /** A real host lifecycle or configuration event can reopen a failed connection. */
    fun requestRefresh() {
        if (closed) return
        if (queued.compareAndSet(false, true)) {
            runCatching { executor.execute {
                queued.set(false)
                synchronized(this) {
                    if (!closed) {
                        retryAttempt = 0
                        retry?.cancel(false)
                        retry = null
                        HookStatus.workActive("config_receiver_retry", false)
                        refresh()
                    }
                }
            } }
                .onFailure { queued.set(false) }
        }
    }

    @Synchronized
    internal fun refresh() {
        if (closed) return
        HookStatus.workTick("config_receiver")
        var receivingPayload = false
        try {
            val prefs = preferences ?: getPreferences().also {
                it.registerOnSharedPreferenceChangeListener(listener)
                preferences = it
            }
            val text = prefs.getString(FrameworkConfigChannel.SNAPSHOT_KEY, null) ?: return
            receivingPayload = true
            if (text == lastSnapshot) return
            val snapshot = FrameworkConfigSnapshot.decode(text, readFile)
            if (closed) return
            onConfig(snapshot)
            lastSnapshot = text
            retry?.cancel(false)
            retry = null
            retryAttempt = 0
            HookStatus.workActive("config_receiver_retry", false)
        } catch (error: Exception) {
            // Keep the listener after a malformed/temporarily unreadable payload: otherwise a
            // later valid publication would be lost once bounded retries finish.
            if (!receivingPayload) {
                preferences?.let { runCatching { it.unregisterOnSharedPreferenceChangeListener(listener) } }
                preferences = null
            }
            val now = System.currentTimeMillis()
            if (now - lastErrorAt >= 60_000) {
                lastErrorAt = now
                runCatching { onError(error) }
            }
            if (!closed && retryAttempt < retryDelays.size && retry?.isDone != false) {
                val delay = retryDelays[retryAttempt++]
                HookStatus.workActive("config_receiver_retry", true)
                retry = executor.schedule({
                    synchronized(this) { retry = null }
                    refresh()
                    synchronized(this) {
                        if (retry == null) HookStatus.workActive("config_receiver_retry", false)
                    }
                }, delay, TimeUnit.MILLISECONDS)
            }
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        retry?.cancel(false)
        retry = null
        HookStatus.workActive("config_receiver_retry", false)
        executor.shutdownNow()
        preferences?.let { runCatching { it.unregisterOnSharedPreferenceChangeListener(listener) } }
        preferences = null
    }
}
