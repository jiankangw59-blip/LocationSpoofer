@file:Suppress(
    "UNUSED_PARAMETER",
    "UNUSED_VARIABLE",
    "UNNECESSARY_NOT_NULL_ASSERTION",
    "DEPRECATION",
    "NAME_SHADOWING",
    "FunctionName",
    "PrivatePropertyName",
    "SpellCheckingInspection",
    "RedundantUnitReturnType",
    "RemoveRedundantQualifierName",
    "OPT_IN_USAGE",
    "unused",
    "UnusedImport"
)

package com.vincenthzr.locationspoofer.xposed.hooks

import android.util.Log
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.hooks.network.*
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemClassLocator
import com.vincenthzr.locationspoofer.xposed.utils.XposedBridge
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import org.json.JSONObject

private fun sysLog(msg: String) = XposedBridge.log(msg)

/**
 * 电话底层服务（PhoneInterfaceManager in com.android.phone）与 TelephonyRegistry (in system_server)
 * 基站小区拦截与伪造模块
 *
 * 核心原理：
 * 1. 全设备所有应用同步调用 TelephonyManager.getAllCellInfo() 与 getCellLocation()
 *    都会经由 ITelephony 远程 IPC 路由至 com.android.phone 中的 PhoneInterfaceManager。
 * 2. 异步基站监听（PhoneStateListener / TelephonyCallback）则通过 system_server 中的 TelephonyRegistry 分发。
 *
 * 本模块双管齐下：
 * - 在 com.android.phone 中挂载 PhoneInterfaceManager，拦截 getAllCellInfo / getCellLocation；
 * - 在 system_server 中挂载 TelephonyRegistry，按目标应用改写基站回调；
 * 派发与目标模拟坐标一致的 GSM/LTE/5G NR 基站小区数据，彻底消除基站与 GPS 的位置冲突。
 */

@Volatile
internal var isTelephonyServiceHooked = false

