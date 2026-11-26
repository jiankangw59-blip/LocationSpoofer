package com.vincenthzr.locationspoofer.xposed.utils

import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap

/** Each map represents one subscription role, with its own death link for each current record. */
internal class BinderRegistrationMap<V : Any> private constructor(
    private val records: ConcurrentHashMap<IBinder, V>,
    private val deaths: BinderDeathRegistry,
    private val onRemoved: (V) -> Unit,
    private val changed: () -> Unit
) : Map<IBinder, V> by records {
    constructor(
        deaths: BinderDeathRegistry = ModuleBinderDeaths,
        onRemoved: (V) -> Unit = {},
        changed: () -> Unit = {}
    ) : this(ConcurrentHashMap(), deaths, onRemoved, changed)

    private class DeathLink(val token: Any, val handle: AutoCloseable)

    private val lock = Any()
    private val links = HashMap<IBinder, DeathLink>()

    operator fun set(binder: IBinder, value: V) {
        put(binder, value)
    }

    fun put(binder: IBinder, value: V): V? {
        val previous = synchronized(lock) {
            val current = records[binder]
            if (current === value) return current
            if (!install(binder, value)) return current
            current
        }
        notifyChanged()
        return previous
    }

    fun registerIfAbsent(binder: IBinder, value: V): Boolean {
        val installed = synchronized(lock) {
            if (records.containsKey(binder)) return false
            install(binder, value)
        }
        if (installed) notifyChanged()
        return installed
    }

    fun replace(binder: IBinder, previous: V, value: V): Boolean {
        synchronized(lock) {
            if (records[binder] !== previous) return false
            if (previous === value) return true
            if (!install(binder, value)) return false
        }
        notifyChanged()
        return true
    }

    fun rollback(binder: IBinder, rejected: V, previous: V?) {
        if (previous == null || !replace(binder, rejected, previous)) {
            remove(binder, rejected)
        }
    }

    fun remove(binder: IBinder, expected: V? = null): V? {
        val removed = synchronized(lock) {
            val current = records[binder] ?: return null
            if (expected != null && current !== expected) return null
            discard(binder)
            current
        }
        notifyChanged()
        return removed
    }

    fun clear() {
        synchronized(lock) {
            if (records.isEmpty()) return
            records.keys.toList().forEach { discard(it) }
        }
        notifyChanged()
    }

    private fun install(binder: IBinder, value: V): Boolean {
        // Obtain the replacement link before giving up the current one.
        val token = Any()
        val handle = runCatching { deaths.watch(binder) { removeDead(binder, token) } }.getOrNull()
            ?: return false
        val previous = records.put(binder, value)
        links.put(binder, DeathLink(token, handle))?.handle?.close()
        if (previous != null) onRemoved(previous)
        return true
    }

    private fun removeDead(binder: IBinder, token: Any) {
        synchronized(lock) {
            if (links[binder]?.token !== token) return
            discard(binder)
        }
        notifyChanged()
    }

    private fun discard(binder: IBinder) {
        val removed = records.remove(binder) ?: return
        links.remove(binder)?.handle?.close()
        onRemoved(removed)
    }

    private fun notifyChanged() {
        runCatching { changed() }
    }
}
