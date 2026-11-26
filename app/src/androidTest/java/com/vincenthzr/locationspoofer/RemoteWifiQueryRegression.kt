package com.vincenthzr.locationspoofer

import android.os.Bundle
import android.util.AtomicFile
import com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion
import com.vincenthzr.locationspoofer.data.repository.RemoteWifiRepository
import com.vincenthzr.locationspoofer.utils.AppleWifiTileClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import androidx.room.Room
import com.vincenthzr.locationspoofer.data.db.*
import com.vincenthzr.locationspoofer.data.repository.RemoteEnvironmentImporter
import com.vincenthzr.locationspoofer.data.model.*
import com.vincenthzr.locationspoofer.utils.CoordinateUtils

/** Exercises the phone network and an isolated cache without publishing simulation configuration. */
internal fun SystemFilesInstrumentation.verifyRemoteWifiQuery(): Bundle = runBlocking {
    val output = Bundle()
    val cacheFile = File(targetContext.cacheDir, "remote-wifi-regression.json")
    val offlineClient = OkHttpClient.Builder().addInterceptor {
        throw UnknownHostException("Deterministic offline regression")
    }.build()
    val database = Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java).build()
    try {
        val repository = RemoteWifiRepository(AppleWifiTileClient(), cacheFile)
        val online = repository.query(30.6544979, 113.1366294, RemoteWifiRegion.MAINLAND_CHINA)
        check(online.points.isNotEmpty())
        check(online.points.all { it.distanceMeters in 0.0..2_000.0 })
        check(online.points.map { it.bssid }.distinct().size == online.points.size)

        val restoredRepository = RemoteWifiRepository(AppleWifiTileClient(offlineClient), cacheFile)
        val restored = restoredRepository.loadLatest() ?: error("Disk cache was lost")
        check(restored == online) { "Disk cache did not round-trip" }
        val offlineFailed = try {
            restoredRepository.query(30.6544979, 113.1366294, RemoteWifiRegion.MAINLAND_CHINA)
            false
        } catch (_: IOException) {
            true
        }
        check(offlineFailed) { "Offline request unexpectedly succeeded" }
        check(restoredRepository.loadLatest() == online) { "Failed query overwrote cached result" }
        val dao = database.environmentDao()
        val gcj = CoordinateUtils.wgs84ToGcj02(online.latitude, online.longitude)
        val existingId = dao.insertLocation(LocationRecord(lat = gcj.lat, lng = gcj.lng, placeName = "keep-name", remark = "keep-remark"))
        val knownBssid = online.points.first().bssid
        dao.insertWifiDevice(WifiDevice(knownBssid, "keep-ssid", 5180, "keep-capabilities"))
        dao.insertLocationWifi(LocationWifi(existingId, knownBssid, -42))
        dao.insertBluetoothDevice(BluetoothDevice("02:00:00:00:01:02", "keep-bluetooth"))
        dao.insertLocationBluetooth(LocationBluetooth(existingId, "02:00:00:00:01:02", -52))
        val importer = RemoteEnvironmentImporter(database)
        check(importer.importWifi(online) == existingId)
        check(importer.importWifi(online) == existingId)
        val imported = checkNotNull(dao.getCompleteLocationById(existingId))
        check(imported.wifis.size == online.points.size)
        check(imported.location.placeName == "keep-name" && imported.location.remark == "keep-remark")
        check(imported.bluetooths.single().locationBluetooth.rssi == -52)
        check(imported.connectedWifi == null)
        val known = imported.wifis.single { it.device.bssid == knownBssid }
        check(known.device.ssid == "keep-ssid" && known.device.frequency == 5180 && known.locationWifi.level == -42)
        check(imported.wifis.filter { it.device.bssid != knownBssid }.all {
            it.device.ssid.isEmpty() && it.device.frequency == 0 && it.locationWifi.level in -110..-35
        })
        val identity = RemoteCellIdentity(RemoteCellRadio.NR, 460, 0, 321, 68_719_476_735L)
        val cellSnapshot = RemoteCellSnapshot(online.latitude, online.longitude, online.region, identity, online.fetchedAt,
            listOf(RemoteCellPoint(identity, online.latitude, online.longitude, 0.0)))
        check(importer.importCells(cellSnapshot) == existingId)
        val importedCell = checkNotNull(dao.getCompleteLocationById(existingId)).cells.single()
        check(importedCell.device.nci == 68_719_476_735L && importedCell.device.mnc == 0)
        check(!importedCell.locationCell.isRegistered)
        val beforeCount = dao.getAllLocations().size
        try {
            importer.importCells(cellSnapshot.copy(latitude = 31.0, points = listOf(
                cellSnapshot.points.first(), cellSnapshot.points.first().copy(identity = identity.copy(id = Long.MAX_VALUE)))))
            error("Invalid import unexpectedly succeeded")
        } catch (_: IllegalArgumentException) { }
        check(dao.getAllLocations().size == beforeCount)

        output.putInt("records", online.points.size)
        output.putString("endpoint", online.region.host)
        output.putString("cache_round_trip", "PASS")
        output.putString("offline_cache_preserved", "PASS")
        output.putString("environment_import_idempotent", "PASS")
        output.putString("existing_observations_preserved", "PASS")
        output.putString("unknown_fields_and_nr_36bit", "PASS")
        output.putString("import_transaction_rollback", "PASS")
        output.putString("result", "PASS")
    } catch (error: Throwable) {
        output.putString("result", "FAIL: ${error.stackTraceToString()}")
    } finally {
        AtomicFile(cacheFile).delete()
        database.close()
        offlineClient.connectionPool.evictAll()
        offlineClient.dispatcher.executorService.shutdown()
    }
    output
}
