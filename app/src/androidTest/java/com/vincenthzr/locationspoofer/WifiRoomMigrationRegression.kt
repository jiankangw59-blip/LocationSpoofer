package com.vincenthzr.locationspoofer

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.vincenthzr.locationspoofer.data.db.AppDatabase
import java.util.UUID

/** Schema from a v9 Room database. The rows inserted into it are synthetic. */
private val wifiMigrationV9Schema = listOf(
    """CREATE TABLE location_records (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL,
        timestamp INTEGER NOT NULL, placeName TEXT NOT NULL, remark TEXT NOT NULL, selectedWifiBssid TEXT,
        selectedBluetoothAddress TEXT, selectedCellKey TEXT, collectionRouteId INTEGER,
        FOREIGN KEY(collectionRouteId) REFERENCES collection_routes(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE TABLE location_connected_wifi (
        locationId INTEGER NOT NULL PRIMARY KEY, bssid TEXT NOT NULL, ssid TEXT NOT NULL,
        vendor TEXT NOT NULL, macAddress TEXT NOT NULL, frequency INTEGER NOT NULL, linkSpeed INTEGER NOT NULL,
        level INTEGER NOT NULL, capabilities TEXT NOT NULL, networkId INTEGER NOT NULL, wifiStandard INTEGER NOT NULL,
        FOREIGN KEY(locationId) REFERENCES location_records(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE TABLE wifi_devices (
        bssid TEXT NOT NULL PRIMARY KEY, ssid TEXT NOT NULL, frequency INTEGER NOT NULL,
        capabilities TEXT NOT NULL, vendor TEXT NOT NULL)""",
    """CREATE TABLE location_wifi (
        locationId INTEGER NOT NULL, bssid TEXT NOT NULL, level INTEGER NOT NULL, PRIMARY KEY(locationId,bssid),
        FOREIGN KEY(locationId) REFERENCES location_records(id) ON UPDATE NO ACTION ON DELETE CASCADE,
        FOREIGN KEY(bssid) REFERENCES wifi_devices(bssid) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE TABLE bluetooth_devices (
        address TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, scanRecordHex TEXT NOT NULL)""",
    """CREATE TABLE location_bluetooth (
        locationId INTEGER NOT NULL, address TEXT NOT NULL, rssi INTEGER NOT NULL, PRIMARY KEY(locationId,address),
        FOREIGN KEY(locationId) REFERENCES location_records(id) ON UPDATE NO ACTION ON DELETE CASCADE,
        FOREIGN KEY(address) REFERENCES bluetooth_devices(address) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE TABLE cell_devices (
        cellKey TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, mcc INTEGER NOT NULL, mnc INTEGER NOT NULL,
        tac INTEGER NOT NULL, ci INTEGER NOT NULL, pci INTEGER NOT NULL, lac INTEGER NOT NULL, cid INTEGER NOT NULL,
        psc INTEGER NOT NULL, nci INTEGER NOT NULL, networkId INTEGER NOT NULL, systemId INTEGER NOT NULL,
        basestationId INTEGER NOT NULL)""",
    """CREATE TABLE location_cells (
        locationId INTEGER NOT NULL, cellKey TEXT NOT NULL, dbm INTEGER NOT NULL, isRegistered INTEGER NOT NULL,
        PRIMARY KEY(locationId,cellKey),
        FOREIGN KEY(locationId) REFERENCES location_records(id) ON UPDATE NO ACTION ON DELETE CASCADE,
        FOREIGN KEY(cellKey) REFERENCES cell_devices(cellKey) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE TABLE saved_routes (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, pointsJson TEXT NOT NULL,
        timestamp INTEGER NOT NULL)""",
    """CREATE TABLE collection_routes (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, remark TEXT NOT NULL,
        pointsJson TEXT NOT NULL, halfWidthM REAL NOT NULL, timestamp INTEGER NOT NULL)""",
    "CREATE INDEX index_location_records_collectionRouteId ON location_records(collectionRouteId)",
    "CREATE INDEX index_location_records_lat_lng ON location_records(lat,lng)",
    "CREATE INDEX index_location_records_timestamp ON location_records(timestamp)",
    "CREATE INDEX index_location_wifi_bssid ON location_wifi(bssid)",
    "CREATE INDEX index_location_bluetooth_address ON location_bluetooth(address)",
    "CREATE INDEX index_location_cells_cellKey ON location_cells(cellKey)"
)

internal suspend fun SystemFilesInstrumentation.verifyWifiRoomMigration() {
    val databaseName = "wifi-v9-regression-${UUID.randomUUID()}"
    val path = targetContext.getDatabasePath(databaseName)
    check(!path.exists())
    val parent = checkNotNull(path.parentFile)
    check(parent.isDirectory || parent.mkdirs())
    val legacyBssid = "02:00:00:00:00:B1"
    val unknownBssid = "02:00:00:00:00:B2"
    try {
        SQLiteDatabase.openOrCreateDatabase(path, null).use { raw ->
            wifiMigrationV9Schema.forEach { raw.execSQL(it) }
            raw.execSQL(
                "INSERT INTO location_records (id,lat,lng,timestamp,placeName,remark,selectedWifiBssid) VALUES (101,30,113,7,'fixture-point','legacy',?)",
                arrayOf(legacyBssid)
            )
            raw.execSQL("INSERT INTO wifi_devices VALUES (?,'fixture-ssid',5180,'fixture-capabilities','fixture-vendor')",
                arrayOf(legacyBssid))
            raw.execSQL("INSERT INTO wifi_devices VALUES (?,'',0,'','')", arrayOf(unknownBssid))
            raw.execSQL("INSERT INTO location_wifi VALUES (101,?,-47),(101,?,-69)", arrayOf(legacyBssid, unknownBssid))
            raw.execSQL(
                "INSERT INTO location_connected_wifi VALUES (101,?,'fixture-ssid','fixture-vendor',?,5180,65,-47,'fixture-capabilities',1,6)",
                arrayOf(legacyBssid, legacyBssid)
            )
            raw.version = 9
        }
        val database = Room.databaseBuilder(targetContext, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabase.MIGRATION_9_10).build()
        try {
            val migrated = checkNotNull(database.environmentDao().getCompleteLocationById(101))
            val opened = database.openHelper.readableDatabase
            check(opened.version == 10)
            opened.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) }
            check(migrated.location.placeName == "fixture-point" && migrated.location.timestamp == 7L)
            check(migrated.location.selectedWifiBssid == legacyBssid && migrated.connectedWifi?.frequency == 5180)
            check(migrated.wifis.size == 2)
            val legacy = migrated.wifis.single { it.device.bssid == legacyBssid }
            val unknown = migrated.wifis.single { it.device.bssid == unknownBssid }
            check(legacy.effectiveFrequencyMHz == 5180 && legacy.locationWifi.level == -47)
            check(legacy.device.ssid == "fixture-ssid" && legacy.device.capabilities == "fixture-capabilities")
            check(unknown.effectiveFrequencyMHz == 0 && unknown.locationWifi.level == -69)
            check(migrated.wifis.all {
                it.locationWifi.remoteSource == null && it.locationWifi.appleWlocDerivedFrequencyMHz == null
            })
        } finally {
            database.close()
        }
    } finally {
        check(targetContext.deleteDatabase(databaseName) || !path.exists())
    }
}
