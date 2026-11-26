package com.vincenthzr.locationspoofer.xposed.utils

import java.util.concurrent.ConcurrentHashMap

/** Registration mutations notify their owner; reads never arrange work. */
internal class DemandMap<K : Any, V : Any>(private val changed: () -> Unit) : ConcurrentHashMap<K, V>() {
    private fun notifyChanged() { runCatching { changed() } }
    override fun put(key: K, value: V): V? = super.put(key, value).also { notifyChanged() }
    override fun putIfAbsent(key: K, value: V): V? = super.putIfAbsent(key, value).also { if (it == null) notifyChanged() }
    override fun replace(key: K, value: V): V? = super.replace(key, value).also { if (it != null) notifyChanged() }
    override fun replace(key: K, oldValue: V, newValue: V): Boolean = super.replace(key, oldValue, newValue).also { if (it) notifyChanged() }
    override fun remove(key: K): V? = super.remove(key).also { if (it != null) notifyChanged() }
    override fun remove(key: K, value: V): Boolean = super.remove(key, value).also { if (it) notifyChanged() }
    override fun clear() {
        val hadEntries = isNotEmpty()
        super.clear()
        if (hadEntries) notifyChanged()
    }
    override fun putAll(from: Map<out K, V>) { super.putAll(from); if (from.isNotEmpty()) notifyChanged() }
}
