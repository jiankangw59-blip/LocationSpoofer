package com.vincenthzr.locationspoofer.xposed.hooks

import com.vincenthzr.locationspoofer.xposed.utils.XposedBridge
import com.vincenthzr.locationspoofer.xposed.utils.XposedHelpers
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 高性能零分配卫星模拟引擎 (High-Performance Zero-Allocation GNSS/GPS Simulation Engine)
 *
 * 核心优化特性:
 * 1. 单例反射预热与句柄缓存: 仅在首次使用时初始化 Method / Field 引用，彻底消除高频回调中的反射查找。
 * 2. 零 GC 预计算轨道矩阵: 预分配多星座结构体，基于时间戳步进计算平滑轨道与可信 C/N0，不再每秒创建数百个 Random 实例。
 * 3. 1Hz 原子快照缓存: 无论系统 GNSS 回调触发频率多高 (10Hz~20Hz)，均以纳秒级极速直接复用 1 秒内的预构建快照对象。
 * 4. 全版本覆盖: 深度兼容 Android 11+ (GnssStatus.Builder), Android 7~10 (GnssStatus 内部结构), 以及 Legacy GpsStatus。
 */
object GnssFastMockEngine {

    private const val MAX_SATELLITES = 32

    // 预置可信多星座模板 (GPS=1, GLONASS=3, BDS=5, GALILEO=6)
    @Volatile
    private var cachedGnssStatusObj: Any? = null
    @Volatile
    private var lastGnssStatusTimeMs: Long = 0L
    @Volatile
    private var cachedGnssCount: Int = 0
    @Volatile private var cachedJitter: Boolean = false

    @Volatile
    private var cachedGpsSatelliteList: List<Any>? = null
    @Volatile
    private var lastGpsSatellitesTimeMs: Long = 0L
    @Volatile
    private var cachedGpsSatCount: Int = 0
    @Volatile private var cachedGpsJitter: Boolean = false

    // 反射句柄缓存
    private class ClassReflectionHolder(val classLoader: ClassLoader) {
        var isInitialized = false

        // Android 11+ GnssStatus.Builder
        var builderClass: Class<*>? = null
        var builderConstructor: Constructor<*>? = null
        var addSatelliteMethod: Method? = null
        var buildMethod: Method? = null

        // Android 7-10 GnssStatus Direct Fields
        var gnssStatusClass: Class<*>? = null
        var countField: Field? = null
        var svidWithFlagsField: Field? = null
        var cn0DbHzField: Field? = null
        var elevationsField: Field? = null
        var azimuthsField: Field? = null
        var carrierFrequenciesField: Field? = null
        var basebandCn0DbHzsField: Field? = null
        var svidShift = if (android.os.Build.VERSION.SDK_INT >= 30) 12 else 8
        var constellationShift = if (android.os.Build.VERSION.SDK_INT >= 30) 8 else 4

        // GpsSatellite
        var gpsSatelliteClass: Class<*>? = null
        var gpsSatelliteConstructor: Constructor<*>? = null
        var satValidField: Field? = null
        var satHasEphemerisField: Field? = null
        var satHasAlmanacField: Field? = null
        var satUsedInFixField: Field? = null
        var satSnrField: Field? = null
        var satElevationField: Field? = null
        var satAzimuthField: Field? = null

        fun init() {
            if (isInitialized) return
            synchronized(this) {
                if (isInitialized) return

                // 1. GnssStatus.Builder (Android 11+)
                try {
                    builderClass = XposedHelpers.findClassIfExists(
                        "android.location.GnssStatus\$Builder",
                        classLoader
                    )
                    if (builderClass != null) {
                        builderConstructor = builderClass!!.getDeclaredConstructor().apply { isAccessible = true }
                        addSatelliteMethod = builderClass!!.getDeclaredMethod(
                            "addSatellite",
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType
                        ).apply { isAccessible = true }
                        buildMethod = builderClass!!.getDeclaredMethod("build").apply { isAccessible = true }
                    }
                } catch (e: Throwable) {
                    // Ignored on older Android
                }

                // 2. GnssStatus Fields (Android 7-10)
                try {
                    gnssStatusClass = XposedHelpers.findClassIfExists("android.location.GnssStatus", classLoader)
                    if (gnssStatusClass != null) {
                        fun field(vararg names: String): Field? = names.firstNotNullOfOrNull { name ->
                            runCatching { gnssStatusClass!!.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
                        }
                        countField = field("mSvCount")
                        svidWithFlagsField = field("mSvidWithFlags")
                        cn0DbHzField = field("mCn0DbHzs", "mCn0DbHz")
                        elevationsField = field("mElevations")
                        azimuthsField = field("mAzimuths")
                        carrierFrequenciesField = field("mCarrierFrequencies")
                        basebandCn0DbHzsField = field("mBasebandCn0DbHzs")
                        field("SVID_SHIFT_WIDTH")?.let { svidShift = it.getInt(null) }
                        field("CONSTELLATION_TYPE_SHIFT_WIDTH")?.let { constellationShift = it.getInt(null) }
                    }
                } catch (e: Throwable) {
                }

                // 3. GpsSatellite
                try {
                    gpsSatelliteClass = XposedHelpers.findClassIfExists("android.location.GpsSatellite", classLoader)
                    if (gpsSatelliteClass != null) {
                        gpsSatelliteConstructor = gpsSatelliteClass!!.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }
                        try { satValidField = gpsSatelliteClass!!.getDeclaredField("mValid").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satHasEphemerisField = gpsSatelliteClass!!.getDeclaredField("mHasEphemeris").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satHasAlmanacField = gpsSatelliteClass!!.getDeclaredField("mHasAlmanac").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satUsedInFixField = gpsSatelliteClass!!.getDeclaredField("mUsedInFix").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satSnrField = gpsSatelliteClass!!.getDeclaredField("mSnr").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satElevationField = gpsSatelliteClass!!.getDeclaredField("mElevation").apply { isAccessible = true } } catch (_: Throwable) {}
                        try { satAzimuthField = gpsSatelliteClass!!.getDeclaredField("mAzimuth").apply { isAccessible = true } } catch (_: Throwable) {}
                    }
                } catch (e: Throwable) {
                }

                isInitialized = true
            }
        }
    }

