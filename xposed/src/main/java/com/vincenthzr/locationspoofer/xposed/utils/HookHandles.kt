package com.vincenthzr.locationspoofer.xposed.utils

internal fun <T> installHookHandles(
    targets: Iterable<T>,
    onUnhookError: (Throwable) -> Unit,
    install: (T) -> AutoCloseable
): AutoCloseable {
    val handles = mutableListOf<AutoCloseable>()
    val closeable = object : AutoCloseable {
        override fun close() {
            val owned = synchronized(handles) { handles.toList().also { handles.clear() } }
            for (handle in owned.asReversed()) {
                try {
                    handle.close()
                } catch (error: Throwable) {
                    runCatching { onUnhookError(error) }
                }
            }
        }
    }
    try {
        for (target in targets) handles += install(target)
    } catch (error: Throwable) {
        closeable.close()
        throw error
    }
    return closeable
}
