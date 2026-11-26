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

import android.os.IBinder
import android.os.Binder
import android.os.IInterface
import android.util.Log
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.hooks.network.*
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemClassLocator
import com.vincenthzr.locationspoofer.xposed.utils.*
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.Timer
import java.util.TimerTask
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

private fun sysLog(msg: String) = XposedBridge.log(msg)

/**
 * com.android.bluetooth 系统级蓝牙与 BLE 信标拦截派发引擎
 *
 * 核心原理：
 * 全设备所有应用通过 BluetoothLeScanner 进行 BLE 扫描时，均会经由 IBluetoothGatt
 * 远程 IPC 路由至 com.android.bluetooth 进程中的 GattService。
 *
 * 本模块在 com.android.bluetooth 进程内拦截 BLE 扫描入口（Android 17 起是 le_scan.ScanBinder，更早是 GattService）：
 * 1. 当目标应用调用 registerScanner / startScan / registerAndStartScan 时，记录其 IScannerCallback 监听句柄；
 * 2. 绑定 IBinder.linkToDeath，客户端退出时自动移除；
 * 3. 启动系统级 BLE 周期性主动派发定时器：当配置开启 mock_bluetooth 且包含虚拟蓝牙设备（如打卡信标）时，
 *    周期性向目标应用的 IScannerCallback 派发伪造的 ScanResult，彻底解决室内无真实打卡机信号的问题；
 * 4. 拦截并阻止向目标应用派发真实物理硬件捕获的外部蓝牙信标，防止真实环境特征泄露。
 */

private class BleScannerRegistration(
    val binder: IBinder,
    val callbackRef: WeakReference<Any>,
    val packageName: String,
    val registeredTime: Long = System.currentTimeMillis(),
    @Volatile var scanning: Boolean = false,
    val uid: Int = Binder.getCallingUid()
)

private val bleScannerIds = ConcurrentHashMap<Int, BleScannerRegistration>()
private val activeBleScanners = BinderRegistrationMap<BleScannerRegistration>(
    onRemoved = { registration -> bleScannerIds.entries.removeIf { it.value === registration } }
) { bleDemandChanged?.invoke() }
private var bleDemandChanged: (() -> Unit)? = null
private var bleDemandTimer: DemandTimer? = null

@Volatile
private var isBleTimerStarted = false

@Volatile
internal var isBluetoothServiceHooked = false

internal fun saveSystemBleReloadState(): Array<Any> = activeBleScanners.mapNotNull { (binder, info) ->
    info.callbackRef.get()?.let { callback -> arrayOf<Any>(binder, callback, info.packageName,
        info.registeredTime, info.scanning, info.uid, bleScannerIds.entries.filter { it.value === info }.map { it.key }.toIntArray()) }
}.toTypedArray()

internal fun clearSystemBleReloadState() {
    bleDemandChanged = null
    bleDemandTimer?.close()
    bleDemandTimer = null
    bleScannerIds.clear()
    isBleTimerStarted = false
    activeBleScanners.clear()
}

internal fun LocationHooker.restoreSystemBleReloadState(value: Any?) {
    (value as? Array<*>)?.filterIsInstance<Array<*>>()?.forEach { row ->
        val binder = row[0] as IBinder
        if (!binder.isBinderAlive) return@forEach
        val callback = row[1]!!
        val registration = BleScannerRegistration(binder, WeakReference(callback), row[2] as String,
            row[3] as Long, row.getOrNull(4) as? Boolean ?: true, row.getOrNull(5) as? Int ?: 0)
        activeBleScanners[binder] = registration
        (row.getOrNull(6) as? IntArray)?.forEach { rememberScannerId(it, registration) }
        ensureScannerCallbackHooked(callback)
    }
}

