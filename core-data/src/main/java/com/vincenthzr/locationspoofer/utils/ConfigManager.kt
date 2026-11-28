package com.vincenthzr.locationspoofer.utils

import android.content.Context
import android.location.Geocoder
import com.vincenthzr.locationspoofer.data.model.RoutePoint
import com.vincenthzr.locationspoofer.data.state.SpoofingState
import com.vincenthzr.locationspoofer.utils.CoordinateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ConfigManager(private val context: Context, private val rootManager: RootManager) {

    companion object {
        const val TRANSPORT_PREFS = "framework_config_state"
        const val DESIRED_CONFIG = "desired_config"
        const val FIXTURE_OWNER = "instrumentation_fixture_owner"
        const val FIXTURE_USER_CONFIG = "instrumentation_fixture_user_config"
    }

    // system_hook_packages 是独立于每次模拟会话的常驻勾选项（在"系统级模拟应用"页面里配置），
    // 不随 lat/lng/active 这类瞬时状态一起在调用方逐层透传，这里每次发布时直接读取最新值即可。
    private val settingsManager = SettingsManager(context)

    private val positionAddress = PositionAddressResolver(::reverseGeocode) { first, second ->
        val distance = FloatArray(1)
        android.location.Location.distanceBetween(first.lat, first.lng, second.lat, second.lng, distance)
        distance[0] <= 500f
    }

    data class StartingMotion(val realismLevel: Int, val speedFluctuationPct: Int)

    suspend fun saveConfig(
        lat: Double,
        lng: Double,
        active: Boolean,
        simMode: String = "STILL",
        simBearing: Float = 0f,
        startTimestamp: Long = System.currentTimeMillis(),
        routePoints: List<RoutePoint> = emptyList(),
        isRouteMode: Boolean = false,
        wifiJson: String = "[]",
        appCoordinateSystems: Map<String, String> = emptyMap(),
        cellJson: String = "[]",
        bluetoothJson: String = "[]",
        mockWifi: Boolean = true,
        mockCell: Boolean = true,
        mockBluetooth: Boolean = true,
        enableJitter: Boolean = true,
        altitude: Double = 0.0,
        satelliteCount: Int = 10,
        speedMs: Double = 0.0,
        stopAtDestination: Boolean = false,
        enableStepSimulation: Boolean = true,
        stepCadenceSpm: Int = 165,
        isAutoCadence: Boolean = true,
        selectedPoi: SelectedPoiAddress? = null,
        reuseNearbyAddress: Boolean = active && simMode != "STILL",
        startingMotion: StartingMotion? = null,
        pointEllipsoidMeters: Double? = null,
        onDesiredCommitted: (() -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val addressContext = currentCoroutineContext()
        val routeArray = JSONArray()
        routePoints.forEach { p ->
            val obj = JSONObject()
            obj.put("lat", p.lat)
            obj.put("lng", p.lng)
            routeArray.put(obj)
        }


        val json = JSONObject().apply {
            putPosition(this, lat, lng, reuseNearbyAddress, selectedPoi) { addressContext.ensureActive() }
            put("active", active)
            put("sim_mode", simMode)
            put("sim_bearing", simBearing.toDouble())
            put("speed_m_s", speedMs)
            put("start_timestamp", startTimestamp)
            put("route_points", routeArray)
            put("is_route_mode", isRouteMode)
            put("stop_at_destination", stopAtDestination)
            putEnvironment(this, wifiJson, cellJson, bluetoothJson)
            put("mock_wifi", mockWifi)
            put("mock_cell", mockCell)
            put("mock_bluetooth", mockBluetooth)
            put("enable_jitter", enableJitter)
            PointHeightBinding.assign(this, altitude, pointEllipsoidMeters)
            put("satellite_count", satelliteCount)
            put("enable_step_simulation", enableStepSimulation)
            put("step_cadence_spm", stepCadenceSpm)
            put("is_auto_cadence", isAutoCadence)

            val coordSysObj = JSONObject()
            appCoordinateSystems.forEach { (pkg, sys) -> coordSysObj.put(pkg, sys) }
            put("app_coordinate_systems", coordSysObj)

            put("route_distance_offset", if (startingMotion != null) 0.0 else SpoofingState.routeDistanceOffset)
            putPersistentSettings(this, startingMotion)
        }
        writeMutex.writeCurrentPosition { writeLocked(json, onDesiredCommitted) }
    }

    private val _writeFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 框架配置发布失败时发出，界面据此提示用户（core-data 没有字符串资源，不在这里直接弹提示） */
    val writeFailures: SharedFlow<Unit> = _writeFailures

    /**
     * 在上一次写入的配置基础上只改动部分字段，其余字段原样保留；常驻设置项每次都刷新为最新值。
     * 暂停、摇杆、周边环境数据、开关设置等局部更新都走这里，避免某个调用方用自己手里过时的整份状态
     * 覆盖掉别人刚写入的字段（例如悬浮窗刚把路线暂停，界面的周边数据刷新又把路线模式写回去）。
     * 还没有配置（未开始模拟）或发布失败时返回 false；未发布的最新状态会在框架重连后重试。
     */
    suspend fun patchConfig(mutate: (JSONObject) -> Unit): Boolean = withContext(Dispatchers.IO) {
        // 读取、修改、写入整体放在锁内：并发的局部更新若各自基于同一份旧配置修改，后写的会冲掉先写的改动
        writeMutex.withLock {
            val base = lastJson ?: return@withLock false
            val json = JSONObject(base.toString())
            mutate(json)
            PointHeightBinding.reconcilePatch(json) { AltitudeModel.parseBaseMeters(settingsManager.altitude) ?: 0.0 }
            putPersistentSettings(json)
            writeLocked(json)
        }
    }

    /** 返回最近一次发布的完整配置，供进程或设备重启后的恢复流程复用环境数据。 */
    fun lastConfigSnapshot(): JSONObject? = lastJson?.let { JSONObject(it.toString()) }

    // The committed object is never mutated; patches always copy it before publication.
    // Avoid encoding/decoding the entire radio environment on every map drag.
    internal fun activePointUsesAutomaticHeight(): Boolean = PointHeightBinding.isBound(lastJson)

    /** Writes coordinates and location-bound address fields from an IO dispatcher. */
    fun putPosition(
        json: JSONObject,
        lat: Double,
        lng: Double,
        reuseNearbyAddress: Boolean = true,
        selectedPoi: SelectedPoiAddress? = null,
        ensureCurrent: () -> Unit = {}
    ) {
        val point = CoordinateUtils.LatLng(lat, lng)
        val address = positionAddress.resolve(point, reuseNearbyAddress, ensureCurrent).withSelectedPoi(point, selectedPoi)
        json.put("province", address.province)
        json.put("city", address.city)
        json.put("district", address.district)
        json.put("street", address.street)
        json.put("streetNum", address.streetNum)
        json.put("address", address.address)
        json.put("country", address.country)
        json.put("poiName", address.poiName)

        val wgs = CoordinateUtils.gcj02ToWgs84(lat, lng)
        val bd = CoordinateUtils.gcj02ToBd09(lat, lng)
        json.put("wgs84_lat", wgs.lat)
        json.put("wgs84_lng", wgs.lng)
        json.put("bd09_lat", bd.lat)
        json.put("bd09_lng", bd.lng)
        json.put("lat", lat)
        json.put("lng", lng)
    }

    fun putEnvironment(json: JSONObject, wifiJson: String, cellJson: String, bluetoothJson: String) {
        putWifi(json, wifiJson)
        json.put("cell_json", JSONArray(cellJson))
        json.put("bluetooth_json", JSONArray(bluetoothJson))
    }

    internal fun putWifi(json: JSONObject, wifiJson: String) {
        val wifiObj = try {
            JSONObject(wifiJson)
        } catch (e: Exception) {
            JSONObject().apply {
                put("isConnected", false)
                put("connectedWifi", JSONObject.NULL)
                put("nearbyWifi", JSONArray())
            }
        }
        json.put("wifi_json", wifiObj)
    }

    /** 独立于每次模拟会话的常驻设置项，调用方不逐层透传，每次发布时直接读取最新值 */
    private fun putPersistentSettings(json: JSONObject, startingMotion: StartingMotion? = null) {
        val systemHookPackagesArr = JSONArray()
        settingsManager.getSystemHookPackages().forEach { systemHookPackagesArr.put(it) }
        json.put("system_hook_packages", systemHookPackagesArr)
        json.put("system_hook_global_mode", settingsManager.isSystemHookGlobalMode)
        json.put("vendor_override", settingsManager.vendorOverride)
        json.put("force_location_enabled", settingsManager.forceLocationEnabled)
        json.put("debug_dump_system_services", settingsManager.debugDumpSystemServices)
        // 运动真实度：Xposed 端据此给速度、步频、海拔、加速度加起伏；随机强度与速度浮动在会话内取开始时的快照
        val realismLevel = startingMotion?.realismLevel
            ?: SpoofingState.realismLevel.takeIf { it >= 0 } ?: settingsManager.realismLevel
        val speedFluctuation = startingMotion?.speedFluctuationPct
            ?: SpoofingState.speedFluctuationPct.takeIf { it >= 0 } ?: settingsManager.speedFluctuationPct
        json.put("realism_level", realismLevel)
        json.put("speed_fluctuation_pct", speedFluctuation)
        json.put("gait_template", if (settingsManager.useGaitTemplate) settingsManager.gaitTemplate else "")
        json.put("altitude_variation_m", PointHeightBinding.variationMeters(json, realismLevel, settingsManager.altitudeVariationM))
    }

    private fun reverseGeocode(point: CoordinateUtils.LatLng): PositionAddress? {
        val geocoder = Geocoder(context, java.util.Locale.CHINA)
        val wgs = CoordinateUtils.gcj02ToWgs84(point.lat, point.lng)
        @Suppress("DEPRECATION")
        val address = geocoder.getFromLocation(wgs.lat, wgs.lng, 1)?.firstOrNull() ?: return null
        return PositionAddress(
            province = address.adminArea.orEmpty(),
            city = address.locality ?: address.subAdminArea.orEmpty(),
            district = address.subLocality.orEmpty(),
            street = address.thoroughfare.orEmpty(),
            streetNum = address.subThoroughfare.orEmpty(),
            address = address.getAddressLine(0).orEmpty(),
            country = address.countryName.orEmpty(),
            poiName = address.featureName.orEmpty()
        )
    }

    private val writeMutex = Mutex()

    private val transportPrefs = context.getSharedPreferences(TRANSPORT_PREFS, Context.MODE_PRIVATE)
    private val publisher = FrameworkConfigPublisher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastJson: JSONObject? = readUserConfig()
    @Volatile private var publicationPending = lastJson != null
    private var publicationRetry: Job? = null
    private var nextCleanupAttemptAt = 0L

    private fun hasFixture() = transportPrefs.contains(FIXTURE_OWNER)

    private fun readUserConfig(): JSONObject? {
        val key = if (hasFixture()) FIXTURE_USER_CONFIG else DESIRED_CONFIG
        return transportPrefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    /** Serializes short instrumentation writes with UI publication and reconnection. */
    suspend fun <T> withFixtureConfigAccess(action: () -> T): T = writeMutex.withLock {
        val wasFixture = hasFixture()
        try {
            action()
        } finally {
            lastJson = readUserConfig()
            if (wasFixture && !hasFixture() && publicationPending && lastJson != null) {
                XposedModuleStatus.mService?.let { service ->
                    val currentPayload = runCatching {
                        service.getRemotePreferences(FrameworkConfigChannel.GROUP)
                            .getString(FrameworkConfigChannel.SNAPSHOT_KEY, null)
                            ?.let { JSONObject(it).optString("payload") }
                    }.getOrNull()
                    if (currentPayload != lastJson.toString()) schedulePublicationRetryLocked()
                }
            }
        }
    }

    init {
        // StateFlow delivers an already-connected service too, so initialization order is harmless.
        scope.launch {
            XposedModuleStatus.service.collect { service ->
                writeMutex.withLock {
                    if (service != null) {
                        publisher.onReconnect()
                        if (!hasFixture()) lastJson?.let { publishLocked(it, service) }
                    } else cancelPublicationRetryLocked()
                }
            }
        }
    }

    /** A failed publication owns a retry; successful/idle/disconnected states own none. */
    private fun schedulePublicationRetryLocked() {
        if (publicationRetry?.isActive == true) return
        publicationRetry = scope.launch {
            delay(3_000)
            writeMutex.withLock {
                publicationRetry = null
                val service = XposedModuleStatus.mService
                if (publicationPending && service != null && !hasFixture()) lastJson?.let {
                    publishLocked(it, service, notifyFailure = false)
                }
            }
        }
    }

    private fun cancelPublicationRetryLocked() {
        publicationRetry?.cancel()
        publicationRetry = null
    }

    private fun writeLocked(json: JSONObject, onDesiredCommitted: (() -> Unit)? = null): Boolean {
        // Remember the desired state even if a stop/pause happens while the service is disconnected.
        // Reconnection must never replay an older active session over a newer stop request.
        if (!transportPrefs.edit().putString(DESIRED_CONFIG, json.toString())
                .remove(FIXTURE_OWNER).remove(FIXTURE_USER_CONFIG).commit()) {
            _writeFailures.tryEmit(Unit)
            return false
        }
        lastJson = json
        publicationPending = true
        onDesiredCommitted?.invoke()
        val service = XposedModuleStatus.mService
        if (service == null) {
            cancelPublicationRetryLocked()
            android.util.Log.w("LocationSpoofer", "Framework disconnected; latest configuration queued")
            _writeFailures.tryEmit(Unit)
            return false
        }
        return publishLocked(json, service)
    }

    private fun publishLocked(
        json: JSONObject,
        service: io.github.libxposed.service.XposedService,
        notifyFailure: Boolean = true
    ): Boolean {
        return try {
            val prefs = service.getRemotePreferences(FrameworkConfigChannel.GROUP)
            publisher.publish(json.toString(), object : FrameworkConfigStore {
                override fun commit(snapshot: String): Boolean =
                    prefs.edit().putString(FrameworkConfigChannel.SNAPSHOT_KEY, snapshot).commit()

                override fun writeFile(name: String, payload: ByteArray) {
                    ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(name)).use { it.write(payload) }
                }

                override fun listFiles(): List<String> = service.listRemoteFiles().toList()
                override fun deleteFile(name: String) { service.deleteRemoteFile(name) }
            })
            publicationPending = false
            cancelPublicationRetryLocked()
            // Migration runs only after the replacement channel accepted a complete snapshot.
            val now = System.currentTimeMillis()
            if (!transportPrefs.getBoolean("legacy_files_removed", false) && now >= nextCleanupAttemptAt) {
                nextCleanupAttemptAt = now + 60_000
                // Root authorization/cleanup must not delay subsequent stop or coordinate updates.
                scope.launch {
                    if (rootManager.executeCommand(SystemFileCommands.removeLegacyConfigs()) != "ERROR") {
                        transportPrefs.edit().putBoolean("legacy_files_removed", true).apply()
                    }
                }
            }
            true
        } catch (error: Exception) {
            android.util.Log.e("LocationSpoofer", "Framework config publication failed", error)
            publicationPending = true
            schedulePublicationRetryLocked()
            if (notifyFailure) _writeFailures.tryEmit(Unit)
            false
        }
    }
}