internal fun LocationHooker.hookSystemTelephonyService(classLoader: ClassLoader) {
    if (isTelephonyServiceHooked) return

    var phoneServiceClass = SystemClassLocator.locate(SystemComponent.TELEPHONY_PHONE_MANAGER, classLoader)

    if (phoneServiceClass == null) {
        try {
            val smClass = XposedHelpers.findClassIfExists("android.os.ServiceManager", classLoader)
            val binder = if (smClass != null) {
                XposedHelpers.callStaticMethod(smClass, "getService", "phone") as? android.os.IBinder
            } else null
            if (binder != null) {
                phoneServiceClass = binder.javaClass
                HookStatus.classFound(SystemComponent.TELEPHONY_PHONE_MANAGER, binder.javaClass, "ServiceManager.getService")
                sysLog("[SysCell] Captured PhoneInterfaceManager from ServiceManager.getService(\"phone\"): ${binder.javaClass}")
            }
        } catch (_: Throwable) {}
    }

    if (phoneServiceClass == null) {
        sysLog("[SysCell] PhoneInterfaceManager not found in this classloader")
        return
    }

    if (hookedCallbackClasses.putIfAbsent(phoneServiceClass, true) != null) {
        isTelephonyServiceHooked = true
        return
    }

    XposedHelpers.hookAllMethods(phoneServiceClass, "getAllCellInfo") { chain, _ ->
        val config = readConfig()
        val pkg = SystemHookUtils.extractPackageName(chain.args)
        if (config == null || !SystemHookUtils.isTargetCaller(chain.thisObject, pkg, config))
            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        // Keep native permission, user and AppOps decisions, including soft-denied empty results.
        val original = chain.proceed(chain.args.toTypedArray())
        if ((original as? List<*>)?.isNotEmpty() != true) return@hookAllMethods original
        if (!config.optBoolean("mock_cell", true)) return@hookAllMethods java.util.ArrayList<Any>()
        try { buildFakeCellInfoList(classLoader, config.optDouble("lat"), config.optDouble("lng"), config) }
        catch (error: Throwable) {
            HookStatus.error("CellInfo transformation", error)
            java.util.ArrayList<Any>()
        }
    }

    XposedHelpers.hookAllMethods(phoneServiceClass, "getCellLocation") { chain, executable ->
        val config = readConfig()
        val pkg = SystemHookUtils.extractPackageName(chain.args)
        if (config == null || !SystemHookUtils.isTargetCaller(chain.thisObject, pkg, config))
            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        val original = chain.proceed(chain.args.toTypedArray()) ?: return@hookAllMethods null
        if (!config.optBoolean("mock_cell", true)) return@hookAllMethods null
        try {
            val cells = buildFakeCellInfoList(classLoader, config.optDouble("lat"), config.optDouble("lng"), config)
            val serving = cells.firstOrNull { XposedHelpers.callMethod(it, "isRegistered") == true }
                ?: return@hookAllMethods null
            val identity = XposedHelpers.callMethod(serving, "getCellIdentity") ?: return@hookAllMethods null
            val returnType = (executable as java.lang.reflect.Method).returnType
            if (returnType.isInstance(identity)) return@hookAllMethods identity
            val cellLocation = XposedHelpers.callMethod(identity, "asCellLocation") ?: return@hookAllMethods null
            if (returnType.isInstance(cellLocation)) return@hookAllMethods cellLocation
            if (returnType == android.os.Bundle::class.java) {
                val bundle = android.os.Bundle()
                XposedHelpers.callMethod(cellLocation, "fillInNotifierBundle", bundle)
                return@hookAllMethods bundle
            }
            null
        } catch (error: Throwable) {
            HookStatus.error("Serving cell transformation", error)
            null
        }
    }

    XposedHelpers.hookAllMethods(phoneServiceClass, "requestCellInfoUpdate") { chain, _ ->
        routeCellInfoRequest(chain, classLoader)
    }
    val projection = RadioStateProjection(this, classLoader)
    for (name in listOf("getDataNetworkTypeForSubscriber", "getNetworkTypeForSubscriber", "getVoiceNetworkTypeForSubscriber",
            "getDataNetworkType", "getNetworkType", "getVoiceNetworkType", "getServiceStateForSubscriber", "getServiceStateForSlot", "getServiceState", "getSignalStrength")) {
        XposedHelpers.hookAllMethods(phoneServiceClass, name) { chain, _ ->
            val original = chain.proceed(chain.args.toTypedArray())
            val config = readConfig()
            val pkg = SystemHookUtils.extractPackageName(chain.args)
            if (original == null || config == null || !SystemHookUtils.isTargetCaller(chain.thisObject, pkg, config))
                return@hookAllMethods original
            try {
                when {
                    name.contains("NetworkType") -> if (original == 0) 0 else projection.network(config)
                    name.startsWith("getServiceState") -> projection.service(original, config)
                    name == "getSignalStrength" -> projection.signal(config)
                    else -> original
                }
            } catch (error: Throwable) {
                HookStatus.error("Radio state projection/$name", error)
                when {
                    name.contains("NetworkType") -> 0
                    name.startsWith("getServiceState") -> XposedHelpers.newInstance(XposedHelpers.findClass("android.telephony.ServiceState", classLoader))
                    else -> XposedHelpers.newInstance(XposedHelpers.findClass("android.telephony.SignalStrength", classLoader))
                }
            }
        }
    }
    isTelephonyServiceHooked = true
}

/** Preserve the registry cache; transform only permission-checked recipient callbacks. */
internal fun LocationHooker.hookSystemTelephonyRegistry(classLoader: ClassLoader) {
    val registryClass = SystemClassLocator.locate(SystemComponent.TELEPHONY_REGISTRY, classLoader) ?: return
    if (hookedCallbackClasses.putIfAbsent(registryClass, true) != null) return
    val delivery = TelephonyCallbackDelivery(this, classLoader)
    vendorExtraHooks.add(delivery)
    delivery.install(registryClass)
}