internal fun LocationHooker.hookSystemBluetoothService(classLoader: ClassLoader) {
    if (isBluetoothServiceHooked) return
    if (com.vincenthzr.locationspoofer.xposed.hooks.vendor.VendorRegistry.active.usesFrameworkBleDelivery) return

    // 扫描入口类：Android 17 的 le_scan.ScanBinder，或更早版本的 GattService（可能在 bluetooth APEX 里）；
    // 都找不到时从 ServiceManager 里已注册的 GATT 服务实例拿真实类
    var scanServiceClass = SystemClassLocator.locate(
        SystemComponent.BLUETOOTH_SCAN_SERVICE, classLoader,
        apexJars = listOf(
            "/apex/com.android.btservices/javalib/service-bluetooth.jar",
            "/apex/com.android.bluetooth/javalib/service-bluetooth.jar"
        )
    )

    if (scanServiceClass == null) {
        try {
            val smClass = XposedHelpers.findClassIfExists("android.os.ServiceManager", classLoader)
            val binder = if (smClass != null) {
                XposedHelpers.callStaticMethod(smClass, "getService", "bluetooth_gatt") as? android.os.IBinder
            } else null
            if (binder != null) {
                scanServiceClass = binder.javaClass
                HookStatus.classFound(SystemComponent.BLUETOOTH_SCAN_SERVICE, binder.javaClass, "ServiceManager.getService")
                sysLog("[SysBle] Captured GattService from ServiceManager.getService(\"bluetooth_gatt\"): ${binder.javaClass}")
            }
        } catch (_: Throwable) {}
    }

    if (scanServiceClass == null) {
        sysLog("[SysBle] BLE scan service not found in com.android.bluetooth")
        return
    }

    if (hookedCallbackClasses.putIfAbsent(scanServiceClass, true) != null) {
        isBluetoothServiceHooked = true
        return
    }

    startSystemBleHeartbeat(classLoader)

    // 三个方法名分属不同 Android 版本，每个版本上只存在其中一部分（Hook 运行状态里其余的显示 ×0）：
    // - registerScanner：旧版本的第一步，只登记回调，此时还没开始扫描；
    // - startScan：旧版本的第二步，扫描开始，立即派发一次虚拟信标；
    // - registerAndStartScan：Android 17 合并成一步。此刻客户端还没收到 scannerId，
    //   BluetoothLeScanner 会丢弃这之前到达的结果，所以首次派发延后一点，之后由心跳定时器持续推送。
    hookScannerEntry(scanServiceClass, "registerScanner", classLoader, dispatchDelayMs = null)
    hookScannerEntry(scanServiceClass, "startScan", classLoader, dispatchDelayMs = 0L, register = false)
    hookScannerEntry(scanServiceClass, "registerAndStartScan", classLoader, dispatchDelayMs = 500L)
    for (name in listOf("stopScan", "unregisterScanner")) {
        XposedHelpers.hookAllMethods(scanServiceClass, name) { chain, _ ->
            val id = chain.args.filterIsInstance<Int>().firstOrNull()
            val direct = chain.args.firstNotNullOfOrNull { (it as? IInterface)?.asBinder() ?: it as? IBinder }
            val registration = direct?.let { activeBleScanners[it] } ?: id?.let { bleScannerIds[it] }
            val result = chain.proceed(chain.args.toTypedArray())
            if (registration != null) {
                if (name == "unregisterScanner") {
                    activeBleScanners.remove(registration.binder, registration)
                } else {
                    registration.scanning = false
                    bleDemandChanged?.invoke()
                }
            }
            result
        }
    }

    isBluetoothServiceHooked = true
}

/**
 * 在扫描入口方法前置拦截目标应用：
 * @param register 是否从参数里取出 IScannerCallback 登记为活跃扫描者（之后由心跳定时器持续派发虚拟信标）
 * @param dispatchDelayMs 非空时表示这次调用会开始扫描，在这么久之后立即派发一次虚拟信标
 */
private fun LocationHooker.hookScannerEntry(
    serviceClass: Class<*>,
    methodName: String,
    classLoader: ClassLoader,
    dispatchDelayMs: Long?,
    register: Boolean = true
) {
    try {
        XposedHelpers.hookAllMethods(serviceClass, methodName) { chain, _ ->
            val config = readConfig()
            val explicitPkg = config?.let { SystemHookUtils.extractPackageName(chain.args) }
            val callerUid = SystemHookUtils.extractCallerUid(chain.args) ?: Binder.getCallingUid()
            val target = callerUid > 1000 && explicitPkg?.substringBefore(':') !in SystemHookUtils.EXEMPT_PACKAGES
            // Install the scanner-ID callback before the platform can synchronously return
            // registration status. A pending registration alone never creates output demand.
            val pending = if (register && target) registerTargetScanner(chain.args, explicitPkg, false) else null
            val original = try { chain.proceed(chain.args.toTypedArray()) }
            catch (error: Throwable) {
                if (pending != null) activeBleScanners.remove(pending.binder, pending)
                throw error
            }
            try {
                if (config != null && target) {
                    if (register && dispatchDelayMs != null) {
                        pending?.scanning = true
                        bleDemandChanged?.invoke()
                    }
                    else chain.args.filterIsInstance<Int>().firstOrNull()?.let { id ->
                        bleScannerIds[id]?.scanning = true
                        bleDemandChanged?.invoke()
                    }
                    val mocking = config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true) &&
                        (pending?.packageName ?: explicitPkg)?.let {
                            registeredPackageEnabled(config, it)
                        } == true
                    if (dispatchDelayMs != null && mocking) {
                        sysLog("[SysBle] Target app started BLE scan via $methodName: ${explicitPkg ?: "caller"}")
                        if (dispatchDelayMs == 0L) {
                            dispatchFakeBeaconsToActiveScanners(config, classLoader)
                        } else {
                            val delayed = ownReloadTimer(Timer("LocationSpoofer-SysBleFirst", true))
                            delayed.schedule(object : TimerTask() {
                                override fun run() {
                                    try {
                                        dispatchFakeBeaconsToActiveScanners(readConfig() ?: return, classLoader)
                                    } catch (_: Throwable) {} finally {
                                        delayed.cancel()
                                        releaseReloadTimer(delayed)
                                    }
                                }
                            }, dispatchDelayMs)
                        }
                    }
                }
            } catch (e: Throwable) {
                sysLog("[SysBle] $methodName pre-hook error: $e")
            }
            return@hookAllMethods original
        }
    } catch (e: Throwable) {
        sysLog("[SysBle] hook $methodName failed: $e")
    }
}

