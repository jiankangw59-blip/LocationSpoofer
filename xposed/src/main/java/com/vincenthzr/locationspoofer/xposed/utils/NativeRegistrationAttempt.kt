package com.vincenthzr.locationspoofer.xposed.utils

internal class NativeRegistration<T : Any>(val value: T) {
    private var previous: NativeRegistration<T>? = null
    private var rejected = false

    fun remember(previous: NativeRegistration<T>?) {
        this.previous = previous
    }

    fun finish(rejected: Boolean): NativeRegistration<T>? {
        this.rejected = rejected
        if (!rejected) previous = null
        // A concurrent or enclosing call may have failed while this entry was current.
        while (previous?.rejected == true) previous = previous?.previous
        return previous
    }
}

internal class NativeRegistrationAttempt(private val lock: Any) {
    private val updates = mutableListOf<(Boolean) -> Unit>()

    fun <T : Any> track(
        value: T,
        put: (NativeRegistration<T>) -> NativeRegistration<T>?,
        rollback: (NativeRegistration<T>, NativeRegistration<T>?) -> Unit
    ) {
        synchronized(lock) {
            val entry = NativeRegistration(value)
            entry.remember(put(entry))
            updates.add { rejected ->
                val previous = entry.finish(rejected)
                if (rejected) rollback(entry, previous)
            }
        }
    }

    fun proceed(nativeCall: () -> Any?): Any? {
        val result = try {
            nativeCall()
        } catch (failure: Throwable) {
            finish(true)
            throw failure
        }
        finish(result is Boolean && !result)
        return result
    }

    fun accept() = finish(false)

    private fun finish(rejected: Boolean) {
        synchronized(lock) {
            for (update in updates.asReversed()) runCatching { update(rejected) }
        }
    }
}
