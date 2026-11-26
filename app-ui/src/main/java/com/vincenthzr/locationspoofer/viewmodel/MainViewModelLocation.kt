package com.vincenthzr.locationspoofer.viewmodel

import android.content.Context
import java.util.Locale
import androidx.lifecycle.viewModelScope
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.vincenthzr.locationspoofer.ui.R
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

// 当前位置获取
internal fun MainViewModel.fetchCurrentLocation(ctx: Context, forceCallback: ((Double, Double) -> Unit)? = null) {
    viewModelScope.launch(Dispatchers.Main) {
        val client = try {
            AMapLocationClient(ctx.applicationContext)
        } catch (e: Exception) {
            fallbackToNativeLocation(ctx, forceCallback, true)
            return@launch
        }
        client.setLocationOption(AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = true
            isNeedAddress = false // 禁用逆地理编码，防止因未开通Web服务导致 SERVICE_NOT_EXIST 鉴权错误
        })
        client.setLocationListener { loc ->
            if (loc != null && loc.errorCode == 0) {
                if (_uiState.value.longitudeInput.isEmpty() || _uiState.value.latitudeInput.isEmpty() || forceCallback != null) {
                    _uiState.update {
                        it.copy(
                            latitudeInput = String.format("%.6f", loc.latitude),
                            longitudeInput = String.format("%.6f", loc.longitude),
                            showCoordinateError = false
                        )
                    }
                    forceCallback?.invoke(loc.latitude, loc.longitude)
                }
            } else {
                // 如果鉴权失败(如 SERVICE_NOT_EXIST)或其他错误，回退到原生定位
                fallbackToNativeLocation(ctx, forceCallback, true)
            }
            client.stopLocation()
            client.onDestroy()
        }
        client.startLocation()
    }
}

@android.annotation.SuppressLint("MissingPermission")