    private val reflectionHolders = ConcurrentHashMap<ClassLoader, ClassReflectionHolder>()

    private fun getReflectionHolder(classLoader: ClassLoader): ClassReflectionHolder {
        val holder = reflectionHolders.getOrPut(classLoader) { ClassReflectionHolder(classLoader) }
        if (!holder.isInitialized) {
            holder.init()
        }
        return holder
    }

    /**
     * 极速获取或创建 GnssStatus 快照对象
     * 有效期内直接返回原子缓存，耗时 < 0.01ms (纳秒级)
     */
    fun getOrCreateSpoofedGnssStatus(
        classLoader: ClassLoader,
        targetCount: Int,
        enableJitter: Boolean,
        fallbackOriginalObj: Any?
    ): Any? {
        val now = System.currentTimeMillis()
        val count = targetCount.coerceIn(4, MAX_SATELLITES)

        // 1 秒内命中快照直接返回
        val cached = cachedGnssStatusObj
        if (cached != null && cachedGnssCount == count && now / 1000L == lastGnssStatusTimeMs / 1000L && cachedJitter == enableJitter) {
            return cached
        }

        synchronized(this) {
            // Double check
            if (cachedGnssStatusObj != null && cachedGnssCount == count && now / 1000L == lastGnssStatusTimeMs / 1000L && cachedJitter == enableJitter) {
                return cachedGnssStatusObj
            }

            val holder = getReflectionHolder(classLoader)
            val sky = SatelliteSky.snapshot(count, now, enableJitter)

            // 方案 A: Android 11+ Builder 极速构建 (全反射句柄已缓存)
            if (holder.builderConstructor != null && holder.addSatelliteMethod != null && holder.buildMethod != null) {
                try {
                    val builder = holder.builderConstructor!!.newInstance()
                    val addMethod = holder.addSatelliteMethod!!

                    for (i in 0 until count) {
                        val satellite = sky[i]
                        val type = satellite.constellation
                        val svid = satellite.svid
                        val cn0 = satellite.cn0
                        val elevation = satellite.elevation
                        val azimuth = satellite.azimuth
                        val usedInFix = satellite.used
                        val carrierFreq = satellite.carrierHz ?: 0f
                        val basebandCn0 = (cn0 - 2.5f).coerceAtLeast(26f)

                        addMethod.invoke(
                            builder,
                            type,
                            svid,
                            cn0,
                            elevation,
                            azimuth,
                            true,             // hasEphemeris
                            true,             // hasAlmanac
                            usedInFix,        // usedInFix
                            satellite.carrierHz != null, // hasCarrierFrequency
                            carrierFreq,      // carrierFrequencyHz
                            true,             // hasBasebandCn0
                            basebandCn0       // basebandCn0DbHz
                        )
                    }

                    val newStatus = holder.buildMethod!!.invoke(builder)
                    cachedGnssStatusObj = newStatus
                    lastGnssStatusTimeMs = now
                    cachedGnssCount = count
                    cachedJitter = enableJitter
                    return newStatus
                } catch (e: Throwable) {
                    XposedBridge.log("[GnssFastMockEngine] Builder build failed: $e")
                }
            }

            // 方案 B: Android 7~10 字段直接写入 (零额外实例化)
            if (fallbackOriginalObj != null && holder.countField != null && holder.svidWithFlagsField != null) {
                try {
                    holder.countField!!.setInt(fallbackOriginalObj, count)

                    val cn0DbHzs = FloatArray(count)
                    val elevations = FloatArray(count)
                    val azimuths = FloatArray(count)
                    val svidWithFlags = IntArray(count)
                    val carrierFreqs = FloatArray(count)
                    val basebandCn0s = FloatArray(count)

                    for (i in 0 until count) {
                        val satellite = sky[i]
                        val type = satellite.constellation
                        val svid = satellite.svid
                        val cn0 = satellite.cn0
                        val elevation = satellite.elevation
                        val azimuth = satellite.azimuth
                        val usedInFix = satellite.used
                        val carrierFreq = satellite.carrierHz ?: 0f

                        cn0DbHzs[i] = cn0
                        elevations[i] = elevation
                        azimuths[i] = azimuth
                        carrierFreqs[i] = carrierFreq
                        basebandCn0s[i] = cn0 - 2.5f

                        svidWithFlags[i] = SatelliteSky.packed(satellite, holder.svidShift,
                            holder.constellationShift, holder.basebandCn0DbHzsField != null)
                    }

                    holder.svidWithFlagsField!!.set(fallbackOriginalObj, svidWithFlags)
                    holder.cn0DbHzField?.set(fallbackOriginalObj, cn0DbHzs)
                    holder.elevationsField?.set(fallbackOriginalObj, elevations)
                    holder.azimuthsField?.set(fallbackOriginalObj, azimuths)
                    holder.carrierFrequenciesField?.set(fallbackOriginalObj, carrierFreqs)
                    holder.basebandCn0DbHzsField?.set(fallbackOriginalObj, basebandCn0s)

                    cachedGnssStatusObj = fallbackOriginalObj
                    lastGnssStatusTimeMs = now
                    cachedGnssCount = count
                    cachedJitter = enableJitter
                    return fallbackOriginalObj
                } catch (e: Throwable) {
                    XposedBridge.log("[GnssFastMockEngine] Fallback field injection failed: $e")
                }
            }
        }

        return fallbackOriginalObj
    }

