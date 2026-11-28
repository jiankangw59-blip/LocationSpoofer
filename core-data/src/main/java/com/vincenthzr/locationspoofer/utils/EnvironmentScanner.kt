package com.vincenthzr.locationspoofer.utils

import android.annotation.SuppressLint
import android.os.SystemClock
import android.content.Context
import android.net.wifi.WifiManager
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@android.annotation.SuppressLint("MissingPermission", "NewApi")
class EnvironmentScanner(private val context: Context) {

    @SuppressLint("MissingPermission")
    suspend fun scanWifi(): String = scanWifiObservation().recordsJson

    @SuppressLint("MissingPermission")
    suspend fun scanWifiObservation(): RadioCapture<String> = withContext(Dispatchers.IO) {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectivityManager =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val resultObj = JSONObject()
        var status: CaptureStatus
        var observed: Map<String, Long> = emptyMap()
        try {
            var isWifiConnected = false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                val networks = connectivityManager.allNetworks
                for (network in networks) {
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                        // 确保这个 Wi-Fi 网络具备基础的连接能力
                        if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        ) {
                            isWifiConnected = true
                            break
                        }
                    }
                }
            } else {
                val activeNetwork = connectivityManager.activeNetworkInfo
                isWifiConnected =
                    activeNetwork != null && activeNetwork.type == android.net.ConnectivityManager.TYPE_WIFI && activeNetwork.isConnected
            }

            resultObj.put("isConnected", isWifiConnected)

            val connectionInfo = wifiManager.connectionInfo
            val connectedBssid = if (isWifiConnected) connectionInfo?.bssid else null
            val read = RecentWifiCapture.read(context, wifiManager)
            val results = read.rows

            // 1. 优先提取当前正在连接的 Wi-Fi 信息
            if (isWifiConnected && connectedBssid != null && connectedBssid != "02:00:00:00:00:00") {
                val connObj = JSONObject()
                connObj.put("bssid", connectedBssid)
                val rawSsid = connectionInfo.ssid
                var cleanSsid =
                    if (rawSsid != null && rawSsid.startsWith("\"") && rawSsid.endsWith("\"")) {
                        rawSsid.substring(1, rawSsid.length - 1)
                    } else {
                        rawSsid ?: ""
                    }

                val match = results.find { it.BSSID.equals(connectedBssid, ignoreCase = true) }
                // 如果是 unknown ssid 或为空，尝试从扫描结果中恢复真实 SSID
                if (cleanSsid == "<unknown ssid>" || cleanSsid.isEmpty()) {
                    if (match != null && !match.SSID.isNullOrEmpty()) {
                        cleanSsid = match.SSID
                    }
                }

                connObj.put("ssid", if (cleanSsid == "<unknown ssid>") "" else cleanSsid)
                connObj.put("vendor", MacVendorHelper.getVendor(connectedBssid))
                connObj.put("level", connectionInfo.rssi)
                connObj.put("frequency", connectionInfo.frequency)
                connObj.put("channel", MacVendorHelper.frequencyToChannel(connectionInfo.frequency))
                connObj.put("capabilities", match?.capabilities.orEmpty())
                try {
                    connObj.put("macAddress", connectionInfo.macAddress)
                } catch (e: Throwable) {
                }
                try {
                    connObj.put("linkSpeed", connectionInfo.linkSpeed)
                } catch (e: Throwable) {
                }
                try {
                    connObj.put("networkId", connectionInfo.networkId)
                } catch (e: Throwable) {
                }
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    try {
                        connObj.put("wifiStandard", connectionInfo.wifiStandard)
                    } catch (e: Throwable) {
                    }
                }
                resultObj.put("connectedWifi", connObj)
            } else {
                resultObj.put("connectedWifi", JSONObject.NULL)
            }

            // 2. 提取周围扫描到的其他 Wi-Fi（去重，不包含当前已连接的）
            val nearbyArray = JSONArray()
            val seenBssids = mutableSetOf<String>()
            if (isWifiConnected && connectedBssid != null) {
                seenBssids.add(connectedBssid)
            }
            results.forEach { scanResult ->
                val bssid = scanResult.BSSID
                if (bssid != null && !seenBssids.contains(bssid)) {
                    seenBssids.add(bssid)
                    val obj = JSONObject()
                    obj.put("bssid", bssid)
                    obj.put("ssid", scanResult.SSID ?: "")
                    obj.put("vendor", MacVendorHelper.getVendor(bssid))
                    obj.put("level", scanResult.level)
                    obj.put("capabilities", scanResult.capabilities ?: "")
                    obj.put("frequency", scanResult.frequency)
                    obj.put("channel", MacVendorHelper.frequencyToChannel(scanResult.frequency))
                    nearbyArray.put(obj)
                }
            }
            resultObj.put("nearbyWifi", nearbyArray)
            status = read.status
            observed = read.observedAtMs
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            e.printStackTrace()
            status = if (e is SecurityException) CaptureStatus.PERMISSION_DENIED else CaptureStatus.FAILED
            resultObj.put("isConnected", false)
            resultObj.put("connectedWifi", JSONObject.NULL)
            resultObj.put("nearbyWifi", JSONArray())
        }
        RadioCapture(resultObj.toString(), status, observed)
    }

    @SuppressLint("MissingPermission")
    suspend fun scanCell(): String = scanCellObservation().recordsJson

    @SuppressLint("MissingPermission")
    suspend fun scanCellObservation(): RadioCapture<Int> = withContext(Dispatchers.IO) {
        // 之前这里连了 Wi-Fi 就直接跳过基站扫描，但 Wi-Fi 和基站是同一个物理位置的两个
        // 独立维度，并不互斥，跳过只会导致最常见的采集场景（家里/公司都连着 Wi-Fi）
        // 永远采不到基站数据，纯属早期写错，去掉这个限制。
        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val jsonArray = JSONArray()
        var read = ScanRead.SNAPSHOT
        val observed = mutableMapOf<Int, Long>()
        val operators = mutableMapOf<Int, CellOperatorEvidence>()
        val startUs = SystemClock.elapsedRealtimeNanos() / 1_000L
        try {
            val allCellInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val updated = try {
                    kotlinx.coroutines.withTimeoutOrNull(3000L) {
                        suspendCancellableCoroutine<List<CellInfo>?> { continuation ->
                            telephonyManager.requestCellInfoUpdate(context.mainExecutor,
                                object : TelephonyManager.CellInfoCallback() {
                                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                                        if (continuation.isActive) continuation.resume(cellInfo)
                                    }
                                    override fun onError(errorCode: Int, detail: Throwable?) {
                                        if (continuation.isActive) continuation.resume(null)
                                    }
                                })
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: SecurityException) {
                    read = ScanRead.DENIED
                    null
                } catch (_: Exception) {
                    read = ScanRead.FAILED
                    null
                }
                if (updated != null) {
                    read = ScanRead.UPDATED
                    updated
                } else {
                    if (read == ScanRead.SNAPSHOT) read = ScanRead.NOT_UPDATED
                    telephonyManager.allCellInfo.orEmpty()
                }
            } else telephonyManager.allCellInfo.orEmpty()
            val window = CaptureWindow(startUs, SystemClock.elapsedRealtimeNanos() / 1_000L, System.currentTimeMillis())

            if (allCellInfo.isEmpty()) {
                val cellLoc = try {
                    telephonyManager.cellLocation
                } catch (e: Exception) {
                    null
                }
                if (cellLoc is android.telephony.gsm.GsmCellLocation) {
                    val obj = JSONObject()
                    obj.put("isRegistered", true)
                    obj.put("type", "GSM")
                    obj.put("lac", cellLoc.lac)
                    obj.put("cid", cellLoc.cid)
                    obj.put("psc", cellLoc.psc)
                    putCapturedCellStrength(obj, null)
                    obj.put("mcc", 460)
                    obj.put("mnc", 0)
                    jsonArray.put(obj)
                } else if (cellLoc is android.telephony.cdma.CdmaCellLocation) {
                    val obj = JSONObject()
                    obj.put("isRegistered", true)
                    obj.put("type", "CDMA")
                    obj.put("networkId", cellLoc.networkId)
                    obj.put("systemId", cellLoc.systemId)
                    obj.put("basestationId", cellLoc.baseStationId)
                    putCapturedCellStrength(obj, null)
                    jsonArray.put(obj)
                }
            }

            allCellInfo.forEach { cellInfo ->
                val obj = JSONObject()
                var operator: CellOperatorEvidence? = null
                fun putOperator(mcc: String?, mnc: String?, readable: Boolean = true) {
                    // Keep legacy numeric storage independent of optional field evidence.
                    obj.put("mcc", mcc?.toIntOrNull() ?: 460)
                    obj.put("mnc", mnc?.toIntOrNull() ?: 0)
                    if (read == ScanRead.UPDATED && readable) operator = CellOperatorEvidence.fromReported(mcc, mnc)
                }
                obj.put("isRegistered", cellInfo.isRegistered)

                when (cellInfo) {
                    is CellInfoLte -> {
                        if (android.os.Build.VERSION.SDK_INT < 28) return@forEach
                        obj.put("type", "LTE")
                        val id = cellInfo.cellIdentity
                        putOperator(id.mccString, id.mncString)
                        obj.put("tac", id.tac)
                        obj.put("ci", id.ci)
                        obj.put("pci", id.pci)
                        val signal = cellInfo.cellSignalStrength
                        putCapturedCellStrength(obj, signal.dbm)
                    }

                    is CellInfoGsm -> {
                        if (android.os.Build.VERSION.SDK_INT < 28) return@forEach
                        obj.put("type", "GSM")
                        val id = cellInfo.cellIdentity
                        putOperator(id.mccString, id.mncString)
                        obj.put("lac", id.lac)
                        obj.put("cid", id.cid)
                        val signal = cellInfo.cellSignalStrength
                        putCapturedCellStrength(obj, signal.dbm)
                    }

                    is CellInfoWcdma -> {
                        if (android.os.Build.VERSION.SDK_INT < 28) return@forEach
                        obj.put("type", "WCDMA")
                        val id = cellInfo.cellIdentity
                        putOperator(id.mccString, id.mncString)
                        obj.put("lac", id.lac)
                        obj.put("cid", id.cid)
                        obj.put("psc", id.psc)
                        val signal = cellInfo.cellSignalStrength
                        putCapturedCellStrength(obj, signal.dbm)
                    }

                    is CellInfoCdma -> {
                        obj.put("type", "CDMA")
                        val id = cellInfo.cellIdentity
                        obj.put("networkId", id.networkId)
                        obj.put("systemId", id.systemId)
                        obj.put("basestationId", id.basestationId)
                        val signal = cellInfo.cellSignalStrength
                        putCapturedCellStrength(obj, signal.dbm)
                    }

                    else -> if (android.os.Build.VERSION.SDK_INT >= 29 && cellInfo is CellInfoNr) {
                        obj.put("type", "NR")
                        val id = cellInfo.cellIdentity as? android.telephony.CellIdentityNr
                        putOperator(id?.mccString, id?.mncString, readable = id != null)
                        obj.put("tac", id?.tac ?: Int.MAX_VALUE)
                        obj.put("nci", id?.nci ?: Long.MAX_VALUE)
                        obj.put("pci", id?.pci ?: Int.MAX_VALUE)
                        val signal =
                            cellInfo.cellSignalStrength as? android.telephony.CellSignalStrengthNr
                        putCapturedCellStrength(obj, signal?.dbm)
                    }
                }
                // Keep DB JSON identical; timestamp evidence is sidecar-only and uses the array index until the row is saved.
                if (read == ScanRead.UPDATED && obj.has("type")) {
                    @Suppress("DEPRECATION")
                    val sampled = window.sampleEpochMs(cellInfo.timeStamp / 1_000L)
                    if (sampled != null) {
                        val row = jsonArray.length()
                        observed[row] = sampled
                        operator?.let { operators[row] = it }
                    }
                }
                jsonArray.put(obj)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            read = if (e is SecurityException) ScanRead.DENIED else ScanRead.FAILED
            observed.clear()
            operators.clear()
            e.printStackTrace()
        }
        RadioCapture(jsonArray.toString(), captureStatus(read, jsonArray.length(), observed.size), observed, operators)
    }

    /** Kept for callers that consume records only; the first-party collection UI uses the richer outcome. */
    suspend fun scanBluetooth(): String = scanBluetoothObservation().recordsJson

    @SuppressLint("MissingPermission")
    suspend fun scanBluetoothObservation(onWindowSamples: ((Map<String, Long>) -> Unit)? = null): BleObservation = withContext(Dispatchers.IO) {
        try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: return@withContext BleObservation(BleScanStatus.UNAVAILABLE)
            // This collector associates BLE reports with location; its manifest does not assert neverForLocation.
            fun hasCapturePermissions(): Boolean {
                fun granted(permission: String) =
                    context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val sdk = android.os.Build.VERSION.SDK_INT
                val locationGranted = granted(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
                    (sdk < 29 && granted(android.Manifest.permission.ACCESS_COARSE_LOCATION))
                return locationGranted && (sdk < 31 || (granted(android.Manifest.permission.BLUETOOTH_SCAN) &&
                    granted(android.Manifest.permission.BLUETOOTH_CONNECT)))
            }
            if (!hasCapturePermissions()) return@withContext BleObservation(BleScanStatus.PERMISSION_DENIED)
            if (!adapter.isEnabled) return@withContext BleObservation(BleScanStatus.ADAPTER_OFF)
            val scanner = adapter.bluetoothLeScanner ?: return@withContext BleObservation(BleScanStatus.UNAVAILABLE)

            lateinit var callback: ScanCallback
            val startUs = SystemClock.elapsedRealtimeNanos() / 1_000L
            val window = collectBleWindow<ScanResult>(2000L, start = { offer, fail ->
                callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult?) { result?.let(offer) }
                    override fun onBatchScanResults(results: MutableList<ScanResult>?) { results?.forEach(offer) }
                    override fun onScanFailed(errorCode: Int) { fail() }
                }
                scanner.startScan(callback)
            }, stop = { scanner.stopScan(callback) })
            // A changed radio/permission at either end is not a successful empty observation.
            if (!hasCapturePermissions()) return@withContext BleObservation(BleScanStatus.PERMISSION_DENIED)
            if (!adapter.isEnabled) return@withContext BleObservation(BleScanStatus.ADAPTER_OFF)
            if (window == null) return@withContext BleObservation(BleScanStatus.FAILED)
            val records = JSONArray()
            window.distinctBy { it.device.address }.forEach { res ->
                val obj = JSONObject().put("address", res.device.address)
                    .put("name", res.device.name ?: "").put("rssi", res.rssi)
                res.scanRecord?.bytes?.let { bytes ->
                    obj.put("scanRecordHex", bytes.joinToString("") { "%02X".format(it) })
                }
                records.put(obj)
            }
            if (onWindowSamples != null) {
                val clock = CaptureWindow(startUs, SystemClock.elapsedRealtimeNanos() / 1_000L, System.currentTimeMillis())
                val samples = mutableMapOf<String, Long>()
                for (item in window) {
                    val t = clock.sampleEpochMs(item.timestampNanos / 1_000L) ?: continue
                    // Optional history must not discard an otherwise completed payload if permissions change here.
                    val address = try { item.device.address } catch (_: SecurityException) { null } ?: continue
                    samples[address] = maxOf(samples[address] ?: t, t)
                }
                onWindowSamples(samples)
            }
            BleObservation.completed(records.toString(), records.length())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: SecurityException) {
            BleObservation(BleScanStatus.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            // BLE is optional; its failure must not discard this round's Wi-Fi/cell observations.
            BleObservation(BleScanStatus.FAILED)
        }
    }
}
