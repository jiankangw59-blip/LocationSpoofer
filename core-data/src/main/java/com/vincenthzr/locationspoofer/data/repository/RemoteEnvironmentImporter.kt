package com.vincenthzr.locationspoofer.data.repository

import androidx.room.withTransaction
import com.vincenthzr.locationspoofer.data.db.*
import com.vincenthzr.locationspoofer.data.model.*
import com.vincenthzr.locationspoofer.utils.CoordinateUtils
import com.vincenthzr.locationspoofer.utils.GeoMath
import com.vincenthzr.locationspoofer.utils.RemoteSignalModel
import com.vincenthzr.locationspoofer.utils.WifiBssid

/** Imports an explicitly selected lookup into the existing environment pipeline, without activating simulation. */
class RemoteEnvironmentImporter(private val database: AppDatabase) {
    suspend fun importWifi(snapshot: RemoteWifiSnapshot): Long = database.withTransaction {
        require(snapshot.points.size in 1..100)
        val points = snapshot.points.associateBy { requireNotNull(WifiBssid.normalize(it.bssid)) }
        val dao = database.environmentDao()
        val id = location(snapshot.latitude, snapshot.longitude, snapshot.fetchedAt)
        val saved = checkNotNull(dao.getCompleteLocationById(id))
        val target = CoordinateUtils.wgs84ToGcj02(snapshot.latitude, snapshot.longitude)
        val samePoint = GeoMath.distance(target, CoordinateUtils.LatLng(saved.location.lat, saved.location.lng)) <= 2.0
        val protectedBssids = listOfNotNull(saved.location.selectedWifiBssid, saved.connectedWifi?.bssid)
            .mapNotNull(WifiBssid::normalize).toSet()
        val existing = saved.wifis.groupBy { WifiBssid.normalize(it.device.bssid) }
        val known = dao.getWifiDevicesIgnoringCase(points.keys.toList()).groupBy { WifiBssid.normalize(it.bssid) }
        fun isOwned(link: LocationWithWifi): Boolean {
            val address = WifiBssid.normalize(link.device.bssid) ?: return false
            return link.locationWifi.remoteSource == APPLE_WIFI_LINK_SOURCE && address !in protectedBssids
        }
        points.forEach { (bssid, point) ->
            val links = existing[bssid].orEmpty()
            val link = links.firstOrNull { !isOwned(it) } ?: links.firstOrNull()
            val knownDevices = known[bssid].orEmpty()
            val original = link?.device ?: knownDevices.firstOrNull { it.frequency > 0 } ?: knownDevices.firstOrNull()
            val metadata = point.metadata
            val frequency = original?.frequency?.takeIf { it > 0 } ?: metadata?.frequencyMHz ?: 0
            val device = original?.copy(
                ssid = original.ssid.ifEmpty { metadata?.ssid.orEmpty() },
                frequency = frequency
            ) ?: WifiDevice(bssid, ssid = metadata?.ssid.orEmpty(), frequency = frequency)
            if (device != original) dao.insertWifiDevice(device)
            val derivedFrequency = point.effectiveFrequencyMHz.takeIf { frequency == 0 }
            val previous = link?.locationWifi
            val updated = when {
                previous == null -> LocationWifi(
                    id, device.bssid, RemoteSignalModel.wifi(point.distanceMeters),
                    derivedFrequency, APPLE_WIFI_LINK_SOURCE
                )
                !samePoint -> previous
                isOwned(checkNotNull(link)) -> previous.copy(
                    level = RemoteSignalModel.wifi(point.distanceMeters), appleWlocDerivedFrequencyMHz = derivedFrequency
                )
                else -> previous.copy(appleWlocDerivedFrequencyMHz = derivedFrequency)
            }
            if (updated != previous) dao.insertLocationWifi(updated)
        }
        if (samePoint) saved.wifis.asSequence().filter(::isOwned)
            .filter { WifiBssid.normalize(it.device.bssid) !in points }
            .map { it.device.bssid }.chunked(100)
            .forEach { dao.deleteOwnedAppleWifiLinks(id, it) }
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
