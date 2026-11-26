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

import com.vincenthzr.locationspoofer.xposed.hooks.vendor.VendorRegistry
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.util.Log
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemClassLocator
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.versions.ColorOs16Vendor
import com.vincenthzr.locationspoofer.xposed.utils.*
import io.github.libxposed.api.*
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.Timer
import java.util.TimerTask
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * system_server 系统级定位服务拦截与主动派发引擎
 *
 * 核心原理：
 * 全设备所有 App 的原生定位请求（LocationManager / FusedLocation）最终均通过 Binder IPC
 * 汇聚至 system_server 的 LocationManagerService。
 *
 * 本模块直接拦截 LocationManagerService，并在每次 IPC 入口处根据调用方 UID 与包名进行规则判定：
 * 1. 命中用户在 LocationSpoofer 中指定的应用白名单，或开启了【全局模拟模式】时，向该应用派发伪造的
 *    Location 对象、GNSS 20+ 卫星星座以及 NMEA-0183 报文流；
 * 2. 严禁污染 LocationSpoofer 自身与系统核心组件（SystemUI、电话、紧急呼叫）；
 * 3. 内置主动心跳推送定时器：当物理 GPS 芯片在室内无锁定时，主动向目标应用的 ILocationListener
 *    周期性推送平滑的伪造坐标序列，彻底杜绝目标应用无限期等待真实 GPS 回调的问题；
 * 4. 监听者通过 IBinder.linkToDeath 实现自动垃圾回收，客户端进程退出即刻清理，保证系统服务零内存泄露。
 */

private fun logLoc(msg: String) {
    XposedBridge.log(msg)
}

// 存储被标记为目标应用的 ILocationListener / ILocationCallback 实例
private val spoofedListenerInstances =
    Collections.synchronizedMap(WeakHashMap<Any, NativeRegistration<Unit>>())

// 存储当前处于活跃状态的目标应用 ILocationListener 代理对象及其 Binder 映射（强引用避免 GC 导致失联）
private data class ListenerRegistrationInfo(
    val listener: Any,
    val packageName: String,
    val provider: String = android.location.LocationManager.GPS_PROVIDER,
    val registeredTime: Long = System.currentTimeMillis()
)

private var locationDemandChanged: (() -> Unit)? = null
private var locationDemandTimer: DemandTimer? = null
private var nativeSignalGate: NativeSignalGate? = null
private var rawGnssDelivery: RawGnssDelivery? = null
private val nmeaAltitudeResolverLock = Any()
@Volatile private var nmeaAltitudeResolver: NmeaAltitudeResolver? = null

private val nativeLocationRegistrationLock = Any()
private val activeListenerBinders =
    BinderRegistrationMap<NativeRegistration<ListenerRegistrationInfo>> { locationDemandChanged?.invoke() }
private fun NativeRegistrationAttempt.trackLocationInstance(listener: Any) {
    track(Unit, { spoofedListenerInstances.put(listener, it) }) { rejected, previous ->
        if (previous == null) spoofedListenerInstances.remove(listener, rejected)
        else spoofedListenerInstances.replace(listener, rejected, previous)
    }
}

private fun NativeRegistrationAttempt.trackListener(binder: IBinder?, info: ListenerRegistrationInfo) {
    if (binder != null) {
        track(info, { activeListenerBinders.put(binder, it) }) { rejected, previous ->
            activeListenerBinders.rollback(binder, rejected, previous)
        }
    }
    trackLocationInstance(info.listener)
}

// 存储当前活跃的目标应用 IGnssStatusListener 映射
private data class GnssStatusRegistrationInfo(
    val listener: Any,
    val packageName: String,
    val registeredTime: Long = System.currentTimeMillis(),
    @Volatile var started: Boolean = false,
    @Volatile var accepted: Boolean = true
)
private val activeGnssStatusBinders = BinderRegistrationMap<GnssStatusRegistrationInfo> { locationDemandChanged?.invoke() }

// 存储当前活跃的目标应用 IGnssNmeaListener 映射
private data class GnssNmeaRegistrationInfo(
    val listener: Any,
    val packageName: String,
    val registeredTime: Long = System.currentTimeMillis(),
    @Volatile var accepted: Boolean = true
)
private val activeGnssNmeaBinders = BinderRegistrationMap<GnssNmeaRegistrationInfo> { locationDemandChanged?.invoke() }
private val syntheticSignalDelivery = ThreadLocal.withInitial { false }
private val signalDeliveryHooks = ConcurrentHashMap<Pair<Class<*>, Boolean>, Boolean>()
private var retiringSignalBinders: Set<IBinder> = emptySet()

internal fun LocationHooker.prepareSignalReloadHandoff(): Array<Any> {
    val config = readConfig()
    val callbacks = linkedMapOf<IBinder, Any>()
    if (config != null) {
        activeGnssStatusBinders.filterValues { registeredPackageEnabled(config, it.packageName) }
            .forEach { (binder, info) -> callbacks[binder] = info.listener }
        activeGnssNmeaBinders.filterValues { registeredPackageEnabled(config, it.packageName) }
            .forEach { (binder, info) -> callbacks[binder] = info.listener }
    }
    retiringSignalBinders = callbacks.keys.toSet()
    return (callbacks.values.toList() + rawGnssDelivery?.prepareReloadHandoff().orEmpty().toList()).toTypedArray()
}

internal fun registeredPackageEnabled(config: JSONObject, pkg: String, capturedUid: Int? = null): Boolean {
    if (!config.optBoolean("active", false)) return false
    val caller = pkg.substringBefore(':')
    val uid = capturedUid?.takeIf { it > 1000 } ?: runCatching { SystemHookUtils.getSystemContext()?.packageManager
        ?.getApplicationInfo(caller, 0)?.uid }.getOrNull()
    return SystemHookUtils.isTargetCaller(null, caller.takeUnless {
        it in setOf("unknown", "target", "caller")
    }, config, uid)
}

/** Native and synthetic streams must not be mixed for a managed target registration. */
private fun LocationHooker.hookSignalDelivery(listener: Any, nmea: Boolean) {
    val type = listener.javaClass
    if (signalDeliveryHooks.putIfAbsent(type to nmea, true) != null) return
    val methods = if (nmea) listOf("onNmeaReceived", "onNmeaMessage")
        else listOf("onSvStatusChanged", "onGnssStarted", "onGnssStopped", "onFirstFix")
    for (name in methods) runCatching {
        XposedHelpers.hookAllMethods(type, name) { chain, _ ->
            val recipient = (chain.thisObject as? IInterface)?.asBinder() ?: chain.thisObject as? IBinder
            if (retiringGeneration && recipient in retiringSignalBinders) return@hookAllMethods null
            if (!syntheticSignalDelivery.get()) {
                val binder = (chain.thisObject as? IInterface)?.asBinder() ?: chain.thisObject as? IBinder
                val pkg = binder?.let { if (nmea) activeGnssNmeaBinders[it]?.packageName else activeGnssStatusBinders[it]?.packageName }
                val config = readConfig()
                if (pkg != null && config != null && registeredPackageEnabled(config, pkg)) return@hookAllMethods null
            }
            chain.proceed(chain.args.toTypedArray())
        }
    }
}

/** Adopt accepted registrations that existed before activation or a target-scope change. */
private fun LocationHooker.reconcileNativeSignals() {
    val config = readConfig()
    val gate = nativeSignalGate
    if (config != null && gate != null) for (nmea in listOf(false, true)) {
        for ((binder, registration) in gate.entries(nmea)) {
            if (!binder.isBinderAlive || !registeredPackageEnabled(config, registration.packageName, registration.uid)) continue
            val added = if (nmea) activeGnssNmeaBinders.registerIfAbsent(binder,
                GnssNmeaRegistrationInfo(registration.listener, registration.packageName))
            else activeGnssStatusBinders.registerIfAbsent(binder,
                GnssStatusRegistrationInfo(registration.listener, registration.packageName))
            if (added) {
                hookSignalDelivery(registration.listener, nmea)
                deliveryHandoff?.release(registration.listener)
            }
        }
    }
    locationDemandChanged?.invoke()
}

