package com.vincenthzr.locationspoofer.viewmodel

import android.content.Context
import java.net.UnknownHostException
import java.util.Locale
import androidx.lifecycle.viewModelScope
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.vincenthzr.locationspoofer.data.model.LocationLookupFeedback
import com.vincenthzr.locationspoofer.data.model.LocationLookupSource.CELL
import com.vincenthzr.locationspoofer.data.model.LocationLookupSource.OPEN_CELL
import com.vincenthzr.locationspoofer.data.model.LocationLookupSource.WIFI
import com.vincenthzr.locationspoofer.data.model.LocationLookupSource.WIFI_METADATA
import com.vincenthzr.locationspoofer.data.model.LocationLookupSource.WIGLE_WIFI
import com.vincenthzr.locationspoofer.utils.RemoteCellNoCoverageException
import com.vincenthzr.locationspoofer.utils.RemoteCellNoSeedException
import com.vincenthzr.locationspoofer.utils.RemoteWifiNoCoverageException
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.screen.AppPoiItem
import com.vincenthzr.locationspoofer.utils.CoordinateUtils
import com.vincenthzr.locationspoofer.utils.SelectedPoiAddress
import com.vincenthzr.locationspoofer.utils.WigleWifiLookupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

// MainViewModel 的当前位置获取、原生定位回退与模拟能力评估相关扩展函数

// 当前位置仅由页面显式请求或空输入回填。等待定位期间已经选了别的点，就丢弃迟到结果。
internal fun MainViewModel.fetchCurrentLocation(ctx: Context, forceCallback: ((Double, Double) -> Unit)? = null) {
    val start = _uiState.value
    if (forceCallback == null && start.latitudeInput.isNotEmpty() && start.longitudeInput.isNotEmpty()) return
    fun notifyUser(message: Int) {
        if (forceCallback != null) android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_SHORT).show()
    }
    viewModelScope.launch(Dispatchers.Main) {
        val amap = fetchAmapPoint(ctx)
        val result = amap ?: try {
            fetchNativePoint(ctx) { notifyUser(R.string.waiting_gps_signal) }
        } catch (_: SecurityException) {
            notifyUser(R.string.location_permission_denied)
            null
        }
        val (lat, lng) = result ?: return@launch
        while (true) {
            val state = _uiState.value
            if (state.latitudeInput != start.latitudeInput || state.longitudeInput != start.longitudeInput ||
                state.isSpoofingActive != start.isSpoofingActive) return@launch
            val next = state.copy(latitudeInput = coordinateInputText(lat), longitudeInput = coordinateInputText(lng),
                showCoordinateError = false, locationLookupFeedback = state.locationLookupFeedback?.takeIf { it.matchesPoint(lat, lng) })
            if (_uiState.compareAndSet(state, next)) break
        }
        if (amap == null) notifyUser(R.string.native_location_success)
        forceCallback?.invoke(lat, lng)
    }
}

internal suspend fun MainViewModel.fetchRealLocationSilent(ctx: Context): Pair<Double, Double>? =
    fetchAmapPoint(ctx) ?: try { fetchNativePoint(ctx) } catch (_: SecurityException) { null }

private suspend fun fetchAmapPoint(ctx: Context): Pair<Double, Double>? = withTimeoutOrNull(15_000) {
    val client = try { AMapLocationClient(ctx.applicationContext) } catch (_: Exception) { return@withTimeoutOrNull null }
    try {
        suspendCancellableCoroutine<Pair<Double, Double>?> { cont ->
            client.setLocationOption(AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
                isNeedAddress = false
            })
            client.setLocationListener { loc ->
                if (cont.isActive) cont.resume(if (loc != null && loc.errorCode == 0 &&
                    validCurrentCoordinates(loc.latitude, loc.longitude)) loc.latitude to loc.longitude else null)
            }
            if (cont.isActive) client.startLocation()
        }
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    } finally {
        runCatching { client.stopLocation() }
        runCatching { client.onDestroy() }
    }
}

private fun android.location.Location.asNativeFix() = NativeCurrentFix(latitude, longitude, elapsedRealtimeNanos)

