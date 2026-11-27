package com.vincenthzr.locationspoofer

import android.os.Bundle
import android.util.AtomicFile
import androidx.room.Room
import com.vincenthzr.locationspoofer.data.db.APPLE_WIFI_LINK_SOURCE
import com.vincenthzr.locationspoofer.data.db.AppDatabase
import com.vincenthzr.locationspoofer.data.db.LocationConnectedWifi
import com.vincenthzr.locationspoofer.data.db.LocationRecord
import com.vincenthzr.locationspoofer.data.db.LocationWifi
import com.vincenthzr.locationspoofer.data.db.WifiDevice
import com.vincenthzr.locationspoofer.data.model.RemoteWifiMetadataStatus
import com.vincenthzr.locationspoofer.data.model.RemoteWifiSource
import com.vincenthzr.locationspoofer.data.repository.RemoteEnvironmentImporter
import com.vincenthzr.locationspoofer.data.repository.RemoteWifiRepository
import com.vincenthzr.locationspoofer.data.repository.WifiRepository
import com.vincenthzr.locationspoofer.utils.AppleWifiDiscovery
import com.vincenthzr.locationspoofer.utils.AppleWifiTileClient
import com.vincenthzr.locationspoofer.utils.AppleWifiWlocClient
import com.vincenthzr.locationspoofer.utils.CoordinateUtils
import com.vincenthzr.locationspoofer.utils.WifiMetadataEnricher
import com.vincenthzr.locationspoofer.utils.WigleClient
import com.vincenthzr.locationspoofer.utils.WigleTransport
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val WLOC_FIXTURE_HEADER = "00000000000000000000"
private const val SEED_AP = "12240a1130323a30303a30303a30303a30303a6131120c0880bcc1960b1080a2a18c2aa8010b"
private const val NEIGHBOR_AP = "12240a1130323a30303a30303a30303a30303a6132120c08e4bcc1960b1080a2a18c2aa8010b"
private const val ZERO_CHANNEL_AP = "12240a1130323a30303a30303a30303a30303a6133120c08c8bdc1960b1080a2a18c2aa80100"
private const val LOCAL_AP = "12240a1130323a30303a30303a30303a30303a6134120c08acbec1960b1080a2a18c2aa80106"
private const val REMOVED_AP = "12240a1130323a30303a30303a30303a30303a6135120c0890bfc1960b1080a2a18c2aa80101"
private const val CONFLICTING_AP = "12240a1130323a30303a30303a30303a30303a6136120c08f4bfc1960b1080a2a18c2aa80124"
private const val PROTECTED_AP = "12240a1130323a30303a30303a30303a30303a6137120c08d8c0c1960b1080a2a18c2aa8010b"