/** Observe the native permission/registration result before emitting any synthetic data. */
private fun LocationHooker.registerSignalCallback(chain: XposedInterface.Chain, nmea: Boolean): Any? {
    val args = chain.args.toTypedArray()
    val config = readConfig() ?: return chain.proceed(args)
    val pkg = SystemHookUtils.extractPackageName(chain.args)
    val uid = SystemHookUtils.extractCallerUid(chain.args)
    if (!config.optBoolean("active", false) || !SystemHookUtils.isTargetCaller(chain.thisObject, pkg, config, uid))
        return chain.proceed(args)
    val callback = chain.args.firstOrNull { it is IInterface || it is IBinder } ?: return chain.proceed(args)
    val binder = (callback as? IInterface)?.asBinder() ?: callback as? IBinder ?: return chain.proceed(args)
    val gnssInfo = GnssStatusRegistrationInfo(callback, pkg ?: "unknown", accepted = false)
    val nmeaInfo = GnssNmeaRegistrationInfo(callback, pkg ?: "unknown", accepted = false)
    val oldGnss = if (!nmea) activeGnssStatusBinders.put(binder, gnssInfo) else null
    val oldNmea = if (nmea) activeGnssNmeaBinders.put(binder, nmeaInfo) else null
    hookSignalDelivery(callback, nmea)
    fun rollback() {
        if (nmea) {
            activeGnssNmeaBinders.rollback(binder, nmeaInfo, oldNmea)
        } else {
            activeGnssStatusBinders.rollback(binder, gnssInfo, oldGnss)
        }
    }
    val result = try { chain.proceed(args) } catch (error: Throwable) { rollback(); throw error }
    if (result == false) { rollback(); return result }
    if (nmea) nmeaInfo.accepted = true else gnssInfo.accepted = true
    nativeSignalGate?.refresh(chain.thisObject)
    locationDemandChanged?.invoke()
    runCatching {
        if (nativeSignalGate?.allows(binder, nmea) != true) return@runCatching
        if (nmea) {
            val motion = getCurrentSpoofedMotion("WGS-84")
            if (motion != null) {
                val now = System.currentTimeMillis()
                val altitude = RouteEngine.realisticAltitude(config)
                val accuracy = getJitteredAccuracy()
                val lines = if (usesColorOsNmeaModel()) {
                    val reference = nmeaAltitudeResolver?.cached(motion.lat, motion.lng, altitude)
                    if (retiringGeneration || readConfig() !== config) return@runCatching
                    buildModelNmeaSentences(motion, reference, accuracy, config, now)
                } else buildMockNmeaSentences(motion, altitude, accuracy, config)
                for (line in lines)
                    dispatchNmea(callback, now, line)
            }
        } else {
            val status = GnssFastMockEngine.getOrCreateSpoofedGnssStatus(callback.javaClass.classLoader ?: ClassLoader.getSystemClassLoader(),
                SatelliteProfile.count(config), config.optBoolean("enable_jitter", true), null)
            dispatchGnssStatus(callback, status, callback.javaClass.classLoader ?: ClassLoader.getSystemClassLoader())
        }
    }
    return result
}

// 存储当前活跃的目标应用 ILocationCallback (单次定位) 映射
private data class CallbackRegistrationInfo(
    val callback: Any,
    val packageName: String,
    val registeredTime: Long = System.currentTimeMillis()
)
private val activeCallbackBinders = BinderRegistrationMap<NativeRegistration<CallbackRegistrationInfo>>()

private fun NativeRegistrationAttempt.trackCallback(binder: IBinder?, info: CallbackRegistrationInfo) {
    if (binder != null) {
        track(info, { activeCallbackBinders.put(binder, it) }) { rejected, previous ->
            activeCallbackBinders.rollback(binder, rejected, previous)
        }
    }
    trackLocationInstance(info.callback)
}

// 存储当前活跃的目标应用 PendingIntent 映射
private val activePendingIntents = ConcurrentHashMap<android.app.PendingIntent, NativeRegistration<String>>()

private fun NativeRegistrationAttempt.trackPendingIntent(pendingIntent: android.app.PendingIntent, packageName: String) {
    track(packageName, { activePendingIntents.put(pendingIntent, it) }) { rejected, previous ->
        if (previous == null) activePendingIntents.remove(pendingIntent, rejected)
        else activePendingIntents.replace(pendingIntent, rejected, previous)
    }
}

@Volatile
private var isHeartbeatTimerStarted = false

/** Flatten module-defined registrations into bootstrap arrays before handing them to new code. */
internal fun saveSystemLocationReloadState(): Array<Any> = arrayOf(
    activeListenerBinders.mapTo(java.util.ArrayList()) { (binder, entry) ->
        val info = entry.value
        arrayOf<Any>(binder, info.listener, info.packageName, info.provider, info.registeredTime)
    },
    activeGnssStatusBinders.filterValues { it.accepted }.mapTo(java.util.ArrayList()) { (binder, info) ->
        arrayOf<Any>(binder, info.listener, info.packageName, info.registeredTime, info.started)
    },
    activeGnssNmeaBinders.filterValues { it.accepted }.mapTo(java.util.ArrayList()) { (binder, info) ->
        arrayOf<Any>(binder, info.listener, info.packageName, info.registeredTime)
    },
    activeCallbackBinders.mapTo(java.util.ArrayList()) { (binder, entry) ->
        val info = entry.value
        arrayOf<Any>(binder, info.callback, info.packageName, info.registeredTime)
    },
    activePendingIntents.mapTo(java.util.ArrayList()) { (pendingIntent, entry) ->
        arrayOf<Any>(pendingIntent, entry.value)
    },
    rawGnssDelivery?.save() ?: emptyList<Array<Any>>()
)

internal fun clearSystemLocationReloadState() {
    locationDemandChanged = null
    nativeSignalGate?.close()
    nativeSignalGate = null
    rawGnssDelivery?.close()
    rawGnssDelivery = null
    locationDemandTimer?.close()
    locationDemandTimer = null
    clearNmeaAltitudeResolver()
    isHeartbeatTimerStarted = false
    activeListenerBinders.clear()
    activeGnssStatusBinders.clear()
    activeGnssNmeaBinders.clear()
    activeCallbackBinders.clear()
    activePendingIntents.clear()
    spoofedListenerInstances.clear()
}

internal fun LocationHooker.restoreSystemLocationReloadState(value: Any?) {
    val state = value as? Array<*> ?: return
    fun rows(index: Int): List<Array<*>> = (state.getOrNull(index) as? List<*>)?.filterIsInstance<Array<*>>() ?: emptyList()
    rows(0).forEach { row ->
        val binder = row[0] as IBinder
        if (!binder.isBinderAlive) return@forEach
        val listener = row[1]!!
        activeListenerBinders[binder] = NativeRegistration(
            ListenerRegistrationInfo(listener, row[2] as String, row[3] as String, row[4] as Long)
        )
        spoofedListenerInstances[listener] = NativeRegistration(Unit)
        ensureCallbackHooked(listener, "onLocationChanged")
    }
    rows(1).forEach { row ->
        val binder = row[0] as IBinder
        if (!binder.isBinderAlive) return@forEach
        activeGnssStatusBinders[binder] = GnssStatusRegistrationInfo(row[1]!!, row[2] as String, row[3] as Long, row.getOrNull(4) as? Boolean ?: true)
        hookSignalDelivery(row[1]!!, false)
        deliveryHandoff?.release(row[1]!!)
    }
    rows(2).forEach { row ->
        val binder = row[0] as IBinder
        if (!binder.isBinderAlive) return@forEach
        activeGnssNmeaBinders[binder] = GnssNmeaRegistrationInfo(row[1]!!, row[2] as String, row[3] as Long)
        hookSignalDelivery(row[1]!!, true)
        deliveryHandoff?.release(row[1]!!)
    }
    rows(3).forEach { row ->
        val binder = row[0] as IBinder
        if (!binder.isBinderAlive) return@forEach
        val callback = row[1]!!
        activeCallbackBinders[binder] = NativeRegistration(
            CallbackRegistrationInfo(callback, row[2] as String, row[3] as Long)
        )
        spoofedListenerInstances[callback] = NativeRegistration(Unit)
        ensureCallbackHooked(callback, "onLocation")
    }
    rows(4).forEach { row ->
        activePendingIntents[row[0] as android.app.PendingIntent] = NativeRegistration(row[1] as String)
    }
    rawGnssDelivery?.restore(state.getOrNull(5))
}

/** 在 args 里递归找出所有 android.location.Location（单个、List、或 LocationResult 形态）并就地改写 */
private fun rewriteLocationArgs(
    args: List<Any?>,
    motion: SpoofedMotion,
    altitude: Double,
    accuracy: Float
) {
    for (arg in args) {
        when {
            arg == null -> continue
            LocationHooker.hasTypeByName(arg.javaClass, "android.location.Location") -> {
                SystemHookUtils.applyFakeLocationFields(arg, motion, altitude, accuracy)
            }
            arg.javaClass.name.contains("LocationResult") -> {
                try {
                    val list = XposedHelpers.callMethod(arg, "asList") as? List<*>
                    list?.forEach { item ->
                        if (item != null && LocationHooker.hasTypeByName(
                                item.javaClass,
                                "android.location.Location"
                            )
                        ) {
                            SystemHookUtils.applyFakeLocationFields(item, motion, altitude, accuracy)
                        }
                    }
                } catch (_: Throwable) {}
            }
            arg is List<*> -> {
                arg.forEach { item ->
                    if (item != null && LocationHooker.hasTypeByName(
                            item.javaClass,
                            "android.location.Location"
                        )
                    ) {
                        SystemHookUtils.applyFakeLocationFields(item, motion, altitude, accuracy)
                    }
                }
            }
        }
    }
}