private suspend fun fetchNativePoint(ctx: Context, waiting: () -> Unit = {}): Pair<Double, Double>? =
    try {
        readNativePoint(ctx, waiting)
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (denied: SecurityException) {
        throw denied // Interactive callers show the permission error; silent callers return no fix.
    } catch (_: RuntimeException) {
        null // Providers or the SDK converter may disappear/fail during a one-shot request.
    }

@android.annotation.SuppressLint("MissingPermission")
private suspend fun readNativePoint(ctx: Context, waiting: () -> Unit): Pair<Double, Double>? {
    val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
    val providers = listOf(android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.GPS_PROVIDER)
        .filter { manager.isProviderEnabled(it) }
    val provider = providers.firstOrNull() ?: return null
    val candidates = providers.mapNotNull {
        try { manager.getLastKnownLocation(it)?.asNativeFix() }
        catch (_: IllegalArgumentException) { null } // A provider may disappear between calls.
        catch (_: SecurityException) { null } // An otherwise accessible provider can still supply the fix.
    }
    val cached = newestFreshNativeFix(candidates, android.os.SystemClock.elapsedRealtimeNanos())
    val fix = cached ?: run {
        waiting()
        var listener: android.location.LocationListener? = null
        try {
            withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine<NativeCurrentFix?> { cont ->
                    val callback = object : android.location.LocationListener {
                        override fun onLocationChanged(location: android.location.Location) {
                            if (cont.isActive) cont.resume(location.asNativeFix().takeIf {
                                it.isFreshAt(android.os.SystemClock.elapsedRealtimeNanos())
                            })
                        }
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    listener = callback
                    if (cont.isActive) manager.requestSingleUpdate(provider, callback, android.os.Looper.getMainLooper())
                }
            }
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            listener?.let { runCatching { manager.removeUpdates(it) } }
        }
    } ?: return null
    val converted = com.amap.api.maps.CoordinateConverter(ctx).apply {
        from(com.amap.api.maps.CoordinateConverter.CoordType.GPS)
        coord(com.amap.api.maps.model.LatLng(fix.lat, fix.lng))
    }.convert() ?: return null
    return if (validCurrentCoordinates(converted.latitude, converted.longitude))
        converted.latitude to converted.longitude else null
}

// 坐标输入
internal fun MainViewModel.selectSearchPoint(poi: AppPoiItem) {
    val address = if (poi.isOnlineResult) {
        SelectedPoiAddress(CoordinateUtils.LatLng(poi.lat, poi.lng), poi.title, poi.snippet)
    } else null
    if (!configurationTargetMatches(poi.lat, poi.lng) || selectedPoiAddress != address) {
        mapMoveJob?.cancel()
        pointConfigurationJob?.cancel()
    }
    selectedPoiAddress = address
    _uiState.update {
        it.copy(
            latitudeInput = poi.lat.toString(),
            longitudeInput = poi.lng.toString(),
            showCoordinateError = false,
            locationLookupFeedback = null
        )
    }
    evaluateMockCapabilities()
}

internal fun MainViewModel.updateLongitude(value: String) {
    if (isValidCoord(value)) {
        if (_uiState.value.longitudeInput.toDoubleOrNull() != value.toDoubleOrNull()) {
            discardLocationLookupForNewPoint(_uiState.value.latitudeInput.toDoubleOrNull(), value.toDoubleOrNull())
            selectedPoiAddress = null
            mapMoveJob?.cancel()
            pointConfigurationJob?.cancel()
        }
        _uiState.update { it.copy(longitudeInput = value, showCoordinateError = false) }
        evaluateMockCapabilities()
    }
}

internal fun MainViewModel.updateLatitude(value: String) {
    if (isValidCoord(value)) {
        if (_uiState.value.latitudeInput.toDoubleOrNull() != value.toDoubleOrNull()) {
            discardLocationLookupForNewPoint(value.toDoubleOrNull(), _uiState.value.longitudeInput.toDoubleOrNull())
            selectedPoiAddress = null
            mapMoveJob?.cancel()
            pointConfigurationJob?.cancel()
        }
        _uiState.update { it.copy(latitudeInput = value, showCoordinateError = false) }
        evaluateMockCapabilities()
    }
}

private fun MainViewModel.isValidCoord(value: String): Boolean {
    if (value.isEmpty() || value == "-") return true
    return value.toDoubleOrNull() != null
}

internal fun MainViewModel.evaluateMockCapabilities() {
    mockCapabilitiesJob?.cancel()
    val state = _uiState.value
    val lat = state.latitudeInput.toDoubleOrNull()
    val lng = state.longitudeInput.toDoubleOrNull()

    if (lat == null || lng == null) {
        _uiState.update {
            it.copy(
                canMockWifi = false,
                canMockCell = false,
                canMockBluetooth = false,
                collectedWifiJson = "[]",
                collectedCellJson = "[]",
                collectedBluetoothJson = "[]",
                wifiApCount = 0,
                wifiLoadStatus = com.vincenthzr.locationspoofer.data.model.WifiLoadStatus.IDLE
            )
        }
        return
    }

    mockCapabilitiesJob = viewModelScope.launch {
        evaluateMockCapabilitiesSuspend(lat, lng)
    }
}

internal fun MainViewModel.selectCollectedLocation(locationId: Long) {
    viewModelScope.launch {
        val record = withContext(Dispatchers.IO) {
            environmentDao.getCompleteLocationById(locationId)
        }
        if (record != null) {
            pinnedLocationRecordId = locationId
            val name = when {
                record.location.remark.isNotBlank() -> record.location.remark
                record.location.placeName.isNotBlank() -> record.location.placeName
                else -> String.format(Locale.US, "(%.5f, %.5f)", record.location.lat, record.location.lng)
            }
            _uiState.update {
                it.copy(
                    latitudeInput = record.location.lat.toString(),
                    longitudeInput = record.location.lng.toString(),
                    locationLookupFeedback = it.locationLookupFeedback?.takeIf { feedback ->
                        feedback.matchesPoint(record.location.lat, record.location.lng)
                    },
                    selectedCollectionRouteId = null,
                    pinnedCollectedLocationId = locationId,
                    pinnedLocationName = name
                )
            }
            evaluateMockCapabilitiesSuspend(record.location.lat, record.location.lng)
        }
    }
}

internal fun MainViewModel.clearPinnedCollectedLocation() {
    pinnedLocationRecordId = null
    _uiState.update {
        it.copy(
            pinnedCollectedLocationId = null,
            pinnedLocationName = null
        )
    }
    evaluateMockCapabilities()
}

private fun MainViewModel.calculateDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) *
            kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLng / 2).let { it * it }
    return 2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}

