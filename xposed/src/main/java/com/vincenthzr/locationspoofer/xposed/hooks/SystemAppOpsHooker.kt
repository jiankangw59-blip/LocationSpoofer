package com.vincenthzr.locationspoofer.xposed.hooks

import android.app.AppOpsManager
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemClassLocator
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers

/** Mock-op response adaptation after native validation; stored modes and other ops stay intact. */
internal fun LocationHooker.hookSystemAppOpsService(classLoader: ClassLoader) {
    val service = SystemClassLocator.locate(SystemComponent.APPOPS_SERVICE, classLoader)
    if (service != null && hookedCallbackClasses.putIfAbsent(service, true) == null) {
        for (name in MOCK_OPERATION_QUERIES) {
            XposedHelpers.hookAllMethods(service, name) { chain, method ->
                // Run native UID/package validation first, including exceptions.
                val original = proceedNativeAppOps(chain)
                if (method !is java.lang.reflect.Method || method.returnType != Int::class.javaPrimitiveType ||
                    chain.args.firstOrNull() != MOCK_LOCATION_OP || original != AppOpsManager.MODE_ALLOWED) {
                    return@hookAllMethods original
                }
                val config = readConfig() ?: return@hookAllMethods original
                if (isAppOpsBinderRequest() && SystemHookUtils.isTargetCaller(chain.thisObject, null, config)) AppOpsManager.MODE_IGNORED
                else original
            }
        }
        for (name in MOCK_NOTE_OPERATIONS) {
            XposedHelpers.hookAllMethods(service, name) { chain, _ ->
                val original = proceedNativeAppOps(chain)
                val config = readConfig()
                if (chain.args.firstOrNull() == MOCK_LOCATION_OP && config != null && isAppOpsBinderRequest() &&
                    SystemHookUtils.isTargetCaller(chain.thisObject, null, config)) projectMockNotedOp(original)
                else original
            }
        }
    }
    // SettingsProvider is loaded by its package class loader, not the system service loader.
    vendorExtraHooks += SystemMockSettings(this, classLoader)
    // Android 16 caches raw modes in Binder clients. Invalidate only when the simulation
    // eligibility changes; no stored mode changes and no polling/coordinate refresh invalidations.
    val invalidate = runCatching { AppOpsManager::class.java.getDeclaredMethod("invalidateAppOpModeCache")
        .apply { isAccessible = true } }.getOrNull()
    val previous = java.util.concurrent.atomic.AtomicReference<String?>(null)
    if (invalidate != null) vendorExtraHooks += observeConfigChanges {
        val config = readConfig() ?: return@observeConfigChanges
        val signature = "${config.optBoolean("active")}|${config.optBoolean("system_hook_global_mode")}|${config.optJSONArray("system_hook_packages")}"
        if (previous.getAndSet(signature) != signature) {
            try {
                invalidate.invoke(null)
                HookStatus.workEvent("mock_appop_cache_invalidations")
            } catch (error: Throwable) { HookStatus.error("AppOps cache invalidation", error) }
        }
    }
}

internal const val MOCK_LOCATION_OP = 58
internal val MOCK_OPERATION_QUERIES = setOf("checkOperation", "checkOperationRaw", "checkAudioOperation",
    "checkOperationForDevice", "checkOperationRawForDevice")
internal val MOCK_NOTE_OPERATIONS = setOf("noteOperation", "noteOperationForDevice")

/** Incoming client RPCs are separate from native local checks made while another Binder caller's
 * identity is still present. Preserve real authorization for those system-service calls. */
private val nativeAppOpsDepth = ThreadLocal.withInitial { 0 }
private val appOpsServiceNames = setOf("com.android.server.appop.AppOpsService", "com.android.server.AppOpsService")
private val appOpsPublicNames = MOCK_OPERATION_QUERIES + MOCK_NOTE_OPERATIONS + "onTransact"

internal fun proceedNativeAppOps(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
    val depth = nativeAppOpsDepth.get()
    nativeAppOpsDepth.set(depth + 1)
    try { return chain.proceed(chain.args.toTypedArray()) }
    finally { if (depth == 0) nativeAppOpsDepth.remove() else nativeAppOpsDepth.set(depth) }
}

internal fun isAppOpsBinderRequest(): Boolean {
    if (nativeAppOpsDepth.get() != 0) return false
    val stack = Thread.currentThread().stackTrace
    return stack.any { it.className == "com.android.internal.app.IAppOpsService\$Stub" && it.methodName == "onTransact" } &&
        stack.none { it.className in appOpsServiceNames && it.methodName !in appOpsPublicNames }
}

/** Modern noteOperation returns SyncNotedAppOp, not an integer. Never mutate the native object. */
internal fun projectMockNotedOp(original: Any?): Any? {
    if (original is Int) return if (original == AppOpsManager.MODE_ALLOWED) AppOpsManager.MODE_IGNORED else original
    if (original?.javaClass?.name != "android.app.SyncNotedAppOp") return original
    return try {
        if (XposedHelpers.callMethod(original, "getOpMode") != AppOpsManager.MODE_ALLOWED) return original
        original.javaClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            String::class.java, String::class.java).newInstance(AppOpsManager.MODE_IGNORED, MOCK_LOCATION_OP,
            XposedHelpers.callMethod(original, "getAttributionTag"), XposedHelpers.callMethod(original, "getPackageName"))
    } catch (error: Throwable) {
        HookStatus.error("AppOps noted result adaptation", error)
        original
    }
}