/** 找到目标监听者/回调对象类之后，Hook 一次，按实例身份决定要不要改写投递内容 */
private fun LocationHooker.ensureCallbackHooked(callback: Any, vararg deliveryMethodNames: String) {
    val clazz = callback.javaClass
    if (hookedCallbackClasses.putIfAbsent(clazz, true) != null) return
    for (methodName in deliveryMethodNames) {
        try {
            XposedHelpers.hookAllMethods(clazz, methodName) { innerChain, _ ->
                val binder = (innerChain.thisObject as? IInterface)?.asBinder() ?: (innerChain.thisObject as? IBinder)
                val oneShot = if (binder != null && methodName == "onLocation") activeCallbackBinders[binder] else null
                val config = readConfig()
                val isGlobal = config?.optBoolean("system_hook_global_mode", false) == true
                val isTracked = (binder != null && (activeListenerBinders.containsKey(binder) || activeCallbackBinders.containsKey(binder))) ||
                        spoofedListenerInstances.containsKey(innerChain.thisObject)
                val isTarget = isTracked || isGlobal
                if (isTarget && config != null && config.optBoolean("active", false)) {
                    val motion = getCurrentSpoofedMotion("WGS-84")
                    if (motion != null) {
                        rewriteLocationArgs(
                            innerChain.args,
                            motion,
                            RouteEngine.realisticAltitude(config),
                            getJitteredAccuracy()
                        )
                        logLoc("[SysLoc] Rewrote location in callback $methodName for target binder (lat=${motion.lat}, lng=${motion.lng}, isGlobal=$isGlobal)"
                        )
                    }
                }
                try {
                    innerChain.proceed(innerChain.args.toTypedArray())
                } finally {
                    if (binder != null && oneShot != null) activeCallbackBinders.remove(binder, oneShot)
                }
            }
        } catch (_: Throwable) {}
    }
}

/**
 * 启动 system_server 内部的主动心跳推送定时器
 * 当目标应用处于活动状态时，此定时器主动向客户端 Binder 投递平滑的伪造坐标、
 * 20+ 真实多星座卫星（GnssStatus）以及标准 NMEA-0183 报文，彻底消除 GPS 状态测试工具卫星数为 0 的问题。
 */
