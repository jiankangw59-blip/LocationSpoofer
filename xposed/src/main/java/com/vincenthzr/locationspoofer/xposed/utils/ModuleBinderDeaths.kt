package com.vincenthzr.locationspoofer.xposed.utils

import android.os.IBinder

/** Unlink module-owned callbacks before retiring their classloader. */
internal open class BinderDeathRegistry : AutoCloseable {
    private val recipients = LinkedHashMap<IBinder.DeathRecipient, IBinder>()
    private var closed = false

    @Synchronized
    fun watch(binder: IBinder, onDeath: () -> Unit): AutoCloseable? {
        if (closed) return null
        val recipient = object : IBinder.DeathRecipient {
            override fun binderDied() {
                val invoke = synchronized(this@BinderDeathRegistry) {
                    recipients.remove(this) != null && !closed
                }
                if (invoke) onDeath()
            }
        }
        recipients[recipient] = binder
        try {
            binder.linkToDeath(recipient, 0)
        } catch (error: Throwable) {
            recipients.remove(recipient)
            runCatching { binder.unlinkToDeath(recipient, 0) }
            throw error
        }
        if (recipient !in recipients) return null
        return AutoCloseable {
            if (synchronized(this) { recipients.remove(recipient) != null }) {
                runCatching { binder.unlinkToDeath(recipient, 0) }
            }
        }
    }

    override fun close() {
        val links = synchronized(this) {
            closed = true
            recipients.toMap().also { recipients.clear() }
        }
        links.forEach { (recipient, binder) -> runCatching { binder.unlinkToDeath(recipient, 0) } }
    }
}

internal object ModuleBinderDeaths : BinderDeathRegistry()
