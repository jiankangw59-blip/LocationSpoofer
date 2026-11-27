package com.vincenthzr.locationspoofer

import android.os.Bundle
import android.util.AtomicFile
import androidx.room.Room
import com.vincenthzr.locationspoofer.data.db.AppDatabase
import com.vincenthzr.locationspoofer.data.model.RemoteWifiMetadataStatus
import com.vincenthzr.locationspoofer.data.model.RemoteWifiSource
import com.vincenthzr.locationspoofer.data.repository.RemoteEnvironmentImporter
import com.vincenthzr.locationspoofer.data.repository.RemoteWifiRepository
import com.vincenthzr.locationspoofer.data.repository.WifiRepository
import com.vincenthzr.locationspoofer.utils.AppleWifiDiscovery
import com.vincenthzr.locationspoofer.utils.AppleWifiTileClient
import com.vincenthzr.locationspoofer.utils.AppleWifiWlocClient
import com.vincenthzr.locationspoofer.utils.WifiMetadataEnricher
import com.vincenthzr.locationspoofer.utils.WigleClient
import com.vincenthzr.locationspoofer.utils.WigleTransport
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.util.UUID

/** Exercises the production lookup and import with synthetic responses and a temporary preview. */
internal fun SystemFilesInstrumentation.verifyAppleWlocChannel(): Bundle = runBlocking {
    val output = Bundle()
    val preview = File(targetContext.cacheDir, "wloc-channel-${UUID.randomUUID()}.json")
    val database = Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java).build()
    val firstBssid = "02:00:00:00:00:01"
    val latitude = 0.25
    val longitude = 0.5
    // Synthetic WLOC header and two locally administered BSSIDs with channel 11 and 0.
    val fixture = ("00000000000000000000" +
        "12220a1130323a30303a30303a30303a30303a3031120a08c0f0f50b1080e1eb17a8010b" +
        "12220a1130323a30303a30303a30303a30303a3032120a08a4f1f50b1080e1eb17a80100"
    ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    var match = false
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val body = when {
            request.url.host == "gs-loc.apple.com" && request.url.encodedPath == "/clls/wloc" ->
                fixture.toResponseBody()
            request.url.host == "api.wigle.net" && request.url.encodedPath == "/api/v2/network/search" -> {
                val record = if (match) """{"netid":"$firstBssid","trilat":$latitude,"trilong":$longitude,"frequency":5180,"channel":36}""" else ""
                """{"success":true,"results":[$record]}""".toResponseBody()
            }
            else -> error("Unexpected fixture request")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(body).build()
    }.build()
    try {
        val tiles = AppleWifiTileClient(client)
        val discovery = AppleWifiDiscovery(wloc = AppleWifiWlocClient(client),
            localSeeds = { _, _ -> listOf(firstBssid) }, tileLookup = { _, _, _, _, _ -> emptyList() })
        val repository = RemoteWifiRepository(tiles, preview, WifiMetadataEnricher({ "fixture-token" }, client), discovery)
        val wifi = WifiRepository(WigleClient(WigleTransport(client)), repository, RemoteEnvironmentImporter(database))
        val imported = wifi.fetchAppleWifi(latitude, longitude)
        check(imported.metadataStatus == RemoteWifiMetadataStatus.NO_MATCH && imported.metadataCount == 0)
        val loaded = checkNotNull(RemoteWifiRepository(tiles, preview).loadLatest())
        check(loaded.source == RemoteWifiSource.APPLE_WLOC && loaded.metadataStatus == RemoteWifiMetadataStatus.NO_MATCH)
        check(loaded.points.map { it.appleWlocChannel } == listOf(11, null)) { "WLOC channels lost after NO_MATCH and cache reload" }
        check(loaded.points.map { it.reportedChannel } == listOf(11, null))
        val saved = checkNotNull(database.environmentDao().getCompleteLocationById(imported.recordId))
        check(saved.wifis.size == 2 && saved.wifis.all {
            it.device.frequency == 0 && it.device.ssid.isEmpty() && it.device.capabilities.isEmpty()
        }) { "Channel-only lookup must preserve unknown import fields" }
        output.putString("apple_channel_survives_no_match_and_cache_reload", "PASS")
        output.putString("channel_only_import_preserves_unknown_fields", "PASS")

        match = true
        val matched = repository.query(latitude, longitude, loaded.region)
        val point = matched.points.first()
        check(matched.metadataStatus == RemoteWifiMetadataStatus.MATCHED)
        check(point.appleWlocChannel == 11 && point.reportedChannel == 36 && point.metadata?.frequencyMHz == 5180)
        check(repository.loadLatest() == matched)
        val reused = wifi.fetchAppleWifi(latitude, longitude)
        val updated = checkNotNull(database.environmentDao().getCompleteLocationById(reused.recordId))
        check(reused.reusedPreview && updated.wifis.single { it.device.bssid == firstBssid }.device.frequency == 5180)
        output.putString("matching_wigle_fields_take_precedence", "PASS")
        output.putString("result", "PASS")
    } catch (error: Exception) {
        output.putString("result", "FAIL: ${error.stackTraceToString()}")
    } finally {
        database.close()
        AtomicFile(preview).delete()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
    output
}