private fun LocationHooker.startSystemLocationHeartbeat(classLoader: ClassLoader) {
    if (isHeartbeatTimerStarted) return
    synchronized(activeListenerBinders) {
        if (isHeartbeatTimerStarted) return
        isHeartbeatTimerStarted = true
    }

    val output = demandTimer("system_location", 1000L, fixedRate = true) {
            try {
                val config = readConfig() ?: return@demandTimer
                if (!config.optBoolean("active", false)) return@demandTimer

                val motion = getCurrentSpoofedMotion("WGS-84") ?: return@demandTimer
                val altitude = RouteEngine.realisticAltitude(config)
                val accuracy = getJitteredAccuracy()

                // 1. 推送定位坐标给目标应用 ILocationListener
                if (activeListenerBinders.isNotEmpty()) {
                    for ((binder, entry) in activeListenerBinders) {
                        val info = entry.value
                        if (!binder.isBinderAlive) {
                            activeListenerBinders.remove(binder, entry)
                            continue
                        }
                        val fakeLoc = SystemHookUtils.buildFakeLocation(
                            classLoader,
                            info.provider,
                            motion,
                            altitude,
                            accuracy
                        )
                        if (fakeLoc != null) {
                            val listener = info.listener
                            try {
                                dispatchFakeLocationToListener(listener, fakeLoc)
                            } catch (e: Throwable) {
                                if (!binder.isBinderAlive) {
                                    activeListenerBinders.remove(binder, entry)
                                }
                            }
                        }
                    }
                }

                // 2. 推送 20+ 真实卫星星座给目标应用 IGnssStatusListener (GnssStatus / GpsStatus)
                if (activeGnssStatusBinders.isNotEmpty()) {
                    val satCount = SatelliteProfile.count(config)
                    val enableJitter = config.optBoolean("enable_jitter", true)
                    val gnssStatus = GnssFastMockEngine.getOrCreateSpoofedGnssStatus(
                        classLoader,
                        satCount,
                        enableJitter,
                        null
                    )
                    for ((binder, info) in activeGnssStatusBinders) {
                        if (!info.accepted) continue
                        if (!registeredPackageEnabled(config, info.packageName) || nativeSignalGate?.allows(binder, false) != true) continue
                        if (!binder.isBinderAlive) {
                            activeGnssStatusBinders.remove(binder, info)
                            continue
                        }
                        val callback = info.listener
                        try {
                            dispatchGnssStatus(callback, gnssStatus, classLoader)
                        } catch (e: Throwable) {
                            if (!binder.isBinderAlive) {
                                activeGnssStatusBinders.remove(binder, info)
                            }
                        }
                    }
                }

                // 3. 推送标准 NMEA-0183 报文流给目标应用 IGnssNmeaListener
                if (activeGnssNmeaBinders.isNotEmpty()) {
                    val useModel = usesColorOsNmeaModel()
                    var nmeas = if (useModel) null else buildMockNmeaSentences(motion, altitude, accuracy, config)
                    var nowMs = if (useModel) 0L else System.currentTimeMillis()
                    for ((binder, info) in activeGnssNmeaBinders) {
                        if (!info.accepted) continue
                        if (!registeredPackageEnabled(config, info.packageName) || nativeSignalGate?.allows(binder, true) != true) continue
                        if (!binder.isBinderAlive) {
                            activeGnssNmeaBinders.remove(binder, info)
                            continue
                        }
                        if (nmeas == null) {
                            nowMs = System.currentTimeMillis()
                            val reference = resolveNmeaAltitudeOnWorker(config, motion, altitude)
                            if (retiringGeneration || readConfig() !== config) return@demandTimer
                            nmeas = buildModelNmeaSentences(motion, reference, accuracy, config, nowMs)
                        }
                        if (useModel) {
                            // The registration or active configuration can change during the asset read.
                            if (retiringGeneration || readConfig() !== config) return@demandTimer
                            if (activeGnssNmeaBinders[binder] !== info || !binder.isBinderAlive ||
                                nativeSignalGate?.allows(binder, true) != true) continue
                        }
                        val callback = info.listener
                        try {
                            for (line in nmeas) {
                                dispatchNmea(callback, nowMs, line)
                            }
                        } catch (e: Throwable) {
                            if (!binder.isBinderAlive) {
                                activeGnssNmeaBinders.remove(binder, info)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
    }
    locationDemandTimer = output
    locationDemandChanged = {
        val config = readConfig()
        val wanted = !retiringGeneration && config != null && config.optBoolean("active", false) &&
            (activeListenerBinders.values.any { registeredPackageEnabled(config, it.value.packageName) } ||
             activeGnssStatusBinders.any { (binder, info) -> info.accepted && nativeSignalGate?.allows(binder, false) == true && registeredPackageEnabled(config, info.packageName) } ||
             activeGnssNmeaBinders.any { (binder, info) -> info.accepted && nativeSignalGate?.allows(binder, true) == true && registeredPackageEnabled(config, info.packageName) })
        output.setRequired(wanted)
    }
    observeConfigChanges {
        if (readConfig()?.optBoolean("active", false) != true) clearNmeaAltitudeResolver()
        nativeSignalGate?.refresh()
        reconcileNativeSignals()
    }
}

/** 向 IGnssStatusListener 代理对象主动反射调用 onSvStatusChanged，支持 Android 11+ (GnssStatus) 与 Android 7~10 */
private fun LocationHooker.dispatchGnssStatus(listener: Any, gnssStatus: Any?, classLoader: ClassLoader) {
    val previous = syntheticSignalDelivery.get()
    syntheticSignalDelivery.set(true)
    try { dispatchSyntheticGnssStatus(listener, gnssStatus, classLoader) }
    finally { syntheticSignalDelivery.set(previous) }
}

private fun LocationHooker.dispatchSyntheticGnssStatus(listener: Any, gnssStatus: Any?, classLoader: ClassLoader) {
    val clazz = listener.javaClass

    // 确保 GNSS 引擎启动状态已告知客户端
    val binder = (listener as? IInterface)?.asBinder() ?: listener as? IBinder
    val info = binder?.let { activeGnssStatusBinders[it] }
    val start = if (info != null) synchronized(info) {
        if (info.started) false else { info.started = true; true }
    } else false
    if (start) {
        runCatching { clazz.methods.firstOrNull { it.name == "onGnssStarted" && it.parameterCount == 0 }?.invoke(listener) }
        runCatching { clazz.methods.firstOrNull { it.name == "onFirstFix" && it.parameterCount == 1 }?.invoke(listener, 1000) }
    }
    val config = readConfig()

    val svMethod = clazz.methods.firstOrNull { it.name == "onSvStatusChanged" }
    if (svMethod != null) {
        try {
            if (svMethod.parameterCount == 1) {
                // Android 11+ 标准：GnssStatus 对象 (Parcelable 跨进程分发)
                val statusObj = gnssStatus ?: GnssFastMockEngine.getOrCreateSpoofedGnssStatus(
                    classLoader, SatelliteProfile.count(config), config?.optBoolean("enable_jitter", true) ?: true, null
                )
                if (statusObj != null) {
                    svMethod.invoke(listener, statusObj)
                }
            } else if (svMethod.parameterCount == 6 || svMethod.parameterCount == 7) {
                val sky = SatelliteSky.snapshot(SatelliteProfile.count(config), System.currentTimeMillis(), config?.optBoolean("enable_jitter", true) ?: true)
                val args = mutableListOf<Any>(sky.size,
                    sky.map { GnssFastMockEngine.packedSatellite(it, classLoader) }.toIntArray(),
                    sky.map { it.cn0 }.toFloatArray(), sky.map { it.elevation }.toFloatArray(),
                    sky.map { it.azimuth }.toFloatArray(), sky.map { it.carrierHz ?: 0f }.toFloatArray())
                if (svMethod.parameterCount == 7) args += sky.map { (it.cn0 - 2.5f).coerceAtLeast(26f) }.toFloatArray()
                svMethod.invoke(listener, *args.toTypedArray())
            }
        } catch (e: Throwable) {
            logLoc("[SysLoc] dispatch onSvStatusChanged error: $e")
        }
    }
}

/** 向 IGnssNmeaListener 反射分发 NMEA 语句 */
private fun dispatchNmea(listener: Any, timestamp: Long, sentence: String) {
    val clazz = listener.javaClass
    val method = clazz.methods.firstOrNull { it.name == "onNmeaReceived" }
        ?: clazz.methods.firstOrNull { it.name == "onNmeaMessage" }
    if (method != null) {
        val previous = syntheticSignalDelivery.get()
        syntheticSignalDelivery.set(true)
        try {
            if (method.parameterTypes.size == 2) {
                if (method.parameterTypes[0] == Long::class.javaPrimitiveType || method.parameterTypes[0] == Long::class.java) {
                    method.invoke(listener, timestamp, sentence)
                } else {
                    method.invoke(listener, sentence, timestamp)
                }
            } else if (method.parameterTypes.size == 1 && method.parameterTypes[0] == String::class.java) {
                method.invoke(listener, sentence)
            }
        } catch (_: Throwable) {}
        finally { syntheticSignalDelivery.set(previous) }
    }
}

/** 动态构造符合当前运动轨迹与坐标的标准 NMEA-0183 报文序列 */
private fun LocationHooker.buildMockNmeaSentences(
    motion: SpoofedMotion,
    altitude: Double,
    accuracy: Float,
    config: JSONObject
): List<String> {
    return NmeaEncoder.build(motion.lat, motion.lng, motion.speed, motion.bearing, altitude,
        accuracy, SatelliteProfile.count(config), config.optBoolean("enable_jitter", true), System.currentTimeMillis())
}

private fun usesColorOsNmeaModel(): Boolean = VendorRegistry.active === ColorOs16Vendor

private fun clearNmeaAltitudeResolver() = synchronized(nmeaAltitudeResolverLock) {
    nmeaAltitudeResolver = null
}

private fun LocationHooker.resolveNmeaAltitudeOnWorker(
    config: JSONObject,
    motion: SpoofedMotion,
    ellipsoidMeters: Double
): NmeaAltitude? {
    if (Build.VERSION.SDK_INT < 35) return null
    val resolver = synchronized(nmeaAltitudeResolverLock) {
        if (retiringGeneration || readConfig() !== config) return null
        nmeaAltitudeResolver ?: run {
            lateinit var installed: NmeaAltitudeResolver
            installed = newAndroidNmeaAltitudeResolver(
                context = SystemHookUtils::getSystemContext,
                onWarmResult = { loaded ->
                    if (!retiringGeneration && nmeaAltitudeResolver === installed &&
                        readConfig()?.optBoolean("active", false) == true) {
                        HookStatus.workEventAndRequestSnapshot(
                            if (loaded) "nmea_altitude_model_loaded" else "nmea_altitude_model_unavailable"
                        )
                    }
                }
            )
            nmeaAltitudeResolver = installed
            installed
        }
    }
    return resolver.resolveOnWorker(config, motion.lat, motion.lng, ellipsoidMeters)
}

private fun buildModelNmeaSentences(
    motion: SpoofedMotion,
    altitude: NmeaAltitude?,
    accuracy: Float,
    config: JSONObject,
    epochMs: Long
): List<String> = NmeaEncoder.build(
    motion.lat, motion.lng, motion.speed, motion.bearing, altitude,
    accuracy, SatelliteProfile.count(config), config.optBoolean("enable_jitter", true), epochMs
)

/** 向 ILocationListener 代理对象主动反射调用 onLocationChanged */
private fun dispatchFakeLocationToListener(listener: Any, fakeLoc: Any) {
    val clazz = listener.javaClass
    val methods = clazz.methods

    // 优先尝试 onLocationChanged(Location)
    val singleLocMethod = methods.firstOrNull {
        it.name == "onLocationChanged" && it.parameterTypes.size == 1 &&
                LocationHooker.hasTypeByName(it.parameterTypes[0], "android.location.Location")
    }
    if (singleLocMethod != null) {
        singleLocMethod.invoke(listener, fakeLoc)
        return
    }

    // Android 12+ 批量位置接口: onLocationChanged(List<Location>, IRemoteCallback)
    val batchMethod = methods.firstOrNull {
        it.name == "onLocationChanged" && it.parameterTypes.isNotEmpty() &&
                List::class.java.isAssignableFrom(it.parameterTypes[0])
    }
    if (batchMethod != null) {
        val args = arrayOfNulls<Any>(batchMethod.parameterTypes.size)
        args[0] = listOf(fakeLoc)
        if (args.size > 1) {
            args[1] = createDummyRemoteCallback()
        }
        try {
            batchMethod.invoke(listener, *args)
        } catch (_: Throwable) {
            if (args.size > 1) {
                args[1] = null
                try { batchMethod.invoke(listener, *args) } catch (_: Throwable) {}
            }
        }
    }
}

private fun createDummyRemoteCallback(): Any {
    return object : android.os.Binder(), android.os.IInterface {
        override fun asBinder(): android.os.IBinder = this
        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
            reply?.writeNoException()
            return true
        }
    }
}

private fun createDummyCancellationSignal(): Any {
    return object : android.os.Binder(), android.os.IInterface {
        override fun asBinder(): android.os.IBinder = this
        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
            reply?.writeNoException()
            return true
        }
    }
}

internal fun LocationHooker.hookSystemLocationService(classLoader: ClassLoader) {
    val serviceClazz = SystemClassLocator.locate(SystemComponent.LOCATION_MANAGER_SERVICE, classLoader)

    if (serviceClazz == null) {
        logLoc("[SysHook] LocationManagerService not found, skip system-level location hook")
        return
    }
    logLoc("[SysHook] hookSystemLocationService invoked on ${serviceClazz.name}")
    if (hookedCallbackClasses.putIfAbsent(serviceClazz, true) != null) {
        return
    }

    nativeSignalGate = NativeSignalGate(this, classLoader) { reconcileNativeSignals() }.also { it.install() }
    rawGnssDelivery = RawGnssDelivery(this).also { it.install(serviceClazz) }
    startSystemLocationHeartbeat(classLoader)

    if (!VendorRegistry.active.usesFrameworkLocationDelivery) {
    // =========================================================================
    // 1. getLastLocation：同步返回，命中目标应用直接替换为伪造 Location
    // =========================================================================
    try {
        XposedHelpers.hookAllMethods(serviceClazz, "getLastLocation") { chain, _ ->
            val config = readConfig() ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
            val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
            val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config, overrideUid)

            if (!isTarget) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            val motion = getCurrentSpoofedMotion("WGS-84")
                ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())

            val provider = SystemHookUtils.extractProvider(chain.args)

            val fakeLoc = SystemHookUtils.buildFakeLocation(
                classLoader,
                provider,
                motion,
                RouteEngine.realisticAltitude(config),
                getJitteredAccuracy()
            )

            if (fakeLoc != null) {
                logLoc(
                    "[SysHook] Injected fake location for ${explicitPkg ?: "caller"} (getLastLocation): lat=${motion.lat}, lng=${motion.lng}, provider=$provider"
                )
                return@hookAllMethods fakeLoc
            }

            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        }
        logLoc("[SysHook] LocationManagerService.getLastLocation hooked")
    } catch (e: Throwable) {
        logLoc("[SysHook] hook getLastLocation failed: $e")
    }

    // =========================================================================
    // 2. registerLocationListener (Android 12+), requestLocationUpdates & registerLocationPendingIntent
    // =========================================================================
    val listenerRegisterMethodNames = arrayOf("registerLocationListener", "requestLocationUpdates", "registerLocationPendingIntent")
    for (methodName in listenerRegisterMethodNames) {
        try {
            XposedHelpers.hookAllMethods(serviceClazz, methodName) { chain, _ ->
                var attempt: NativeRegistrationAttempt? = null
                try {
                    val config = readConfig()
                    if (config != null) {
                        val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                        val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                        val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config, overrideUid)

                        if (isTarget) {
                            val listener = chain.args.firstOrNull { arg ->
                                arg != null && LocationHooker.hasTypeByName(
                                    arg.javaClass,
                                    "android.location.ILocationListener"
                                )
                            }
                            val provider = SystemHookUtils.extractProvider(chain.args)
                            if (listener != null) {
                                val binder = (listener as? IInterface)?.asBinder() ?: (listener as? IBinder)
                                val pkgName = explicitPkg ?: "unknown"
                                val registrationAttempt = NativeRegistrationAttempt(nativeLocationRegistrationLock)
                                attempt = registrationAttempt
                                registrationAttempt.trackListener(
                                    binder,
                                    ListenerRegistrationInfo(
                                        listener = listener,
                                        packageName = pkgName,
                                        provider = provider
                                    )
                                )
                                ensureCallbackHooked(listener, "onLocationChanged")

                                logLoc("[SysHook] Registered target location listener for ${explicitPkg ?: "caller"} (total active: ${activeListenerBinders.size})"
                                )

                                // ★ 核心修复: 注册瞬间立即主动同步派发首帧伪造位置，实现 0ms 瞬间定位，
                                // 彻底消除等待 1 秒心跳导致的定位重试与真实位置闪现
                                val motion = getCurrentSpoofedMotion("WGS-84")
                                if (motion != null) {
                                    val fakeLoc = SystemHookUtils.buildFakeLocation(
                                        classLoader,
                                        provider,
                                        motion,
                                        RouteEngine.realisticAltitude(config),
                                        getJitteredAccuracy()
                                    )
                                    if (fakeLoc != null) {
                                        try {
                                            dispatchFakeLocationToListener(listener, fakeLoc)
                                            logLoc("[SysHook] Proactively dispatched instant fake location to listener for ${explicitPkg ?: "caller"} (provider=$provider)"
                                            )
                                        } catch (t: Throwable) {
                                            logLoc("[SysHook] instant proactive dispatch failed: $t")
                                        }
                                    }
                                }
                            }

                            val pendingIntent = chain.args.firstOrNull { it is android.app.PendingIntent } as? android.app.PendingIntent
                            if (pendingIntent != null) {
                                val pkgName = explicitPkg ?: pendingIntent.creatorPackage ?: "unknown"
                                val registrationAttempt = attempt ?: NativeRegistrationAttempt(nativeLocationRegistrationLock)
                                attempt = registrationAttempt
                                registrationAttempt.trackPendingIntent(pendingIntent, pkgName)
                                logLoc("[SysHook] Registered target location PendingIntent for $pkgName (total active: ${activePendingIntents.size})")
                            }
                        }
                    }
                } catch (e: Throwable) {
                    logLoc("[SysHook] $methodName pre-hook error: $e")
                }
                val nativeCall = { chain.proceed(chain.args.toTypedArray()) }
                return@hookAllMethods if (attempt != null) attempt.proceed(nativeCall) else nativeCall()
            }
            logLoc("[SysHook] LocationManagerService.$methodName hooked")
        } catch (e: Throwable) {
            logLoc("[SysHook] hook $methodName failed: $e")
        }
    }

    // =========================================================================
    // 3. unregisterLocationListener (Android 12+) & removeUpdates (Android 8-11)
    // =========================================================================
    val unregisterMethodNames = arrayOf("unregisterLocationListener", "removeUpdates")
    for (methodName in unregisterMethodNames) {
        try {
            XposedHelpers.hookAllMethods(serviceClazz, methodName) { chain, _ ->
                try {
                    val listener = chain.args.firstOrNull { arg ->
                        arg != null && LocationHooker.hasTypeByName(
                            arg.javaClass,
                            "android.location.ILocationListener"
                        )
                    }
                    if (listener != null) {
                        val binder = (listener as? IInterface)?.asBinder() ?: (listener as? IBinder)
                        if (binder != null) {
                            activeListenerBinders.remove(binder)
                        }
                        spoofedListenerInstances.remove(listener)
                    }
                    val pendingIntent = chain.args.firstOrNull { it is android.app.PendingIntent } as? android.app.PendingIntent
                    if (pendingIntent != null) {
                        activePendingIntents.remove(pendingIntent)
                    }
                } catch (_: Throwable) {}
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // 4. getCurrentLocation：单次定位回调拦截与主动投递
    // =========================================================================
    try {
        XposedHelpers.hookAllMethods(serviceClazz, "getCurrentLocation") { chain, _ ->
            var attempt: NativeRegistrationAttempt? = null
            try {
                val config = readConfig()
                if (config != null) {
                    val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                    val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                    val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config, overrideUid)

                    if (isTarget) {
                        val callback = chain.args.firstOrNull { arg ->
                            arg != null && LocationHooker.hasTypeByName(
                                arg.javaClass,
                                "android.location.ILocationCallback"
                            )
                        }
                        if (callback != null) {
                            val binder = (callback as? IInterface)?.asBinder() ?: (callback as? IBinder)
                            val pkgName = explicitPkg ?: "caller"
                            val registrationAttempt = NativeRegistrationAttempt(nativeLocationRegistrationLock)
                            attempt = registrationAttempt
                            registrationAttempt.trackCallback(binder, CallbackRegistrationInfo(callback, pkgName))

                            ensureCallbackHooked(callback, "onLocation")
                            logLoc("[SysLoc] Registered getCurrentLocation callback for $pkgName (active callbacks: ${activeCallbackBinders.size})"
                            )

                            // 主动推送单次定位结果，防止在室内真实 GPS 未锁定导致目标 App 持续等待超时
                            val motion = getCurrentSpoofedMotion("WGS-84")
                            if (motion != null) {
                                val provider = SystemHookUtils.extractProvider(chain.args)
                                val fakeLoc = SystemHookUtils.buildFakeLocation(
                                    classLoader,
                                    provider,
                                    motion,
                                    RouteEngine.realisticAltitude(config),
                                    getJitteredAccuracy()
                                )
                                if (fakeLoc != null) {
                                    val onLocationMethod = callback.javaClass.methods.firstOrNull {
                                        it.name == "onLocation" && it.parameterTypes.size == 1
                                    }
                                    if (onLocationMethod != null) {
                                        try {
                                            onLocationMethod.invoke(callback, fakeLoc)
                                            logLoc("[SysLoc] Proactively dispatched fake location to getCurrentLocation callback for $pkgName (lat=${motion.lat}, lng=${motion.lng}, provider=$provider)"
                                            )
                                            return@hookAllMethods createDummyCancellationSignal().also { registrationAttempt.accept() }
                                        } catch (t: Throwable) {
                                            logLoc("[SysLoc] proactive callback invoke failed: $t")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                logLoc("[SysLoc] getCurrentLocation pre-hook failed: $e")
            }
            val nativeCall = { chain.proceed(chain.args.toTypedArray()) }
            return@hookAllMethods if (attempt != null) attempt.proceed(nativeCall) else nativeCall()
        }
        logLoc("[SysLoc] LocationManagerService.getCurrentLocation hooked")
    } catch (e: Throwable) {
        logLoc("[SysLoc] hook getCurrentLocation failed: $e")
    }

    }

    // =========================================================================
    // 5. isProviderEnabled & isProviderEnabledForUser：对目标应用强制汇报 GPS 可用
    // =========================================================================
    val providerEnabledMethods = arrayOf("isProviderEnabled", "isProviderEnabledForUser")
    for (methodName in providerEnabledMethods) {
        try {
            XposedHelpers.hookAllMethods(serviceClazz, methodName) { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) &&
                    (!com.vincenthzr.locationspoofer.xposed.hooks.vendor.VendorRegistry.active.requiresVirtualLocationOptIn ||
                        config.optBoolean("force_location_enabled", false))) {
                    val providerArg = SystemHookUtils.extractProvider(chain.args)
                    if (providerArg == "gps" || providerArg == "network" || providerArg == "passive" || providerArg == "fused") {
                        val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                        val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                        if (SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config, overrideUid)) {
                            return@hookAllMethods true
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // 6. GNSS 卫星与 NMEA 拦截与注册 (registerGnssStatusCallback / registerGnssNmeaCallback)
    // =========================================================================
    val registerGnssMethods = arrayOf("registerGnssStatusCallback", "addGpsStatusListener")
    for (mName in registerGnssMethods) {
        runCatching {
            XposedHelpers.hookAllMethods(serviceClazz, mName) { chain, _ -> registerSignalCallback(chain, false) }
        }
    }

    val unregisterGnssMethods = arrayOf("unregisterGnssStatusCallback", "removeGpsStatusListener")
    for (mName in unregisterGnssMethods) {
        try {
            XposedHelpers.hookAllMethods(serviceClazz, mName) { chain, _ ->
                val callback = chain.args.firstOrNull { arg ->
                    arg != null && (arg is IInterface || arg is IBinder)
                }
                if (callback != null) {
                    val binder = (callback as? IInterface)?.asBinder() ?: (callback as? IBinder)
                    if (binder != null) {
                        activeGnssStatusBinders.remove(binder)
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    val registerNmeaMethods = arrayOf("registerGnssNmeaCallback", "addNmeaListener")
    for (mName in registerNmeaMethods) {
        runCatching {
            XposedHelpers.hookAllMethods(serviceClazz, mName) { chain, _ -> registerSignalCallback(chain, true) }
        }
    }

    val unregisterNmeaMethods = arrayOf("unregisterGnssNmeaCallback", "removeNmeaListener")
    for (mName in unregisterNmeaMethods) {
        try {
            XposedHelpers.hookAllMethods(serviceClazz, mName) { chain, _ ->
                val callback = chain.args.firstOrNull { arg ->
                    arg != null && (arg is IInterface || arg is IBinder)
                }
                if (callback != null) {
                    val binder = (callback as? IInterface)?.asBinder() ?: (callback as? IBinder)
                    if (binder != null) {
                        activeGnssNmeaBinders.remove(binder)
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    if (!VendorRegistry.active.usesFrameworkLocationDelivery) {
    // =========================================================================
    // 7. LocationProviderManager 底层引擎挂载 (Android 12+)
    // 直接在 providerManager 层拦截 getLastLocation 与 getCurrentLocation，
    // 全面覆盖所有原生与定制 provider ("gps", "network", "fused", "passive")
    // =========================================================================
    val providerManagerClazz = SystemClassLocator.locate(SystemComponent.LOCATION_PROVIDER_MANAGER, classLoader)
    if (providerManagerClazz != null && hookedCallbackClasses.putIfAbsent(providerManagerClazz, true) == null) {
        try {
            val getLastLocationMethods = arrayOf("getLastLocation", "getLastLocationUnsafe")
            for (mName in getLastLocationMethods) {
                XposedHelpers.hookAllMethods(providerManagerClazz, mName) { chain, _ ->
                    val config = readConfig() ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    val pkgName = SystemHookUtils.extractPackageName(chain.args)
                    val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                    val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, pkgName, config, overrideUid)
                    if (isTarget) {
                        val motion = getCurrentSpoofedMotion("WGS-84")
                        if (motion != null) {
                            val provider = try {
                                XposedHelpers.getObjectField(chain.thisObject, "mName") as? String
                            } catch (_: Throwable) { null } ?: SystemHookUtils.extractProvider(chain.args)
                            val fakeLoc = SystemHookUtils.buildFakeLocation(
                                classLoader,
                                provider,
                                motion,
                                RouteEngine.realisticAltitude(config),
                                getJitteredAccuracy()
                            )
                            if (fakeLoc != null) {
                                logLoc("[SysLoc] LocationProviderManager.$mName intercepted for $pkgName (lat=${motion.lat}, lng=${motion.lng}, provider=$provider)")
                                return@hookAllMethods fakeLoc
                            }
                        }
                    }
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
            }
            logLoc("[SysLoc] LocationProviderManager.getLastLocation hooked")
        } catch (e: Throwable) {
            logLoc("[SysLoc] hook LocationProviderManager.getLastLocation failed: $e")
        }

        try {
            XposedHelpers.hookAllMethods(providerManagerClazz, "getCurrentLocation") { chain, _ ->
                var attempt: NativeRegistrationAttempt? = null
                try {
                    val config = readConfig()
                    if (config != null) {
                        val pkgName = SystemHookUtils.extractPackageName(chain.args)
                        val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                        val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, pkgName, config, overrideUid)
                        if (isTarget) {
                            val callback = chain.args.firstOrNull { arg ->
                                arg != null && LocationHooker.hasTypeByName(
                                    arg.javaClass,
                                    "android.location.ILocationCallback"
                                )
                            }
                            if (callback != null) {
                                val binder = (callback as? IInterface)?.asBinder() ?: (callback as? IBinder)
                                val targetPkg = pkgName ?: "caller"
                                val registrationAttempt = NativeRegistrationAttempt(nativeLocationRegistrationLock)
                                attempt = registrationAttempt
                                registrationAttempt.trackCallback(binder, CallbackRegistrationInfo(callback, targetPkg))
                                ensureCallbackHooked(callback, "onLocation")
                                val motion = getCurrentSpoofedMotion("WGS-84")
                                if (motion != null) {
                                    val provider = try {
                                        XposedHelpers.getObjectField(chain.thisObject, "mName") as? String
                                    } catch (_: Throwable) { null } ?: SystemHookUtils.extractProvider(chain.args)
                                    val fakeLoc = SystemHookUtils.buildFakeLocation(
                                        classLoader,
                                        provider,
                                        motion,
                                        RouteEngine.realisticAltitude(config),
                                        getJitteredAccuracy()
                                    )
                                    if (fakeLoc != null) {
                                        val onLocMethod = callback.javaClass.methods.firstOrNull {
                                            it.name == "onLocation" && it.parameterTypes.size == 1
                                        }
                                        onLocMethod?.invoke(callback, fakeLoc)
                                        logLoc("[SysLoc] LocationProviderManager.getCurrentLocation proactive dispatch for $targetPkg (lat=${motion.lat}, lng=${motion.lng}, provider=$provider)")
                                        return@hookAllMethods createDummyCancellationSignal().also { registrationAttempt.accept() }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
                val nativeCall = { chain.proceed(chain.args.toTypedArray()) }
                return@hookAllMethods if (attempt != null) attempt.proceed(nativeCall) else nativeCall()
            }
            logLoc("[SysLoc] LocationProviderManager.getCurrentLocation hooked")
        } catch (e: Throwable) {
            logLoc("[SysLoc] hook LocationProviderManager.getCurrentLocation failed: $e")
        }

        val registerMethodNames = arrayOf("registerLocationRequest", "registerLocationListener", "registerLocationPendingIntent")
        for (mName in registerMethodNames) {
            try {
                XposedHelpers.hookAllMethods(providerManagerClazz, mName) { chain, _ ->
                    var attempt: NativeRegistrationAttempt? = null
                    val config = readConfig()
                    if (config != null) {
                        val pkgName = SystemHookUtils.extractPackageName(chain.args)
                        val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                        val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, pkgName, config, overrideUid)
                        if (isTarget) {
                            var listener = chain.args.firstOrNull { arg ->
                                arg != null && (LocationHooker.hasTypeByName(arg.javaClass, "android.location.ILocationListener") ||
                                        arg.javaClass.simpleName.contains("Listener"))
                            }
                            if (listener == null) {
                                for (arg in chain.args) {
                                    if (arg != null && arg.javaClass.simpleName.contains("Registration")) {
                                        try {
                                            listener = XposedHelpers.getObjectField(arg, "mListener")
                                            if (listener != null) break
                                        } catch (_: Throwable) {}
                                        try {
                                            listener = XposedHelpers.callMethod(arg, "getListener")
                                            if (listener != null) break
                                        } catch (_: Throwable) {}
                                    }
                                }
                            }
                            val providerName = try {
                                XposedHelpers.getObjectField(chain.thisObject, "mName") as? String
                            } catch (_: Throwable) { null } ?: SystemHookUtils.extractProvider(chain.args)

                            if (listener != null) {
                                val binder = (listener as? IInterface)?.asBinder() ?: (listener as? IBinder)
                                val registrationAttempt = NativeRegistrationAttempt(nativeLocationRegistrationLock)
                                attempt = registrationAttempt
                                registrationAttempt.trackListener(
                                    binder,
                                    ListenerRegistrationInfo(
                                        listener = listener,
                                        packageName = pkgName ?: "target",
                                        provider = providerName
                                    )
                                )
                                ensureCallbackHooked(listener, "onLocationChanged")

                                // 立即主动投递首帧伪造位置
                                val motion = getCurrentSpoofedMotion("WGS-84")
                                if (motion != null) {
                                    val fakeLoc = SystemHookUtils.buildFakeLocation(
                                        classLoader,
                                        providerName,
                                        motion,
                                        RouteEngine.realisticAltitude(config),
                                        getJitteredAccuracy()
                                    )
                                    if (fakeLoc != null) {
                                        try {
                                            dispatchFakeLocationToListener(listener, fakeLoc)
                                            logLoc("[SysLoc] LocationProviderManager.$mName proactive dispatch for $pkgName (lat=${motion.lat}, lng=${motion.lng}, provider=$providerName)")
                                        } catch (t: Throwable) {
                                            logLoc("[SysLoc] proactive dispatch error: $t")
                                        }
                                    }
                                }
                                logLoc("[SysLoc] LocationProviderManager.$mName captured for $pkgName (active listeners: ${activeListenerBinders.size})")
                            }

                            val pi = chain.args.firstOrNull { it is android.app.PendingIntent } as? android.app.PendingIntent
                            if (pi != null) {
                                val targetPkg = pkgName ?: pi.creatorPackage ?: "target"
                                val registrationAttempt = attempt ?: NativeRegistrationAttempt(nativeLocationRegistrationLock)
                                attempt = registrationAttempt
                                registrationAttempt.trackPendingIntent(pi, targetPkg)
                                logLoc("[SysLoc] LocationProviderManager.$mName PendingIntent captured for $targetPkg")
                            }
                        }
                    }
                    val nativeCall = { chain.proceed(chain.args.toTypedArray()) }
                    return@hookAllMethods if (attempt != null) attempt.proceed(nativeCall) else nativeCall()
                }
                logLoc("[SysLoc] LocationProviderManager.$mName hooked")
            } catch (e: Throwable) {
                logLoc("[SysLoc] hook LocationProviderManager.$mName failed: $e")
            }
        }

        val unregisterMethods = arrayOf("unregisterLocationRequest", "unregisterLocationListener")
        for (mName in unregisterMethods) {
            try {
                XposedHelpers.hookAllMethods(providerManagerClazz, mName) { chain, _ ->
                    try {
                        for (arg in chain.args) {
                            if (arg != null) {
                                val binder = (arg as? IInterface)?.asBinder() ?: (arg as? IBinder)
                                if (binder != null) {
                                    activeListenerBinders.remove(binder)
                                }
                                spoofedListenerInstances.remove(arg)
                            }
                        }
                    } catch (_: Throwable) {}
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
            } catch (_: Throwable) {}
        }

        // 核心修复: 拦截底层 Provider 上报真实位置 (onReportLocation)
        // 在全局模拟模式下，直接就地改写 LocationResult，防止 MetokNLP/GPS 真实数据污染系统内部缓存
        try {
            XposedHelpers.hookAllMethods(providerManagerClazz, "onReportLocation") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val isGlobal = config.optBoolean("system_hook_global_mode", false)
                    if (isGlobal) {
                        val motion = getCurrentSpoofedMotion("WGS-84")
                        if (motion != null) {
                            rewriteLocationArgs(chain.args, motion, RouteEngine.realisticAltitude(config), getJitteredAccuracy())
                            logLoc("[SysLoc] LocationProviderManager.onReportLocation rewrote location globally")
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            logLoc("[SysLoc] LocationProviderManager.onReportLocation hooked")
        } catch (e: Throwable) {
            logLoc("[SysLoc] hook LocationProviderManager.onReportLocation failed: $e")
        }

        // 核心修复: 拦截向目标注册客户端 (Registration) 分发真实位置的关键节点 acceptLocationChange
        // 彻底解决抖音/淘宝两边跳：当 MetokNLP 或真实 GPS 产生定位推向目标 App 时，在 Registration 处直接改写为模拟坐标
        val regClasses = listOfNotNull(
            XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager\$Registration", classLoader),
            XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager\$LocationRegistration", classLoader),
            XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager\$LocationListenerRegistration", classLoader)
        )
        for (regClazz in regClasses) {
            if (hookedCallbackClasses.putIfAbsent(regClazz, true) != null) continue
            try {
                XposedHelpers.hookAllMethods(regClazz, "acceptLocationChange") { chain, _ ->
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val identity = try {
                            XposedHelpers.getObjectField(chain.thisObject, "mIdentity")
                        } catch (_: Throwable) {
                            try { XposedHelpers.callMethod(chain.thisObject, "getIdentity") } catch (_: Throwable) { null }
                        }
                        val pkg = identity?.let {
                            try { XposedHelpers.callMethod(it, "getPackageName") as? String } catch (_: Throwable) {
                                try { XposedHelpers.getObjectField(it, "mPackageName") as? String } catch (_: Throwable) { null }
                            }
                        }
                        val uid = identity?.let {
                            try { XposedHelpers.callMethod(it, "getUid") as? Int } catch (_: Throwable) {
                                try { XposedHelpers.getIntField(it, "mUid") } catch (_: Throwable) { null }
                            }
                        }
                        val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, pkg, config, uid)
                        if (isTarget) {
                            val motion = getCurrentSpoofedMotion("WGS-84")
                            if (motion != null) {
                                rewriteLocationArgs(chain.args, motion, RouteEngine.realisticAltitude(config), getJitteredAccuracy())
                                logLoc("[SysLoc] Registration.acceptLocationChange rewrote location for $pkg (lat=${motion.lat}, lng=${motion.lng})")
                            }
                        }
                    }
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
                logLoc("[SysLoc] ${regClazz.name}.acceptLocationChange hooked")
            } catch (e: Throwable) {
                logLoc("[SysLoc] hook ${regClazz.name}.acceptLocationChange failed: $e")
            }
        }

        // 核心修复: 拦截向目标 Binder 派发位置的 ProviderTransport
        val transportClasses = listOfNotNull(
            XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager\$LocationListenerTransport", classLoader),
            XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager\$LocationPendingIntentTransport", classLoader),
            XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager\$GetCurrentLocationTransport", classLoader)
        )
        for (tClazz in transportClasses) {
            if (hookedCallbackClasses.putIfAbsent(tClazz, true) != null) continue
            try {
                XposedHelpers.hookAllMethods(tClazz, "deliverOnLocationChanged") { chain, _ ->
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val isGlobal = config.optBoolean("system_hook_global_mode", false)
                        var isTarget = isGlobal
                        if (!isTarget) {
                            val listener = try { XposedHelpers.getObjectField(chain.thisObject, "mListener") } catch (_: Throwable) { null }
                            val binder = (listener as? IInterface)?.asBinder() ?: (listener as? IBinder)
                            if (binder != null && activeListenerBinders.containsKey(binder)) {
                                isTarget = true
                            } else if (listener != null && spoofedListenerInstances.containsKey(listener)) {
                                isTarget = true
                            }
                            if (!isTarget) {
                                val pi = try { XposedHelpers.getObjectField(chain.thisObject, "mPendingIntent") as? android.app.PendingIntent } catch (_: Throwable) { null }
                                if (pi != null && activePendingIntents.containsKey(pi)) {
                                    isTarget = true
                                }
                            }
                            if (!isTarget) {
                                val cb = try { XposedHelpers.getObjectField(chain.thisObject, "mCallback") } catch (_: Throwable) { null }
                                val cbBinder = (cb as? IInterface)?.asBinder() ?: (cb as? IBinder)
                                if (cbBinder != null && activeCallbackBinders.containsKey(cbBinder)) {
                                    isTarget = true
                                } else if (cb != null && spoofedListenerInstances.containsKey(cb)) {
                                    isTarget = true
                                }
                            }
                        }
                        if (isTarget) {
                            val motion = getCurrentSpoofedMotion("WGS-84")
                            if (motion != null) {
                                rewriteLocationArgs(chain.args, motion, RouteEngine.realisticAltitude(config), getJitteredAccuracy())
                                logLoc("[SysLoc] ${tClazz.simpleName}.deliverOnLocationChanged rewrote location (lat=${motion.lat}, lng=${motion.lng})")
                            }
                        }
                    }
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
                logLoc("[SysLoc] ${tClazz.name}.deliverOnLocationChanged hooked")
            } catch (e: Throwable) {
                logLoc("[SysLoc] hook ${tClazz.name}.deliverOnLocationChanged failed: $e")
            }
        }
    }

    }

    // =========================================================================
    // 8. 逆地理编码 Geocoder 拦截 (reverseGeocode / getFromLocation)
    // 防止系统 Geocoder（如小米 MetokGeocodeService / MetokNLP）返回真实住址
    // =========================================================================
    val geocodeMethods = arrayOf("reverseGeocode", "forwardGeocode", "getFromLocation")
    for (mName in geocodeMethods) {
        try {
            XposedHelpers.hookAllMethods(serviceClazz, mName) { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                    val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                    val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config, overrideUid)
                    if (isTarget) {
                        val motion = getCurrentSpoofedMotion("GCJ-02")
                        if (motion != null) {
                            try {
                                val addressClass = XposedHelpers.findClass("android.location.Address", classLoader)
                                val addressObj = XposedHelpers.newInstance(addressClass, java.util.Locale.getDefault())
                                XposedHelpers.callMethod(addressObj, "setLatitude", motion.lat)
                                XposedHelpers.callMethod(addressObj, "setLongitude", motion.lng)
                                val province = cachedProvince.ifEmpty { "北京市" }
                                val city = cachedCity.ifEmpty { "北京市" }
                                val district = cachedDistrict.ifEmpty { "东城区" }
                                val street = cachedStreet.ifEmpty { "南长街" }
                                val fullAddr = cachedAddress.ifEmpty { "$province$city$district$street" }
                                XposedHelpers.callMethod(addressObj, "setAdminArea", province)
                                XposedHelpers.callMethod(addressObj, "setLocality", city)
                                XposedHelpers.callMethod(addressObj, "setSubLocality", district)
                                XposedHelpers.callMethod(addressObj, "setThoroughfare", street)
                                XposedHelpers.callMethod(addressObj, "setAddressLine", 0, fullAddr)

                                // 1. 回调式接口 (Android 13+ IGeocodeListener)
                                val listener = chain.args.firstOrNull { arg ->
                                    arg != null && (arg is IInterface || arg is IBinder ||
                                            arg.javaClass.simpleName.contains("Listener") ||
                                            arg.javaClass.simpleName.contains("Callback"))
                                }
                                if (listener != null) {
                                    val onResultsMethod = listener.javaClass.methods.firstOrNull {
                                        it.name == "onResults" || it.name == "onGeocode" || it.name == "onLocation"
                                    }
                                    if (onResultsMethod != null) {
                                        val params = arrayOfNulls<Any>(onResultsMethod.parameterTypes.size)
                                        for (i in params.indices) {
                                            val pType = onResultsMethod.parameterTypes[i]
                                            if (List::class.java.isAssignableFrom(pType)) {
                                                params[i] = listOf(addressObj)
                                            } else if (pType == addressClass) {
                                                params[i] = addressObj
                                            }
                                        }
                                        onResultsMethod.invoke(listener, *params)
                                        logLoc("[SysLoc] Intercepted $mName (callback) for ${explicitPkg ?: "caller"}, returned: $fullAddr")
                                        return@hookAllMethods null
                                    }
                                }

                                // 2. 入参集合修改 (Android 11-12 inout List<Address>)
                                @Suppress("UNCHECKED_CAST")
                                val addrs = chain.args.firstOrNull { it is List<*> } as? MutableList<Any>
                                if (addrs != null) {
                                    addrs.clear()
                                    addrs.add(addressObj)
                                    logLoc("[SysLoc] Intercepted $mName (inout list) for ${explicitPkg ?: "caller"}, returned: $fullAddr")
                                    return@hookAllMethods null
                                }

                                logLoc("[SysLoc] Intercepted $mName for ${explicitPkg ?: "caller"}, returned: $fullAddr")
                                return@hookAllMethods listOf(addressObj)
                            } catch (e: Throwable) {
                                logLoc("[SysLoc] mock Geocoder Address failed: $e")
                            }
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            logLoc("[SysLoc] LocationManagerService.$mName hooked")
        } catch (e: Throwable) {
            logLoc("[SysLoc] hook $mName failed: $e")
        }
    }

    if (!VendorRegistry.active.usesFrameworkLocationDelivery) hookPendingIntentDelivery(classLoader)
}

/** 拦截 PendingIntent.send(...) 改写通过 PendingIntent 投递给目标应用的 Location 广播与服务 Intent */
private fun LocationHooker.hookPendingIntentDelivery(classLoader: ClassLoader) {
    val piClass = XposedHelpers.findClassIfExists("android.app.PendingIntent", classLoader) ?: return
    if (hookedCallbackClasses.putIfAbsent(piClass, true) != null) return

    XposedHelpers.hookAllMethods(piClass, "send") { chain, _ ->
        val config = readConfig()
        if (config != null && config.optBoolean("active", false)) {
            val pi = chain.thisObject as? android.app.PendingIntent
            val isTarget = if (pi != null && activePendingIntents.containsKey(pi)) {
                true
            } else {
                val pkg = pi?.creatorPackage
                pkg != null && SystemHookUtils.isTargetCaller(chain.thisObject, pkg, config)
            }
            if (isTarget) {
                val motion = getCurrentSpoofedMotion("WGS-84")
                if (motion != null) {
                    val altitude = RouteEngine.realisticAltitude(config)
                    val accuracy = getJitteredAccuracy()
                    for (arg in chain.args) {
                        if (arg is android.content.Intent) {
                            @Suppress("DEPRECATION")
                            val loc = arg.getParcelableExtra<android.location.Location>(android.location.LocationManager.KEY_LOCATION_CHANGED)
                                ?: arg.getParcelableExtra<android.location.Location>("location")
                            if (loc != null) {
                                SystemHookUtils.applyFakeLocationFields(loc, motion, altitude, accuracy)
                            }
                            try {
                                @Suppress("DEPRECATION")
                                val locList = arg.getParcelableArrayListExtra<android.location.Location>("locations")
                                locList?.forEach { SystemHookUtils.applyFakeLocationFields(it, motion, altitude, accuracy) }
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
        }
        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
    }
    logLoc("[SysLoc] PendingIntent.send hooked for location intent rewrite")
}

/** Hook IGnssStatusListener 实例的 onSvStatusChanged 回调，注入 20+ 真实卫星矩阵 */
private fun LocationHooker.hookGnssStatusListenerCallback(listener: Any) {
    val clazz = listener.javaClass
    if (hookedCallbackClasses.putIfAbsent(clazz, true) != null) return

    try {
        XposedHelpers.hookAllMethods(clazz, "onSvStatusChanged") { chain, _ ->
            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                val args = chain.args
                val satCount = SatelliteProfile.count(config)
                val enableJitter = config.optBoolean("enable_jitter", true)

                if (args.size == 1) {
                    // Android 11+ 标准：GnssStatus 单参数
                    val fakeStatus = GnssFastMockEngine.getOrCreateSpoofedGnssStatus(
                        clazz.classLoader ?: ClassLoader.getSystemClassLoader(),
                        satCount,
                        enableJitter,
                        args[0]
                    )
                    if (fakeStatus != null) {
                        return@hookAllMethods chain.proceed(arrayOf(fakeStatus))
                    }
                } else if (args.size >= 6) {
                    // Android 7~10 兼容：6 个数组参数 (svCount, svidWithFlags, cn0DbHz, elevations, azimuths, carrierFrequencies)
                    val loader = clazz.classLoader ?: ClassLoader.getSystemClassLoader()
                    val sky = SatelliteSky.snapshot(satCount, System.currentTimeMillis(), enableJitter)
                    val svCount = sky.size
                    val svidWithFlags = sky.map { GnssFastMockEngine.packedSatellite(it, loader) }.toIntArray()
                    val cn0DbHz = sky.map { it.cn0 }.toFloatArray()
                    val elevations = sky.map { it.elevation }.toFloatArray()
                    val azimuths = sky.map { it.azimuth }.toFloatArray()
                    val carrierFrequencies = sky.map { it.carrierHz ?: 0f }.toFloatArray()

                    val newArgs = args.toMutableList()
                    newArgs[0] = svCount
                    newArgs[1] = svidWithFlags
                    newArgs[2] = cn0DbHz
                    newArgs[3] = elevations
                    newArgs[4] = azimuths
                    newArgs[5] = carrierFrequencies
                    if (newArgs.size >= 7) newArgs[6] = sky.map { (it.cn0 - 2.5f).coerceAtLeast(26f) }.toFloatArray()
                    return@hookAllMethods chain.proceed(newArgs.toTypedArray())
                }
            }
            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        }
    } catch (_: Throwable) {}
}
