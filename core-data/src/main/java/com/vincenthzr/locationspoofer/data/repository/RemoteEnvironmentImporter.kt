package com.vincenthzr.locationspoofer.data.repository

import androidx.room.withTransaction
import com.vincenthzr.locationspoofer.data.db.*
import com.vincenthzr.locationspoofer.data.model.*
import com.vincenthzr.locationspoofer.utils.CoordinateUtils
import com.vincenthzr.locationspoofer.utils.RemoteSignalModel

/** Imports an explicitly selected lookup into the existing environment pipeline, without activating simulation. */
class RemoteEnvironmentImporter(private val database: AppDatabase) {
    suspend fun importWifi(snapshot: RemoteWifiSnapshot): Long = database.withTransaction {
        require(snapshot.points.isNotEmpty())
        val dao = database.environmentDao()
        val id = location(snapshot.latitude, snapshot.longitude, snapshot.fetchedAt)
        val existing = checkNotNull(dao.getCompleteLocationById(id)).wifis.map { it.device.bssid }.toSet()
        val known = dao.getWifiDevices(snapshot.points.map { it.bssid }).associateBy { it.bssid }
        snapshot.points.forEach { point ->
            val original = known[point.bssid]
            val metadata = point.metadata
            val device = original?.copy(
                ssid = original.ssid.ifEmpty { metadata?.ssid.orEmpty() },
                frequency = original.frequency.takeIf { it > 0 } ?: metadata?.frequencyMHz ?: 0
            ) ?: WifiDevice(point.bssid, ssid = metadata?.ssid.orEmpty(), frequency = metadata?.frequencyMHz ?: 0)
            if (device != original) dao.insertWifiDevice(device)
            if (point.bssid !in existing) {
                dao.insertLocationWifi(LocationWifi(id, point.bssid, RemoteSignalModel.wifi(point.distanceMeters)))
            }
        }
        id
    }

    suspend fun importCells(snapshot: RemoteCellSnapshot): Long = database.withTransaction {
        require(snapshot.points.isNotEmpty())
        val dao = database.environmentDao()
        val id = location(snapshot.latitude, snapshot.longitude, snapshot.fetchedAt)
        val existing = checkNotNull(dao.getCompleteLocationById(id)).cells.map { it.device.cellKey }.toSet()
        snapshot.points.forEach { point ->
            val identity = point.identity
            identity.validate()
            val lte = identity.radio == RemoteCellRadio.LTE
            val ci = if (lte) identity.id.toInt() else 0
            val nci = if (lte) 0L else identity.id
            val key = "${identity.radio}_${identity.mcc}_${identity.mnc}_${identity.tac}_${ci}_0_0_${nci}"
            if (key !in existing) {
                if (dao.getCellDevice(key) == null) dao.insertCellDevice(CellDevice(
                    key, identity.radio.name, identity.mcc, identity.mnc, identity.tac, ci,
                    pci = Int.MAX_VALUE, nci = nci
                ))
                dao.insertLocationCell(LocationCell(id, key, RemoteSignalModel.cell(point.distanceMeters), isRegistered = false))
            }
        }
        id
    }

    private suspend fun location(latitude: Double, longitude: Double, fetchedAt: Long): Long {
        val position = CoordinateUtils.wgs84ToGcj02(latitude, longitude)
        val dao = database.environmentDao()
        return dao.findLocationByCoordinates(position.lat, position.lng)?.id ?: dao.insertLocation(LocationRecord(
            lat = position.lat, lng = position.lng, timestamp = fetchedAt,
            placeName = "Apple · 远程模拟数据",
            remark = "Apple 数据库标识；WiGLE 匹配字段（若有）；信号为距离模型估算；未采集蓝牙"
        ))
    }
}