/** 从扫描入口的参数里取出目标应用的 IScannerCallback，登记为活跃扫描者并在客户端退出时自动移除 */
private fun LocationHooker.registerTargetScanner(
    args: List<Any?>, explicitPkg: String?, scanning: Boolean
): BleScannerRegistration? {
    val callback = args.firstOrNull { arg ->
        arg != null && (LocationHooker.hasTypeByName(arg.javaClass, "android.bluetooth.le.IScannerCallback") ||
            arg.javaClass.name.contains("IScannerCallback"))
    } ?: return null
    val binder = (callback as? IInterface)?.asBinder() ?: (callback as? IBinder) ?: return null
    val pkgName = explicitPkg ?: SystemHookUtils.resolveCallingPackages(null).firstOrNull() ?: "unknown"
    val registration = BleScannerRegistration(binder, WeakReference(callback), pkgName, scanning = scanning)
    activeBleScanners[binder] = registration
    ensureScannerCallbackHooked(callback)
    sysLog("[SysBle] Registered target BLE scanner for $pkgName (total active: ${activeBleScanners.size})")
    return registration.takeIf { activeBleScanners[binder] === it }
}

private fun rememberScannerId(id: Int, registration: BleScannerRegistration) {
    bleScannerIds.compute(id) { _, current ->
        if (activeBleScanners[registration.binder] === registration) registration else current
    }
}
/** 启动系统级 BLE 主动派发定时器（每 1200ms 向活跃的目标应用推送虚拟信标） */
private fun LocationHooker.startSystemBleHeartbeat(classLoader: ClassLoader) {
    synchronized(activeBleScanners) {
        if (isBleTimerStarted) return
        isBleTimerStarted = true
    }
    val output = demandTimer("system_ble", 1200L, fixedRate = true) {
        val config = readConfig() ?: return@demandTimer
        if (config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
            dispatchFakeBeaconsToActiveScanners(config, classLoader)
        }
    }
    bleDemandTimer = output
    bleDemandChanged = {
        val config = readConfig()
        output.setRequired(!retiringGeneration && config != null && config.optBoolean("active", false) &&
            config.optBoolean("mock_bluetooth", true) && (config.optJSONArray("bluetooth_json")?.length() ?: 0) > 0 &&
            activeBleScanners.values.any { it.scanning && it.callbackRef.get() != null && registeredPackageEnabled(config, it.packageName, it.uid) })
    }
    observeConfigChanges { bleDemandChanged?.invoke() }
}

/** 向当前所有处于活跃状态的目标应用 IScannerCallback 派发配置中的虚拟 BLE 信标 */
private fun LocationHooker.dispatchFakeBeaconsToActiveScanners(config: JSONObject, classLoader: ClassLoader) {
    val bluetoothArray = config.optJSONArray("bluetooth_json") ?: return
    if (bluetoothArray.length() == 0) return

    for ((binder, info) in activeBleScanners) {
        if (!info.scanning || !registeredPackageEnabled(config, info.packageName, info.uid)) continue
        if (!binder.isBinderAlive) {
            activeBleScanners.remove(binder, info)
            continue
        }
        val callback = info.callbackRef.get()
        if (callback == null) { activeBleScanners.remove(binder, info); continue }
        if (callback != null) {
            try {
                deliverBleScanResults(config, callback, classLoader)
            } catch (e: Throwable) {
                activeBleScanners.remove(binder, info)
            }
        }
    }
}

private fun LocationHooker.ensureScannerCallbackHooked(callback: Any) {
    val clazz = callback.javaClass
    if (hookedCallbackClasses.putIfAbsent(clazz, true) != null) return
    XposedHelpers.hookAllMethods(clazz, "onScannerRegistered") { chain, _ ->
        val status = chain.args.getOrNull(0) as? Int
        val id = chain.args.getOrNull(1) as? Int
        val binder = (chain.thisObject as? IInterface)?.asBinder() ?: chain.thisObject as? IBinder
        val registration = binder?.let { activeBleScanners[it] }
        val original = chain.proceed(chain.args.toTypedArray())
        if (registration != null) {
            if (status == 0 && id != null) rememberScannerId(id, registration)
            else activeBleScanners.remove(registration.binder, registration)
        }
        original
    }
    val methods = arrayOf("onScanResult", "onBatchScanResults")
    for (mName in methods) {
        try {
            XposedHelpers.hookAllMethods(clazz, mName) { innerChain, _ ->
                val binder = (innerChain.thisObject as? IInterface)?.asBinder() ?: (innerChain.thisObject as? IBinder)
                val config = readConfig()
                val registration = binder?.let { activeBleScanners[it] }
                val isTarget = config != null && registration != null && registeredPackageEnabled(config, registration.packageName, registration.uid)
                if (syntheticBleDelivery.current != true && isTarget && config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    // 阻止真实物理硬件扫描到的外部蓝牙设备特征泄漏给目标应用
                    return@hookAllMethods null
                }
                return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }
}