internal fun MainViewModel.configurationTargetMatches(lat: Double, lng: Double): Boolean {
    val state = _uiState.value
    val a = state.latitudeInput.toDoubleOrNull() ?: return false
    val b = state.longitudeInput.toDoubleOrNull() ?: return false
    return kotlin.math.abs(a - lat) <= 0.000001 && kotlin.math.abs(b - lng) <= 0.000001
}

internal suspend fun MainViewModel.evaluateMockCapabilitiesSuspend(lat: Double, lng: Double) {
    val currentPinnedId = pinnedLocationRecordId
    var pinnedRecord: com.vincenthzr.locationspoofer.data.db.CompleteLocation? = null

    if (currentPinnedId != null) {
        val pinned = withContext(Dispatchers.IO) {
            environmentDao.getCompleteLocationById(currentPinnedId)
        }
        if (pinned != null) {
            val distToPinned = calculateDistanceMeters(lat, lng, pinned.location.lat, pinned.location.lng)
            if (distToPinned <= 50.0) {
                pinnedRecord = pinned
            } else {
                // 超出 50 米有效范围，自动解除锁定
                pinnedLocationRecordId = null
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            pinnedCollectedLocationId = null,
                            pinnedLocationName = null
                        )
                    }
                }
            }
        } else {
            pinnedLocationRecordId = null
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        pinnedCollectedLocationId = null,
                        pinnedLocationName = null
                    )
                }
            }
        }
    }

    val validRecords = environmentRecordsAt(lat, lng).toMutableList()

    // 若用户当前明确锁定了某个 50m 内的采集点，则将其置于首位最高优先级
    if (pinnedRecord != null) {
        validRecords.removeAll { it.location.id == pinnedRecord.location.id }
        validRecords.add(0, pinnedRecord)
    }

    withContext(Dispatchers.Main) {
        val current = _uiState.value
        if (!configurationTargetMatches(lat, lng)) {
            return@withContext
        }
        if (validRecords.isEmpty()) {
            _uiState.update {
                it.copy(
                    canMockWifi = false,
                    canMockCell = false,
                    canMockBluetooth = false,
                    collectedWifiJson = "[]",
                    collectedCellJson = "[]",
                    collectedBluetoothJson = "[]",
                    wifiApCount = 0,
                    wifiLoadStatus = com.vincenthzr.locationspoofer.data.model.WifiLoadStatus.IDLE
                )
            }
        } else {
            val (wifiJson, cellJson, btJson) = locationToJson(validRecords, lat, lng)
            val hasW = try {
                val obj = org.json.JSONObject(wifiJson)
                val nearby = obj.optJSONArray("nearbyWifi")
                val connected = obj.opt("connectedWifi")
                (nearby != null && nearby.length() > 0) || (connected != null && !obj.isNull("connectedWifi"))
            } catch (e: Exception) {
                false
            }
            val hasC = try {
                val arr = org.json.JSONArray(cellJson)
                arr.length() > 0
            } catch (e: Exception) {
                false
            }
            val hasB = try {
                val arr = org.json.JSONArray(btJson)
                arr.length() > 0
            } catch (e: Exception) {
                false
            }

            val wifiCount = parseWifiCount(wifiJson)

            _uiState.update {
                it.copy(
                    canMockWifi = hasW,
                    canMockCell = hasC,
                    canMockBluetooth = hasB,
                    collectedWifiJson = wifiJson,
                    collectedCellJson = cellJson,
                    collectedBluetoothJson = btJson,
                    wifiApCount = wifiCount,
                    wifiLoadStatus = if (hasW) com.vincenthzr.locationspoofer.data.model.WifiLoadStatus.DONE else com.vincenthzr.locationspoofer.data.model.WifiLoadStatus.IDLE
                )
            }
        }
    }
}