/** Exercises the production lookup and import with synthetic responses and a temporary preview. */
internal fun SystemFilesInstrumentation.verifyAppleWlocChannel(): Bundle = runBlocking {
    val output = Bundle()
    val fixtureId = UUID.randomUUID()
    val preview = File(targetContext.cacheDir, "wloc-channel-$fixtureId.json")
    val legacyPreview = File(targetContext.cacheDir, "wloc-channel-legacy-$fixtureId.json")
    val database = Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java).build()
    val seedBssid = "02:00:00:00:00:a1"
    val neighborBssid = "02:00:00:00:00:a2"
    val zeroBssid = "02:00:00:00:00:a3"
    val localBssid = "02:00:00:00:00:a4"
    val removedBssid = "02:00:00:00:00:a5"
    val outOfBandBssid = "02:00:00:00:00:a6"
    val protectedBssid = "02:00:00:00:00:a7"
    val savedNearbyBssid = neighborBssid.uppercase()
    val latitude = 30.0
    val longitude = 113.0
    val mapPoint = CoordinateUtils.wgs84ToGcj02(latitude, longitude)
    // Two independent synthetic responses. Channel 36 is only in the conflicting response.
    val validFixture = (WLOC_FIXTURE_HEADER + SEED_AP + NEIGHBOR_AP + ZERO_CHANNEL_AP + LOCAL_AP + REMOVED_AP + PROTECTED_AP)
        .decodeHex().toByteArray()
    val conflictingFixture = (WLOC_FIXTURE_HEADER + PROTECTED_AP + CONFLICTING_AP).decodeHex().toByteArray()
    val match = AtomicBoolean()
    val mixed = AtomicBoolean()
    val bandRequests = AtomicInteger()
    val bandSelector = "f80101".decodeHex()
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val body = when {
            request.url.host == "gs-loc-cn.apple.com" && request.url.encodedPath == "/clls/wloc" -> {
                val wire = Buffer()
                checkNotNull(request.body).writeTo(wire)
                if (wire.indexOf(bandSelector) >= 0) bandRequests.incrementAndGet()
                (if (mixed.get()) conflictingFixture else validFixture).toResponseBody()
            }
            request.url.host == "api.wigle.net" && request.url.encodedPath == "/api/v2/network/search" -> {
                val records = if (match.get()) """
                    {"netid":"$neighborBssid","trilat":30.000001,"trilong":$longitude,"frequency":5180,"channel":36},
                    {"netid":"$localBssid","trilat":30.000003,"trilong":$longitude,"frequency":5180,"channel":36}
                """.trimIndent() else ""
                """{"success":true,"results":[$records]}""".toResponseBody()
            }
            else -> error("Unexpected fixture request")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(body).build()
    }.build()
    try {
        val tiles = AppleWifiTileClient(client)
        val discovery = AppleWifiDiscovery(wloc = AppleWifiWlocClient(client),
            localSeeds = { _, _ -> listOf(seedBssid) }, tileLookup = { _, _, _, _, _ -> emptyList() })
        val repository = RemoteWifiRepository(tiles, preview, WifiMetadataEnricher({ "fixture-token" }, client), discovery)
        val wifi = WifiRepository(WigleClient(WigleTransport(client)), repository, RemoteEnvironmentImporter(database))
        val dao = database.environmentDao()
        val targetId = dao.insertLocation(LocationRecord(lat = mapPoint.lat, lng = mapPoint.lng,
            selectedWifiBssid = seedBssid.uppercase()))
        val otherId = dao.insertLocation(LocationRecord(lat = mapPoint.lat + 0.01, lng = mapPoint.lng))
        dao.insertWifiDevice(WifiDevice(savedNearbyBssid, frequency = 0))
        dao.insertWifiDevice(WifiDevice(localBssid, ssid = "fixture-local", frequency = 5745))
        dao.insertWifiDevice(WifiDevice(protectedBssid, frequency = 0))
        dao.insertLocationWifi(LocationWifi(targetId, savedNearbyBssid, -47))
        dao.insertLocationWifi(LocationWifi(targetId, localBssid, -48))
        dao.insertLocationWifi(LocationWifi(targetId, protectedBssid, -49))
        dao.insertLocationWifi(LocationWifi(otherId, savedNearbyBssid, -72))
        dao.insertConnectedWifi(LocationConnectedWifi(targetId, bssid = zeroBssid.uppercase(), frequency = 5200))

        val imported = wifi.fetchAppleWifi(mapPoint.lat, mapPoint.lng)
        check(bandRequests.get() == 1) { "Expected the WLOC 2.4 GHz selector" }
        check(imported.recordId == targetId)
        check(imported.metadataStatus == RemoteWifiMetadataStatus.NO_MATCH && imported.metadataCount == 0)
        val loaded = checkNotNull(RemoteWifiRepository(tiles, preview).loadLatest())
        check(loaded.source == RemoteWifiSource.APPLE_WLOC && loaded.metadataStatus == RemoteWifiMetadataStatus.NO_MATCH)
        val points = loaded.points.associateBy { it.bssid }
        check(points.mapValues { it.value.appleWlocChannel } ==
            mapOf(seedBssid to 11, neighborBssid to 11, zeroBssid to null, localBssid to 6, removedBssid to 1, protectedBssid to 11))
        check(points.mapValues { it.value.appleWlocDerivedFrequencyMHz } ==
            mapOf(seedBssid to null, neighborBssid to 2462, zeroBssid to null, localBssid to 2437, removedBssid to 2412, protectedBssid to 2462))
        check(points.getValue(neighborBssid).effectiveFrequencyMHz == 2462)
        val saved = checkNotNull(dao.getCompleteLocationById(targetId))
        check(saved.wifis.size == 6)
        check(saved.wifis.filter { it.device.bssid in setOf(seedBssid, zeroBssid) }.all {
            it.device.ssid.isEmpty() && it.device.capabilities.isEmpty() && it.effectiveFrequencyMHz == 0
        })
        val derived = saved.wifis.single { it.device.bssid == savedNearbyBssid }
        check(derived.device.frequency == 0 && derived.effectiveFrequencyMHz == 2462)
        check(derived.locationWifi.remoteSource == null && derived.locationWifi.level == -47)
        check(saved.wifis.single { it.device.bssid == localBssid }.effectiveFrequencyMHz == 5745)
        check(dao.getWifiDevicesIgnoringCase(listOf(neighborBssid)).size == 1)
        check(checkNotNull(dao.getCompleteLocationById(otherId)).wifis.single().effectiveFrequencyMHz == 0)
        val stale = saved.wifis.single { it.device.bssid == removedBssid }
        check(stale.locationWifi.remoteSource == APPLE_WIFI_LINK_SOURCE)
        check(saved.wifis.single { it.device.bssid == protectedBssid }.effectiveFrequencyMHz == 2462)
        dao.insertLocationWifi(LocationWifi(otherId, removedBssid, -75))
        output.putString("apple_channel_survives_no_match_and_cache_reload", "PASS")
        output.putString("channel_only_import_preserves_unknown_fields", "PASS")
        output.putString("band_scoped_neighbor_frequency_reaches_room", "PASS")
        output.putString("derived_frequency_stays_on_target_location_link", "PASS")

        // Preview files from before the band-scoped request contain a channel but no frequency evidence.
        val currentPayload = preview.readText()
        val legacy = JSONObject(currentPayload).put("version", 3)
        val entries = legacy.getJSONArray("points")
        for (i in 0 until entries.length()) entries.getJSONObject(i).remove("apple_wloc_derived_frequency_mhz")
        legacyPreview.writeText(legacy.toString())
        val old = checkNotNull(RemoteWifiRepository(tiles, legacyPreview).loadLatest())
        check(old.points.single { it.bssid == neighborBssid }.appleWlocChannel == 11)
        check(old.points.all { it.appleWlocDerivedFrequencyMHz == null && it.effectiveFrequencyMHz == null })
        output.putString("preview_without_band_evidence_preserves_unknown_frequency", "PASS")
        for ((key, value) in listOf("region" to "GLOBAL", "source_type" to "APPLE_TILE")) {
            legacyPreview.writeText(JSONObject(currentPayload).put(key, value).toString())
            val unscoped = checkNotNull(RemoteWifiRepository(tiles, legacyPreview).loadLatest())
            check(unscoped.points.single { it.bssid == neighborBssid }.appleWlocChannel == 11)
            check(unscoped.points.all { it.appleWlocDerivedFrequencyMHz == null && it.effectiveFrequencyMHz == null })
        }
        output.putString("cache_rejects_unverified_region_or_source", "PASS")

        match.set(true)
        val matched = repository.query(latitude, longitude, loaded.region)
        val point = matched.points.single { it.bssid == neighborBssid }
        check(matched.metadataStatus == RemoteWifiMetadataStatus.MATCHED)
        check(point.appleWlocChannel == 11 && point.appleWlocDerivedFrequencyMHz == 2462)
        check(point.reportedChannel == 36 && point.metadata?.frequencyMHz == 5180 && point.effectiveFrequencyMHz == 5180)
        check(repository.loadLatest() == matched)
        val reused = wifi.fetchAppleWifi(mapPoint.lat, mapPoint.lng)
        val updated = checkNotNull(dao.getCompleteLocationById(reused.recordId))
        check(reused.recordId == targetId && reused.reusedPreview)
        val direct = updated.wifis.single { it.device.bssid == savedNearbyBssid }
        check(direct.device.frequency == 5180 && direct.effectiveFrequencyMHz == 5180)
        check(direct.locationWifi.appleWlocDerivedFrequencyMHz == null && direct.locationWifi.remoteSource == null)
        check(updated.wifis.single { it.device.bssid == localBssid }.device.frequency == 5745)
        output.putString("matching_wigle_fields_take_precedence", "PASS")
        output.putString("existing_local_frequency_takes_precedence", "PASS")

        mixed.set(true)
        match.set(false)
        val conflicting = repository.query(latitude, longitude, loaded.region)
        check(conflicting.points.associate { it.bssid to it.appleWlocChannel } == mapOf(protectedBssid to 11, outOfBandBssid to 36))
        check(conflicting.points.all { it.appleWlocDerivedFrequencyMHz == null })
        val refreshed = wifi.fetchAppleWifi(mapPoint.lat, mapPoint.lng)
        val replaced = checkNotNull(dao.getCompleteLocationById(targetId))
        check(refreshed.recordId == targetId && refreshed.reusedPreview)
        check(replaced.wifis.size == 6 && replaced.wifis.map { it.device.bssid.lowercase() }.toSet() ==
            setOf(seedBssid, neighborBssid, zeroBssid, localBssid, outOfBandBssid, protectedBssid))
        val manual = replaced.wifis.single { it.device.bssid == protectedBssid }
        check(manual.effectiveFrequencyMHz == 0 && manual.locationWifi.appleWlocDerivedFrequencyMHz == null)
        check(manual.locationWifi.level == -49 && manual.locationWifi.remoteSource == null)
        check(replaced.location.selectedWifiBssid == seedBssid.uppercase())
        check(replaced.connectedWifi?.bssid == zeroBssid.uppercase())
        check(checkNotNull(dao.getCompleteLocationById(otherId)).wifis.any { it.device.bssid == removedBssid })
        check(bandRequests.get() == 3)
        output.putString("conflicting_batch_clears_prior_location_derivation", "PASS")
        output.putString("replacement_prunes_only_owned_links_at_this_location", "PASS")
        verifyWifiRoomMigration()
        output.putString("v9_room_schema_migrates_preserving_wifi_relations", "PASS")
        output.putString("result", "PASS")
    } catch (error: Exception) {
        output.putString("result", "FAIL: ${error.stackTraceToString()}")
    } finally {
        database.close()
        AtomicFile(preview).delete()
        AtomicFile(legacyPreview).delete()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
    output
}
