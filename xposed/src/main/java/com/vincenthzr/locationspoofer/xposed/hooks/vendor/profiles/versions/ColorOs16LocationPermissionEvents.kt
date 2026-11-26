package com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.versions

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import com.vincenthzr.locationspoofer.xposed.hooks.SystemHookUtils
import io.github.libxposed.api.XposedInterface

/** Forward settled package events dropped by the inspected ColorOS location watcher. */
internal class ColorOs16LocationPermissionEvents(
    private val module: LocationHooker,
    private val loader: ClassLoader
) : AutoCloseable {
    private var handle: XposedInterface.HookHandle? = null
    private var forwarder: LocationPermissionEventForwarder<Any>? = null

    fun install() {
        if (Build.FINGERPRINT != MATCHED_FINGERPRINT) return
        try {
            val helper = Class.forName(HELPER_CLASS, false, loader)
            val listener = Class.forName("$HELPER_CLASS\$\$ExternalSyntheticLambda1", false, loader)
            val callback = listener.getDeclaredMethod("onOpChanged", String::class.java, String::class.java)
            check(callback.returnType == Void.TYPE &&
                AppOpsManager.OnOpChangedListener::class.java.isAssignableFrom(listener))
            val ownerField = listener.getDeclaredField("f\$0").apply { isAccessible = true }
            check(ownerField.type == helper)
            val contextField = helper.getDeclaredField("mContext").apply { isAccessible = true }
            val notify = helper.superclass.getDeclaredMethod("notifyAppOpChanged", String::class.java)
                .apply { isAccessible = true }
            check(notify.returnType == Void.TYPE)
            val handler = Class.forName("com.android.server.FgThread", false, loader)
                .getDeclaredMethod("getHandler").invoke(null) as Handler
            val token = Any()
            val events = LocationPermissionEventForwarder<Any>(
                post = { task -> handler.postAtTime(task, token, SystemClock.uptimeMillis()) },
                clear = { handler.removeCallbacksAndMessages(token) },
                active = { !module.retiringGeneration && module.readConfig()?.optBoolean("active", false) == true },
                target = { owner, pkg ->
                    val context = contextField.get(owner) as Context
                    val uid = try {
                        context.packageManager.getPackageUid(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                    val config = module.readConfig()
                    // The callback runs as system_server, so only a resolved app UID may be used.
                    uid != null && uid > 1000 && config != null &&
                        SystemHookUtils.isTargetCaller(owner, pkg, config, uid)
                },
                notifyNative = { owner, pkg -> notify.invoke(owner, pkg) },
                onReceived = { HookStatus.workEvent("coloros_location_permission_package_event") },
                onForwarded = {
                    HookStatus.workEventAndRequestSnapshot("coloros_location_permission_forwarded")
                },
                onSkippedClosed = { HookStatus.workEvent("coloros_location_permission_skipped_closed") },
                onFailure = ::reportForwardingFailure
            )
            forwarder = events
            handle = module.hook(callback).intercept { chain ->
                val original = chain.proceed()
                val op = chain.args.getOrNull(0) as? String
                val pkg = chain.args.getOrNull(1) as? String
                events.onChanged(op, pkg) { ownerField.get(chain.thisObject) }
                original
            }
        } catch (error: Throwable) {
            close()
            HookStatus.error("ColorOS16 location permission callback", error)
        }
    }

    private fun reportForwardingFailure() {
        runCatching {
            HookStatus.workEventAndRequestSnapshot("coloros_location_permission_forward_failed")
        }
    }

    override fun close() {
        forwarder?.close()
        forwarder = null
        val oldHandle = handle
        handle = null
        if (oldHandle != null) runCatching { oldHandle.unhook() }
            .onFailure { HookStatus.error("ColorOS16 location permission unhook", it) }
    }

    private companion object {
        const val HELPER_CLASS = "com.android.server.location.injector.SystemAppOpsHelper"
        const val MATCHED_FINGERPRINT =
            "OnePlus/PLQ110/OP6113L1:16/BP2A.250605.015/B.25ec003-5711f4-5711f5:user/release-keys"
    }
}

internal class LocationPermissionEventForwarder<T : Any>(
    private val post: (Runnable) -> Boolean,
    private val clear: () -> Unit,
    private val active: () -> Boolean,
    private val target: (T, String) -> Boolean,
    private val notifyNative: (T, String) -> Unit,
    private val onReceived: () -> Unit,
    private val onForwarded: () -> Unit,
    private val onSkippedClosed: () -> Unit,
    private val onFailure: () -> Unit
) : AutoCloseable {
    private val queueLock = Any()
    @Volatile private var closed = false

    fun onChanged(op: String?, pkg: String?, owner: () -> T?) {
        if (pkg.isNullOrEmpty() || !isLocationAppOp(op)) return
        try {
            synchronized(queueLock) {
                if (isClosed() || !active()) return
                val helper = checkNotNull(owner())
                onReceived()
                if (!post(Runnable { refresh(helper, pkg) })) onFailure()
            }
        } catch (_: Throwable) {
            onFailure()
        }
    }

    private fun refresh(owner: T, pkg: String) {
        try {
            if (isClosed() || !active() || !target(owner, pkg)) return
            // Final admission shares the close lock. Admitted work may finish after close.
            val admitted = synchronized(queueLock) { !isClosed() && active() }
            if (!admitted) return
            notifyNative(owner, pkg)
            onForwarded()
        } catch (_: Throwable) {
            onFailure()
        }
    }

    private fun isClosed(): Boolean {
        val alreadyClosed = closed
        if (alreadyClosed) onSkippedClosed()
        return alreadyClosed
    }

    override fun close() {
        synchronized(queueLock) {
            if (closed) return
            closed = true
            try {
                clear()
            } catch (_: Throwable) {
                onFailure()
            }
        }
    }
}

private fun isLocationAppOp(op: String?): Boolean =
    op == AppOpsManager.OPSTR_COARSE_LOCATION || op == AppOpsManager.OPSTR_FINE_LOCATION