internal suspend fun MainViewModel.hasLocalWifiWithin50m(lat: Double, lng: Double): Boolean =
    environmentRecordsAt(lat, lng).any { it.wifis.isNotEmpty() || it.connectedWifi != null }

internal suspend fun MainViewModel.hasLocalCellsWithin50m(lat: Double, lng: Double): Boolean =
    environmentRecordsAt(lat, lng).any { it.cells.isNotEmpty() }

internal suspend fun MainViewModel.fetchRemoteWifiSync(lat: Double, lng: Double, lookup: LocationLookupFeedback?) {
    withContext(Dispatchers.Main) {
        _uiState.update { it.copy(wifiLoadStatus = com.vincenthzr.locationspoofer.data.model.WifiLoadStatus.LOADING) }
    }
    var appleRecords: Int? = null
    val imported = try {
        val onRecordsFound: ((Int) -> Unit)? = lookup?.let {
            { count ->
                appleRecords = count
                reportLocationLookup(lookup, WIFI, appleWifiLine(count))
                reportLocationLookup(lookup, WIFI_METADATA, R.string.lookup_metadata_pending)
            }
        }
        val result = wifiRepository.fetchAppleWifi(lat, lng, onRecordsFound)
        withTimeoutOrNull(5_000) {
            environmentLocations.first { records ->
                records.any { it.location.id == result.recordId && it.wifis.isNotEmpty() }
            }
        } ?: error("Imported Wi-Fi was not observed")
        val recordCount = environmentDao.getRecordCount()
        _uiState.update { it.copy(environmentRecordCount = recordCount) }
        result
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        // The existing WiGLE path remains a fallback when Apple has no usable data.
        val count = appleRecords
        if (count != null) {
            reportLocationLookup(lookup, WIFI, R.string.lookup_wifi_import_failed, count)
        } else {
            reportLocationLookup(lookup, WIFI, if (error is RemoteWifiNoCoverageException)
                R.string.lookup_wifi_no_data else R.string.lookup_wifi_failed)
        }
        val metadataLine = when {
            count != null -> R.string.lookup_metadata_unfinished
            error is RemoteWifiNoCoverageException || error is UnknownHostException ->
                R.string.lookup_metadata_no_apple_records
            else -> R.string.lookup_metadata_not_requested
        }
        reportLocationLookup(lookup, WIFI_METADATA, metadataLine)
        null
    }
    if (imported != null) {
        reportLocationLookup(lookup, WIFI, appleWifiLine(imported.accessPointCount, imported.reusedPreview))
        reportLocationLookup(lookup, WIFI_METADATA, appleMetadataLine(imported))
        return
    }
    val settingsToken = settingsRepository.getWigleApiToken()
    if (settingsToken.isBlank()) {
        withContext(Dispatchers.Main) {
            _uiState.update {
                it.copy(
                    wifiLoadStatus = com.vincenthzr.locationspoofer.data.model.WifiLoadStatus.IDLE,
                    wifiApCount = 0,
                    canMockWifi = false
                )
            }
        }
        return
    }

    withContext(Dispatchers.Main) {
        _uiState.update { it.copy(wifiLoadStatus = com.vincenthzr.locationspoofer.data.model.WifiLoadStatus.LOADING) }
    }
    // 将坐标转换对齐至 WGS-84 标准以用于 WiGLE API
    val wgs84 = com.vincenthzr.locationspoofer.utils.CoordinateUtils.gcj02ToWgs84(lat, lng)
    val wgsLat = wgs84.lat
    val wgsLng = wgs84.lng

    reportLocationLookup(lookup, WIGLE_WIFI, R.string.lookup_wigle_pending)
    try {
        val reply = wifiRepository.fetchWifiDataResult(wgsLat, wgsLng, settingsToken)
        val records = (reply as? WigleWifiLookupResult.Records)?.json ?: "[]"
        val nearbyArr = org.json.JSONArray(records)
        if (nearbyArr.length() > 0) {
            val wifiObj = org.json.JSONObject()
            // A WiGLE observation describes visible access points, not the
            // handset's current association. Keep connection state unknown until
            // a collected record or an explicit user selection supplies it.
            wifiObj.put("isConnected", false)
            wifiObj.put("connectedWifi", org.json.JSONObject.NULL)

            val formattedNearby = org.json.JSONArray()
            for (i in 0 until nearbyArr.length()) {
                val ap = nearbyArr.getJSONObject(i)
                val bssid = ap.optString("bssid")
                val ssid = ap.optString("ssid")
                val level = ap.optInt("level", -100)
                val freq = ap.optInt("frequency", 0)

                val itemObj = org.json.JSONObject().apply {
                    put("bssid", bssid)
                    put("ssid", ssid)
                    put(
                        "vendor",
                        com.vincenthzr.locationspoofer.utils.MacVendorHelper.getVendor(bssid)
                    )
                    put("level", level)
                    put("capabilities", ap.optString("capabilities", ""))
                    put("frequency", freq)
                    put(
                        "channel",
                        com.vincenthzr.locationspoofer.utils.MacVendorHelper.frequencyToChannel(
                            freq
                        )
                    )
                }
                formattedNearby.put(itemObj)
            }
            wifiObj.put("nearbyWifi", formattedNearby)

            val formattedWifiJson = wifiObj.toString()

            withContext(Dispatchers.IO) {
                saveEnvironmentData(lat, lng, formattedWifiJson, "[]", "[]")
                // 更新 metadata 以指示 WiGLE 来源
                val newestLocation = environmentDao.getAllLocations()
                    .firstOrNull { it.lat == lat && it.lng == lng }
                if (newestLocation != null) {
                    val latLngPrefix = context.getString(R.string.lat_lng_format_prefix)
                    environmentDao.updateMetadata(
                        newestLocation.id,
                        lat,
                        lng,
                        context.getString(R.string.wigle_import),
                        "$latLngPrefix (${String.format("%.6f", lat)}, ${
                            String.format(
                                "%.6f",
                                lng
                            )
                        })",
                        null, null, null
                    )
                }
            }

            reportLocationLookup(lookup, WIGLE_WIFI, R.string.lookup_wigle_count, formattedNearby.length())
            withContext(Dispatchers.Main) {
                evaluateMockCapabilities()
                // 刷新记录统计总数
                viewModelScope.launch(Dispatchers.IO) {
                    val count = environmentDao.getRecordCount()
                    _uiState.update { it.copy(environmentRecordCount = count) }
                }
            }
        } else {
            reportLocationLookup(lookup, WIGLE_WIFI, wigleFallbackEmptyLine(reply))
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        wifiLoadStatus = com.vincenthzr.locationspoofer.data.model.WifiLoadStatus.IDLE,
                        wifiApCount = 0,
                        canMockWifi = false
                    )
                }
            }
        }
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        reportLocationLookup(lookup, WIGLE_WIFI, R.string.lookup_wigle_failed)
        android.util.Log.w("LocationSpoofer", "WiGLE fallback unavailable: ${error.javaClass.simpleName}")
        withContext(Dispatchers.Main) {
            _uiState.update {
                it.copy(
                    wifiLoadStatus = com.vincenthzr.locationspoofer.data.model.WifiLoadStatus.IDLE,
                    wifiApCount = 0,
                    canMockWifi = false
                )
            }
        }
    }
}