    internal fun packedSatellite(value: SatelliteSky.Satellite, loader: ClassLoader): Int {
        val holder = getReflectionHolder(loader)
        return SatelliteSky.packed(value, holder.svidShift, holder.constellationShift,
            holder.basebandCn0DbHzsField != null)
    }

    /**
     * 极速获取或创建 GpsSatellite 列表 (适配旧版 GpsStatus.getSatellites() / DevCheck)
     */
    fun getOrCreateSpoofedGpsSatellites(classLoader: ClassLoader, targetCount: Int, enableJitter: Boolean = true): Iterable<Any> {
        val now = System.currentTimeMillis()
        val count = targetCount.coerceIn(4, MAX_SATELLITES)

        val cached = cachedGpsSatelliteList
        if (cached != null && cachedGpsSatCount == count && now / 1000L == lastGpsSatellitesTimeMs / 1000L && cachedGpsJitter == enableJitter) {
            return cached
        }

        synchronized(this) {
            if (cachedGpsSatelliteList != null && cachedGpsSatCount == count && now / 1000L == lastGpsSatellitesTimeMs / 1000L && cachedGpsJitter == enableJitter) {
                return cachedGpsSatelliteList!!
            }

            val holder = getReflectionHolder(classLoader)
            if (holder.gpsSatelliteConstructor == null) {
                return emptyList()
            }

            val sky = SatelliteSky.snapshot(count, now, enableJitter)
            val list = ArrayList<Any>(count)

            try {
                for (i in 0 until count) {
                    val value = sky[i]
                    val prn = value.svid + when (value.constellation) { 3 -> 64; 5 -> 200; 6 -> 300; else -> 0 }
                    val sat = holder.gpsSatelliteConstructor!!.newInstance(prn)
                    val usedInFix = value.used
                    val snr = value.cn0
                    val elevation = value.elevation
                    val azimuth = value.azimuth

                    holder.satValidField?.setBoolean(sat, true)
                    holder.satHasEphemerisField?.setBoolean(sat, true)
                    holder.satHasAlmanacField?.setBoolean(sat, true)
                    holder.satUsedInFixField?.setBoolean(sat, usedInFix)
                    holder.satSnrField?.set(sat, snr)
                    holder.satElevationField?.set(sat, elevation)
                    holder.satAzimuthField?.set(sat, azimuth)

                    list.add(sat)
                }

                cachedGpsSatelliteList = list
                lastGpsSatellitesTimeMs = now
                cachedGpsSatCount = count
                cachedGpsJitter = enableJitter
                return list
            } catch (e: Throwable) {
                XposedBridge.log("[GnssFastMockEngine] createSpoofedGpsSatellites failed: $e")
                return emptyList()
            }
        }
    }
}
