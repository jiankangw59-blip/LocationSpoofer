package com.vincenthzr.locationspoofer.xposed.hooks

import android.os.IBinder
import android.os.IInterface
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

/** Recipient-specific barrier spanning old-hook removal and native subscription rediscovery. */
internal class CallbackDeliveryHandoff(
    callbacks: Array<Any>,
    claimedLifecycle: (IBinder, String) -> Boolean
) {
    private val targets = ConcurrentHashMap<IBinder, Any>()
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    init {
        callbacks.forEach { callback -> binder(callback)?.let { targets[it] = callback } }
        val names = setOf("onScanResult", "onBatchScanResults", "onFoundOrLost",
            "onSvStatusChanged", "onGnssStarted", "onGnssStopped", "onFirstFix", "onNmeaReceived", "onNmeaMessage",
            "onGnssMeasurementsReceived", "onGnssNavigationMessageReceived", "onCellInfoChanged", "onCellLocationChanged",
            "onServiceStateChanged", "onSignalStrengthsChanged", "onDisplayInfoChanged", "onDataConnectionStateChanged")
        callbacks.map { it.javaClass }.distinct().forEach { type ->
            type.declaredMethods.map { it.name }.distinct().filter { it in names }.forEach { name ->
                handles += XposedHelpers.hookAllMethods(type, name) { chain, _ ->
                    if (binder(chain.thisObject)?.let { targets.containsKey(it) && !claimedLifecycle(it, name) } == true) null
                    else chain.proceed(chain.args.toTypedArray())
                }
            }
        }
    }

    @Synchronized fun release(callback: Any) {
        binder(callback)?.let { targets.remove(it) }
        if (targets.isEmpty()) {
            handles.forEach { runCatching { it.unhook() } }
            handles.clear()
        }
    }

    fun remaining(): Array<Any> = targets.values.toTypedArray()
    private fun binder(value: Any?): IBinder? = (value as? IInterface)?.asBinder() ?: value as? IBinder
}