internal suspend fun MainViewModel.fetchRemoteCellsSync(lat: Double, lng: Double, lookup: LocationLookupFeedback?) {
    reportLocationLookup(lookup, CELL, R.string.lookup_cell_pending)
    val position = com.vincenthzr.locationspoofer.utils.CoordinateUtils.gcj02ToWgs84(lat, lng)
    val region = if (position.lat in 18.0..54.0 && position.lng in 73.0..135.0)
        com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion.MAINLAND_CHINA
    else com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion.GLOBAL
    var appleRecords: Int? = null
    try {
        val latest = try { remoteCellRepository.loadLatest() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { null }
        val cached = latest?.takeIf {
            System.currentTimeMillis() - it.fetchedAt in 0..86_400_000L &&
                com.vincenthzr.locationspoofer.utils.GeoMath.distance(position,
                    com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng(it.latitude, it.longitude)) <= 2.0
        }
        val snapshot = cached ?: remoteCellRepository.query(position.lat, position.lng, region)
        appleRecords = snapshot.points.size
        reportLocationLookup(lookup, CELL, if (cached != null)
            R.string.lookup_cell_preview else R.string.lookup_cell_count, snapshot.points.size)
        val id = remoteEnvironmentImporter.importCells(snapshot)
        withTimeoutOrNull(5_000) {
            environmentLocations.first { records -> records.any { it.location.id == id && it.cells.isNotEmpty() } }
        } ?: error("Imported cells were not observed")
        val recordCount = environmentDao.getRecordCount()
        _uiState.update { it.copy(environmentRecordCount = recordCount) }
        return
    } catch (error: kotlinx.coroutines.CancellationException) { throw error }
    catch (error: com.vincenthzr.locationspoofer.utils.OpenCellLookupException) {
        reportLocationLookup(lookup, CELL, R.string.lookup_cell_failed)
        reportLocationLookup(lookup, OPEN_CELL, R.string.lookup_open_cell_failed)
        android.util.Log.w("LocationSpoofer", "OpenCellID discovery failed: HTTP=${error.httpStatus}, code=${error.apiCode}")
        return
    }
    catch (error: Exception) {
        val count = appleRecords
        if (count != null) {
            reportLocationLookup(lookup, CELL, R.string.lookup_cell_import_failed, count)
        } else {
            reportLocationLookup(lookup, CELL, when (error) {
                is RemoteCellNoSeedException -> R.string.lookup_cell_no_seed
                is RemoteCellNoCoverageException -> R.string.lookup_cell_no_data
                else -> R.string.lookup_cell_failed
            })
        }
    }
    val tokenToUse = settingsRepository.getOpencellidApiToken()
    if (tokenToUse.isBlank()) {
        return
    }
    val wgs84 = com.vincenthzr.locationspoofer.utils.CoordinateUtils.gcj02ToWgs84(lat, lng)
    val wgsLat = wgs84.lat
    val wgsLng = wgs84.lng

    reportLocationLookup(lookup, OPEN_CELL, R.string.lookup_open_cell_pending)
    try {
        val rawJsonArrayString = opencellidClient.fetchCellData(wgsLat, wgsLng, tokenToUse)
        val cellsArray = org.json.JSONArray(rawJsonArrayString)
        if (cellsArray.length() > 0) {
            val formattedCells = normalizeCellArrayForStorage(cellsArray)
            if (formattedCells.length() == 0) {
                reportLocationLookup(lookup, OPEN_CELL, R.string.lookup_open_cell_no_data)
                return
            }

            withContext(Dispatchers.IO) {
                saveEnvironmentData(lat, lng, "{}", formattedCells.toString(), "[]")
                val newestLocation = environmentDao.getAllLocations()
                    .firstOrNull { it.lat == lat && it.lng == lng }
                if (newestLocation != null) {
                    val latLngPrefix = context.getString(R.string.lat_lng_format_prefix)
                    environmentDao.updateMetadata(
                        newestLocation.id,
                        lat,
                        lng,
                        context.getString(R.string.opencellid_import),
                        "$latLngPrefix (${String.format("%.6f", lat)}, ${
                            String.format(
                                "%.6f",
                                lng
                            )
                        })",
                        null, null, null
                    )
                }
            }

            reportLocationLookup(lookup, OPEN_CELL, R.string.lookup_open_cell_count, formattedCells.length())
            withContext(Dispatchers.Main) {
                evaluateMockCapabilities()
                // 刷新记录统计总数
                viewModelScope.launch(Dispatchers.IO) {
                    val count = environmentDao.getRecordCount()
                    _uiState.update { it.copy(environmentRecordCount = count) }
                }
            }
        } else {
            reportLocationLookup(lookup, OPEN_CELL, R.string.lookup_open_cell_no_data)
        }
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        reportLocationLookup(lookup, OPEN_CELL, R.string.lookup_open_cell_failed)
        android.util.Log.w("LocationSpoofer", "OpenCellID fallback unavailable: ${error.javaClass.simpleName}")
    }
}