private fun MainViewModel.fallbackToNativeLocation(
    ctx: Context,
    forceCallback: ((Double, Double) -> Unit)?,
    convertToGcj: Boolean
) {
    try {
        val locationManager =
            ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val provider =
            if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                android.location.LocationManager.NETWORK_PROVIDER
            } else {
                android.location.LocationManager.GPS_PROVIDER
            }

        val lastLoc = locationManager.getLastKnownLocation(provider)
        if (lastLoc != null) {
            applyNativeLocation(ctx, lastLoc, forceCallback, convertToGcj)
        } else if (forceCallback != null) {
            android.widget.Toast.makeText(
                ctx,
                ctx.getString(com.vincenthzr.locationspoofer.ui.R.string.waiting_gps_signal),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                if (forceCallback != null) {
                    android.widget.Toast.makeText(
                        ctx,
                        ctx.getString(com.vincenthzr.locationspoofer.ui.R.string.native_location_success),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                applyNativeLocation(ctx, location, forceCallback, convertToGcj)
                locationManager.removeUpdates(this)
            }

            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: android.os.Bundle?
            ) {
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        locationManager.requestSingleUpdate(
            provider,
            listener,
            android.os.Looper.getMainLooper()
        )
    } catch (e: SecurityException) {
        if (forceCallback != null) {
            android.widget.Toast.makeText(
                ctx,
                ctx.getString(com.vincenthzr.locationspoofer.ui.R.string.location_permission_denied),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun MainViewModel.applyNativeLocation(
    ctx: Context,
    location: android.location.Location,
    forceCallback: ((Double, Double) -> Unit)?,
    convertToGcj: Boolean
) {
    var finalLat = location.latitude
    var finalLng = location.longitude

    if (convertToGcj) {
        val converter = com.amap.api.maps.CoordinateConverter(ctx).apply {
            from(com.amap.api.maps.CoordinateConverter.CoordType.GPS)
            coord(com.amap.api.maps.model.LatLng(location.latitude, location.longitude))
        }
        val gcj = converter.convert()
        finalLat = gcj.latitude
        finalLng = gcj.longitude
    }

    if (_uiState.value.longitudeInput.isEmpty() || _uiState.value.latitudeInput.isEmpty() || forceCallback != null) {
        _uiState.update {
            it.copy(
                latitudeInput = String.format("%.6f", finalLat),
                longitudeInput = String.format("%.6f", finalLng),
                showCoordinateError = false
            )
        }
        forceCallback?.invoke(finalLat, finalLng)
    }
}

internal suspend fun MainViewModel.fetchRealLocationSilent(ctx: Context): Pair<Double, Double>? {
    val amapResult = withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine<Pair<Double, Double>?> { cont ->
            val client = try {
                AMapLocationClient(ctx.applicationContext)
            } catch (error: Exception) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            fun closeClient() {
                runCatching { client.stopLocation(); client.onDestroy() }
            }
            cont.invokeOnCancellation { closeClient() }
            client.setLocationOption(AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
                isNeedAddress = false
            })
            client.setLocationListener { location ->
                closeClient()
                if (cont.isActive) {
                    cont.resume(if (location != null && location.errorCode == 0) {
                        location.latitude to location.longitude
                    } else null)
                }
            }
            if (cont.isActive) client.startLocation() else closeClient()
        }
    }
    return amapResult ?: fallbackToNativeLocationSilent(ctx, true)
}

@android.annotation.SuppressLint("MissingPermission")
private suspend fun MainViewModel.fallbackToNativeLocationSilent(
    ctx: Context,
    convertToGcj: Boolean
): Pair<Double, Double>? = withTimeoutOrNull(5_000) {
    suspendCancellableCoroutine { cont ->
        val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        var listener: android.location.LocationListener? = null
        cont.invokeOnCancellation { listener?.let { runCatching { locationManager.removeUpdates(it) } } }
        try {
            val provider = when {
                locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) -> android.location.LocationManager.NETWORK_PROVIDER
                locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) -> android.location.LocationManager.GPS_PROVIDER
                else -> {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
            }
            val lastLocation = locationManager.getLastKnownLocation(provider)
            if (lastLocation != null) {
                cont.resume(getNativeConverted(ctx, lastLocation, convertToGcj))
                return@suspendCancellableCoroutine
            }
            val callback = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    locationManager.removeUpdates(this)
                    if (cont.isActive) cont.resume(getNativeConverted(ctx, location, convertToGcj))
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            listener = callback
            locationManager.requestSingleUpdate(provider, callback, android.os.Looper.getMainLooper())
            if (!cont.isActive) locationManager.removeUpdates(callback)
        } catch (error: Exception) {
            listener?.let { runCatching { locationManager.removeUpdates(it) } }
            if (cont.isActive) cont.resume(null)
        }
    }
}

private fun MainViewModel.getNativeConverted(
    ctx: Context,
    location: android.location.Location,
    convertToGcj: Boolean
): Pair<Double, Double> {
    var finalLat = location.latitude
    var finalLng = location.longitude
    if (convertToGcj) {
        val converter = com.amap.api.maps.CoordinateConverter(ctx).apply {
            from(com.amap.api.maps.CoordinateConverter.CoordType.GPS)
            coord(com.amap.api.maps.model.LatLng(location.latitude, location.longitude))
        }
        val gcj = converter.convert()
        finalLat = gcj.latitude
        finalLng = gcj.longitude
    }
    return Pair(finalLat, finalLng)
}

// 坐标输入
internal fun MainViewModel.updateLongitude(value: String) {
    if (isValidCoord(value)) {
        _uiState.update { it.copy(longitudeInput = value, showCoordinateError = false) }
        evaluateMockCapabilities()
    }
}

internal fun MainViewModel.updateLatitude(value: String) {
    if (isValidCoord(value)) {
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

internal suspend fun MainViewModel.fetchRemoteWifiSync(lat: Double, lng: Double) {
    withContext(Dispatchers.Main) {
        _uiState.update { it.copy(wifiLoadStatus = com.vincenthzr.locationspoofer.data.model.WifiLoadStatus.LOADING) }
    }
    try {
        val id = wifiRepository.fetchAppleWifi(lat, lng)
        withTimeoutOrNull(5_000) {
            environmentLocations.first { records -> records.any { it.location.id == id && it.wifis.isNotEmpty() } }
        } ?: error("Imported Wi-Fi was not observed")
        val recordCount = environmentDao.getRecordCount()
        _uiState.update { it.copy(environmentRecordCount = recordCount) }
        return
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        // The existing WiGLE path remains a fallback when Apple has no usable data.
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

    try {
        val rawJsonArrayString = wifiRepository.fetchWifiData(wgsLat, wgsLng, settingsToken)
        val nearbyArr = org.json.JSONArray(rawJsonArrayString)
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

            withContext(Dispatchers.Main) {
                evaluateMockCapabilities()
                // 刷新记录统计总数
                viewModelScope.launch(Dispatchers.IO) {
                    val count = environmentDao.getRecordCount()
                    _uiState.update { it.copy(environmentRecordCount = count) }
                }
            }
        } else {
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

internal suspend fun MainViewModel.fetchRemoteCellsSync(lat: Double, lng: Double) {
    val position = com.vincenthzr.locationspoofer.utils.CoordinateUtils.gcj02ToWgs84(lat, lng)
    val region = if (position.lat in 18.0..54.0 && position.lng in 73.0..135.0)
        com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion.MAINLAND_CHINA
    else com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion.GLOBAL
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
        val id = remoteEnvironmentImporter.importCells(snapshot)
        withTimeoutOrNull(5_000) {
            environmentLocations.first { records -> records.any { it.location.id == id && it.cells.isNotEmpty() } }
        } ?: error("Imported cells were not observed")
        val recordCount = environmentDao.getRecordCount()
        _uiState.update { it.copy(environmentRecordCount = recordCount) }
        return
    } catch (error: kotlinx.coroutines.CancellationException) { throw error }
    catch (error: com.vincenthzr.locationspoofer.utils.OpenCellLookupException) {
        android.util.Log.w("LocationSpoofer", "OpenCellID discovery failed: HTTP=${error.httpStatus}, code=${error.apiCode}")
        return
    }
    catch (_: Exception) { /* Continue with existing OpenCellID data when expansion is unavailable. */ }
    val tokenToUse = settingsRepository.getOpencellidApiToken()
    if (tokenToUse.isBlank()) {
        return
    }
    val wgs84 = com.vincenthzr.locationspoofer.utils.CoordinateUtils.gcj02ToWgs84(lat, lng)
    val wgsLat = wgs84.lat
    val wgsLng = wgs84.lng

    try {
        val rawJsonArrayString = opencellidClient.fetchCellData(wgsLat, wgsLng, tokenToUse)
        val cellsArray = org.json.JSONArray(rawJsonArrayString)
        if (cellsArray.length() > 0) {
            val formattedCells = normalizeCellArrayForStorage(cellsArray)
            if (formattedCells.length() == 0) {
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

            withContext(Dispatchers.Main) {
                evaluateMockCapabilities()
                // 刷新记录统计总数
                viewModelScope.launch(Dispatchers.IO) {
                    val count = environmentDao.getRecordCount()
                    _uiState.update { it.copy(environmentRecordCount = count) }
                }
            }
        }
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        android.util.Log.w("LocationSpoofer", "OpenCellID fallback unavailable: ${error.javaClass.simpleName}")
    }
}
