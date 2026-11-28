package com.vincenthzr.locationspoofer.data.repository

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vincenthzr.locationspoofer.data.db.SavedRouteDao
import com.vincenthzr.locationspoofer.data.db.SavedRouteEntity
import com.vincenthzr.locationspoofer.data.model.RootSetupTestResult
import com.vincenthzr.locationspoofer.data.model.RootSolution
import com.vincenthzr.locationspoofer.data.model.RoutePoint
import com.vincenthzr.locationspoofer.data.state.SpoofingState
import com.vincenthzr.locationspoofer.utils.ConfigManager
import com.vincenthzr.locationspoofer.utils.LSPosedManager
import com.vincenthzr.locationspoofer.utils.RootManager
import com.vincenthzr.locationspoofer.utils.SelectedPoiAddress
import com.vincenthzr.locationspoofer.utils.AltitudeModel
import com.vincenthzr.locationspoofer.utils.SettingsManager
import org.json.JSONArray
import org.json.JSONObject

class LocationRepository(
    private val configManager: ConfigManager,
    private val rootManager: RootManager,
    private val lsposedManager: LSPosedManager,
    private val settingsManager: SettingsManager,
    private val savedRouteDao: SavedRouteDao,
    private val spoofingServiceController: SpoofingServiceController
) {
    private val lifecycleHandler = Handler(Looper.getMainLooper())

    /** 用户在设置里选择的 root 方案；解析失败（脏数据/尚未设置）时退回 AUTO */
    private fun currentRootSolution(): RootSolution =
        try {
            RootSolution.valueOf(settingsManager.rootSolution)
        } catch (e: Exception) {
            RootSolution.AUTO
        }

    suspend fun checkRootAccess(): Boolean = rootManager.checkRootAccess(currentRootSolution())

    /** 检测 Root 权限与框架配置通道是否正常，返回完整诊断结果供设置页"测试"按钮展示 */
    suspend fun testRootSetup(): RootSetupTestResult = rootManager.testRootSetup(currentRootSolution())

    /** 强制停止目标 App，让它们下次启动时加载当前模块并订阅框架配置 */
    suspend fun forceStopApps(packages: List<String>) = rootManager.forceStopApps(packages)

    /**
     * 设备重启 / App 更新后的统一自愈入口：恢复 Root 后台运行权限，
     * 并在模拟定位此前处于开启状态时重新调用 startSpoofing() 发布完整配置。
     * 供 BootCompletedReceiver 和 MainViewModel.initialize() 共同复用，
     * 避免这段逻辑两处各写一份。
     */
    suspend fun recoverAfterBoot(context: Context): Boolean {
        val hasRoot = rootManager.checkRootAccess(currentRootSolution())

        if (settingsManager.isSpoofingActive) {
            val lastLat = settingsManager.lastSpoofedLat.toDoubleOrNull() ?: 0.0
            val lastLng = settingsManager.lastSpoofedLng.toDoubleOrNull() ?: 0.0
            if (lastLat != 0.0 && lastLng != 0.0) {
                val stored = configManager.lastConfigSnapshot()
                val routePoints = mutableListOf<RoutePoint>()
                stored?.optJSONArray("route_points")?.let { points ->
                    for (index in 0 until points.length()) {
                        val point = points.optJSONObject(index) ?: continue
                        routePoints += RoutePoint(
                            point.optDouble("lat", lastLat),
                            point.optDouble("lng", lastLng)
                        )
                    }
                }
                startSpoofing(
                    context, lastLat, lastLng,
                    stored?.optString("sim_mode", "STILL") ?: "STILL",
                    stored?.optDouble("sim_bearing", 0.0)?.toFloat() ?: 0f,
                    stored?.optLong("start_timestamp", System.currentTimeMillis())
                        ?: System.currentTimeMillis(),
                    routePoints,
                    stored?.optBoolean("is_route_mode", false) ?: false,
                    settingsManager.getAppCoordinateSystems(),
                    wifiJson = stored?.optJSONObject("wifi_json")?.toString() ?: "{}",
                    cellJson = stored?.optJSONArray("cell_json")?.toString() ?: "[]",
                    bluetoothJson = stored?.optJSONArray("bluetooth_json")?.toString() ?: "[]",
                    mockWifi = stored?.optBoolean("mock_wifi", settingsManager.mockWifi)
                        ?: settingsManager.mockWifi,
                    mockCell = stored?.optBoolean("mock_cell", settingsManager.mockCell)
                        ?: settingsManager.mockCell,
                    mockBluetooth = stored?.optBoolean("mock_bluetooth", settingsManager.mockBluetooth)
                        ?: settingsManager.mockBluetooth,
                    enableJitter = stored?.optBoolean("enable_jitter", settingsManager.enableJitter)
                        ?: settingsManager.enableJitter,
                    speedMs = stored?.optDouble("speed_m_s", 0.0) ?: 0.0,
                    stopAtDestination = stored?.optBoolean("stop_at_destination", false) ?: false,
                    enableStepSimulation = stored?.optBoolean("enable_step_simulation", true) ?: true,
                    stepCadenceSpm = stored?.optInt("step_cadence_spm", 165) ?: 165,
                    isAutoCadence = stored?.optBoolean("is_auto_cadence", true) ?: true
                )
            }
        } else if (spoofingServiceController.isRunning) {
            stopSpoofing(context)
        }

        return hasRoot
    }

    fun isModuleActive(): Boolean = lsposedManager.isModuleActive()

    suspend fun startSpoofing(
        context: Context,
        lat: Double,
        lng: Double,
        simMode: String,
        simBearing: Float,
        startTime: Long,
        routePoints: List<RoutePoint>,
        isRouteMode: Boolean,
        appCoordinateSystems: Map<String, String>,
        wifiJson: String = "[]",
        cellJson: String = "[]",
        bluetoothJson: String = "[]",
        mockWifi: Boolean = true,
        mockCell: Boolean = true,
        mockBluetooth: Boolean = true,
        enableJitter: Boolean = true,
        speedMs: Double = 0.0,
        stopAtDestination: Boolean = false,
        enableStepSimulation: Boolean = true,
        stepCadenceSpm: Int = 165,
        isAutoCadence: Boolean = true,
        selectedPoi: SelectedPoiAddress? = null,
        onDesiredCommitted: (() -> Unit)? = null,
        onAcceptedStart: (() -> Unit)? = null
    ) {
        val startingMotion = ConfigManager.StartingMotion(settingsManager.realismLevel, settingsManager.speedFluctuationPct)

        // 与 stopSpoofing() 里的 revokeMockLocation() 对称：上一次停止模拟时会把
        // android:mock_location 重置为 default（用于避免被系统安全中心一直记录为"模拟中"），
        // 如果这里不重新授予，第二次开始模拟时该 AppOps 就会一直停留在 default，
        // 导致"模拟一次后关闭再开就再也无法模拟"。
        rootManager.grantMockLocation()

        val alt = AltitudeModel.parseBaseMeters(settingsManager.altitude) ?: 0.0
        val satCount = settingsManager.satelliteCount.toIntOrNull() ?: 10
        configManager.saveConfig(
            lat,
            lng,
            true,
            simMode,
            simBearing,
            startTime,
            routePoints,
            isRouteMode,
            wifiJson,
            appCoordinateSystems,
            cellJson,
            bluetoothJson,
            mockWifi,
            mockCell,
            mockBluetooth,
            enableJitter,
            alt,
            satCount,
            speedMs,
            stopAtDestination,
            enableStepSimulation,
            stepCadenceSpm,
            isAutoCadence,
            selectedPoi,
            startingMotion = startingMotion,
            onDesiredCommitted = {
                SpoofingState.isActive = true
                SpoofingState.realismLevel = startingMotion.realismLevel
                SpoofingState.speedFluctuationPct = startingMotion.speedFluctuationPct
                SpoofingState.routeDistanceOffset = 0.0
                updatePositionState(lat, lng, simMode, simBearing, startTime, routePoints, isRouteMode,
                    wifiJson, cellJson, bluetoothJson, enableJitter)
                onDesiredCommitted?.invoke()
                enqueueServiceLifecycle("start", onAcceptedStart) {
                    spoofingServiceController.startForeground(context, lat, lng)
                }
            }
        )
    }

    suspend fun stopSpoofing(context: Context, onAcceptedStop: (() -> Unit)? = null) {
        var desiredCommitted = false
        configManager.saveConfig(0.0, 0.0, false, onDesiredCommitted = {
            desiredCommitted = true
            settingsManager.isSpoofingActive = false
            SpoofingState.isActive = false
            SpoofingState.latitude = 0.0
            SpoofingState.longitude = 0.0
            SpoofingState.wifiJson = "[]"
            SpoofingState.cellJson = "[]"
            SpoofingState.bluetoothJson = "[]"
            SpoofingState.routeJson = "[]"
            SpoofingState.isRouteMode = false
            enqueueServiceLifecycle("stop", onAcceptedStop) {
                spoofingServiceController.stop(context)
            }
        })

        // 1. 同步在当前进程清理所有可能残留的 TestProvider
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm != null) {
            try {
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
            } catch (e: Throwable) {
            }
            try {
                lm.removeTestProvider(LocationManager.GPS_PROVIDER)
            } catch (e: Throwable) {
            }
            try {
                lm.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
            } catch (e: Throwable) {
            }
            try {
                lm.removeTestProvider(LocationManager.NETWORK_PROVIDER)
            } catch (e: Throwable) {
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    lm.setTestProviderEnabled(LocationManager.FUSED_PROVIDER, false)
                    lm.removeTestProvider(LocationManager.FUSED_PROVIDER)
                }
            } catch (e: Throwable) {
            }
        }

        // 2. 停止前台模拟服务
        if (!desiredCommitted) spoofingServiceController.stop(context)

        // 3. 彻底重置系统的 mock_location 状态，防止被澎湃OS/系统安全中心记录为模拟中
        rootManager.revokeMockLocation()
    }

    val isFloatingJoystickShowing: Boolean get() = spoofingServiceController.isFloatingJoystickShowing

    fun setFloatingJoystickVisible(context: Context, visible: Boolean) =
        spoofingServiceController.setFloatingJoystickVisible(context, visible)

    /** 只改配置里的部分字段，见 [ConfigManager.patchConfig] */
    suspend fun patchConfig(mutate: (JSONObject) -> Unit): Boolean = configManager.patchConfig(mutate)

    /** 系统进程读取的配置写入失败事件，界面据此提示用户 */
    val configWriteFailures get() = configManager.writeFailures

    /** 路线 / 摇杆移动过程中刷新周边 Wi-Fi、基站、蓝牙数据，不改动位置和运动状态 */
    suspend fun updateEnvironment(wifiJson: String, cellJson: String, bluetoothJson: String) {
        SpoofingState.wifiJson = wifiJson
        SpoofingState.cellJson = cellJson
        SpoofingState.bluetoothJson = bluetoothJson
        configManager.patchConfig { configManager.putEnvironment(it, wifiJson, cellJson, bluetoothJson) }
    }

    // Enqueue in desired commit order and invoke the service outside the configuration lock.
    private fun enqueueServiceLifecycle(action: String, onApplied: (() -> Unit)?, request: () -> Unit) {
        lifecycleHandler.post {
            try {
                request()
            } catch (error: RuntimeException) {
                Log.w("LocationSpoofer", "Foreground service $action request failed (${error.javaClass.simpleName})")
            }
            onApplied?.invoke()
        }
    }

    suspend fun updateConfig(
        lat: Double,
        lng: Double,
        simMode: String,
        simBearing: Float,
        startTime: Long,
        routePoints: List<RoutePoint>,
        isRouteMode: Boolean,
        appCoordinateSystems: Map<String, String>,
        wifiJson: String = SpoofingState.wifiJson,
        cellJson: String = SpoofingState.cellJson,
        bluetoothJson: String = SpoofingState.bluetoothJson,
        mockWifi: Boolean = true,
        mockCell: Boolean = true,
        mockBluetooth: Boolean = true,
        enableJitter: Boolean = true,
        speedMs: Double = 0.0,
        stopAtDestination: Boolean = false,
        enableStepSimulation: Boolean = true,
        stepCadenceSpm: Int = 165,
        isAutoCadence: Boolean = true,
        selectedPoi: SelectedPoiAddress? = null,
        reuseNearbyAddress: Boolean = simMode != "STILL",
        onDesiredCommitted: (() -> Unit)? = null,
        onAcceptedPosition: (() -> Unit)? = null
    ) {
        val alt = AltitudeModel.parseBaseMeters(settingsManager.altitude) ?: 0.0
        val satCount = settingsManager.satelliteCount.toIntOrNull() ?: 10
        configManager.saveConfig(
            lat,
            lng,
            true,
            simMode,
            simBearing,
            startTime,
            routePoints,
            isRouteMode,
            wifiJson,
            appCoordinateSystems,
            cellJson,
            bluetoothJson,
            mockWifi,
            mockCell,
            mockBluetooth,
            enableJitter,
            alt,
            satCount,
            speedMs,
            stopAtDestination,
            enableStepSimulation,
            stepCadenceSpm,
            isAutoCadence,
            selectedPoi,
            reuseNearbyAddress,
            onDesiredCommitted = {
                updatePositionState(lat, lng, simMode, simBearing, startTime, routePoints, isRouteMode,
                    wifiJson, cellJson, bluetoothJson, enableJitter)
                onDesiredCommitted?.invoke()
                if (onAcceptedPosition != null) lifecycleHandler.post { onAcceptedPosition() }
            }
        )
    }

    private fun updatePositionState(
        lat: Double,
        lng: Double,
        simMode: String,
        simBearing: Float,
        startTime: Long,
        routePoints: List<RoutePoint>,
        isRouteMode: Boolean,
        wifiJson: String,
        cellJson: String,
        bluetoothJson: String,
        enableJitter: Boolean
    ) {
        SpoofingState.latitude = lat
        SpoofingState.longitude = lng
        SpoofingState.startTimestamp = startTime
        SpoofingState.simMode = simMode
        SpoofingState.simBearing = simBearing
        SpoofingState.routeJson = routePointsToJson(routePoints)
        SpoofingState.isRouteMode = isRouteMode
        SpoofingState.wifiJson = wifiJson
        SpoofingState.cellJson = cellJson
        SpoofingState.bluetoothJson = bluetoothJson
        SpoofingState.enableJitter = enableJitter
    }

    suspend fun updateWifiJson(wifiJson: String, appCoordinateSystems: Map<String, String>) {
        SpoofingState.wifiJson = wifiJson
        // 同步写入配置文件,确保Xposed端能读取到WiFi数据
        configManager.saveConfig(
            SpoofingState.latitude,
            SpoofingState.longitude,
            SpoofingState.isActive,
            SpoofingState.simMode,
            SpoofingState.simBearing,
            startTimestamp = SpoofingState.startTimestamp,
            wifiJson = wifiJson,
            appCoordinateSystems = appCoordinateSystems,
            cellJson = SpoofingState.cellJson,
            bluetoothJson = SpoofingState.bluetoothJson,
            altitude = AltitudeModel.parseBaseMeters(settingsManager.altitude) ?: 0.0,
            satelliteCount = settingsManager.satelliteCount.toIntOrNull() ?: 10
        )
    }

    private fun routePointsToJson(points: List<RoutePoint>): String {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONObject().apply {
                put("lat", p.lat)
                put("lng", p.lng)
            })
        }
        return arr.toString()
    }

    fun getSavedRoutes(): kotlinx.coroutines.flow.Flow<List<SavedRouteEntity>> {
        return savedRouteDao.getAllSavedRoutes()
    }

    suspend fun getAllSavedRoutesList(): List<SavedRouteEntity> {
        return savedRouteDao.getAllSavedRoutesList()
    }

    suspend fun insertSavedRoute(name: String, points: List<RoutePoint>) {
        val pointsJson = routePointsToJson(points)
        savedRouteDao.insertSavedRoute(
            SavedRouteEntity(
                name = name,
                pointsJson = pointsJson
            )
        )
    }

    suspend fun insertSavedRouteEntity(route: SavedRouteEntity) {
        savedRouteDao.insertSavedRoute(route)
    }

    suspend fun deleteSavedRoute(route: SavedRouteEntity) {
        savedRouteDao.deleteSavedRoute(route)
    }
}
