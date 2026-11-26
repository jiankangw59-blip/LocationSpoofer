package com.vincenthzr.locationspoofer

import android.os.Bundle
import android.util.AtomicFile
import androidx.room.Room
import com.vincenthzr.locationspoofer.data.db.*
import com.vincenthzr.locationspoofer.data.model.*
import com.vincenthzr.locationspoofer.data.repository.*
import com.vincenthzr.locationspoofer.utils.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.io.File
import java.net.UnknownHostException
import java.net.InetSocketAddress
import java.net.Proxy

/** Deterministic checks use a protocol fixture. The final live lookup uses the saved Apple preview and WiGLE key. */
internal fun SystemFilesInstrumentation.verifyRemoteWifiMetadata(): Bundle = runBlocking {
    val output = Bundle()
    val cacheFile = File(targetContext.cacheDir, "wifi-metadata-regression.json")
    val clients = mutableListOf<OkHttpClient>()
    val database = Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java).build()
    try {
        val tileBytes = "1a1412122881a681f005320a0dfee64412154e197043".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val tileClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(tileBytes.toResponseBody()).build()
        }.build().also(clients::add)
        val base = AppleWifiTileClient(tileClient).query(30.6522, 113.1427, RemoteWifiRegion.MAINLAND_CHINA)
        val first = base.points.first()
        val now = System.currentTimeMillis()
        var requests = 0
        var responseStatus = 200
        val metadataClient = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            check(chain.request().url.encodedPath == "/api/v2/network/search")
            check(chain.request().url.queryParameter("resultsPerPage") == "100")
            check(chain.request().url.queryParameter("netid") == null)
            val json = JSONObject().put("success", true).put("results", JSONArray().put(JSONObject()
                .put("netid", first.bssid.uppercase()).put("trilat", first.latitude).put("trilong", first.longitude)
                .put("ssid", "fixture-observed-name").put("frequency", 5180).put("channel", 36).put("encryption", "WPA2")))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(responseStatus).message("fixture")
                .body(json.toString().toResponseBody()).build()
        }.build().also(clients::add)
        val legacy = JSONObject().put("version", 1).put("coordinate_system", "WGS84")
            .put("latitude", base.latitude).put("longitude", base.longitude).put("tile_key", base.tileKey)
            .put("region", base.region.name).put("fetched_at", base.fetchedAt)
            .put("points", JSONArray().put(JSONObject().put("bssid", first.bssid).put("latitude", first.latitude).put("longitude", first.longitude)))
        cacheFile.writeText(legacy.toString())
        val enricher = WifiMetadataEnricher({ "fixture-token" }, metadataClient, { now })
        val repository = RemoteWifiRepository(AppleWifiTileClient(tileClient), cacheFile, enricher)
        check(repository.loadLatest()?.points?.single()?.metadata == null)
        val enriched = repository.query(base.latitude, base.longitude, base.region)
        check(requests == 1)
        check(enriched.points.size == base.points.size && enriched.points.count { it.metadata != null } == 1)
        check(enriched.metadataStatus == RemoteWifiMetadataStatus.MATCHED)
        check(repository.loadLatest() == enriched)
        check(!cacheFile.readText().contains("fixture-token"))
        val gcj = CoordinateUtils.wgs84ToGcj02(base.latitude, base.longitude)
        val id = database.environmentDao().insertLocation(LocationRecord(lat = gcj.lat, lng = gcj.lng))
        database.environmentDao().insertWifiDevice(WifiDevice(first.bssid, frequency = 0))
        database.environmentDao().insertLocationWifi(LocationWifi(id, first.bssid, -42))
        RemoteEnvironmentImporter(database).importWifi(enriched)
        val imported = checkNotNull(database.environmentDao().getCompleteLocationById(id)).wifis.single { it.device.bssid == first.bssid }
        check(imported.device.ssid == "fixture-observed-name" && imported.device.frequency == 5180)
        check(imported.locationWifi.level == -42)
        val offline = OkHttpClient.Builder().addInterceptor { throw UnknownHostException("offline fixture") }.build().also(clients::add)
        val preserved = WifiMetadataEnricher({ "fixture-token" }, offline, { now + 1_000 }).enrich(base, enriched)
        check(preserved.metadataStatus == RemoteWifiMetadataStatus.UNAVAILABLE && preserved.points.first().metadata == enriched.points.first().metadata)
        val expired = WifiMetadataEnricher({ "fixture-token" }, offline, { now + 86_400_001 }).enrich(base, enriched)
        check(expired.points.all { it.metadata == null })
        responseStatus = 429
        var credential = "fixture-token"
        val limited = WifiMetadataEnricher({ credential }, metadataClient, { now })
        val before = requests
        check(limited.enrich(base).metadataHttpStatus == 429)
        check(limited.enrich(base).metadataHttpStatus == 429)
        check(requests == before + 1)
        credential = "changed-fixture-token"
        limited.enrich(base)
        check(requests == before + 2)
        output.putString("single_area_request_exact_join", "PASS")
        output.putString("v1_cache_upgrade_and_metadata_round_trip", "PASS")
        output.putString("metadata_import_preserves_signal", "PASS")
        output.putString("offline_metadata_and_expiry", "PASS")
        output.putString("rate_limit_backoff_and_key_change", "PASS")
        var leased = false
        var acquisitions = 0
        var releases = 0
        var proxyFails = false
        val proxyClient = OkHttpClient.Builder().addInterceptor { chain ->
            if (!leased || proxyFails) throw UnknownHostException("transport fixture")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"success":true,"results":[]}""".toResponseBody()).build()
        }.build().also(clients::add)
        fun fixtureLease() = object : RequestProxyLease {
            override val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 18765))
            override suspend fun release() { releases++; leased = false }
        }
        val proxyProvider = RequestProxyLeaseProvider { acquisitions++; leased = true; fixtureLease() }
        check(WifiMetadataEnricher({ "fixture-token" }, proxyClient, { now }, proxyProvider)
            .enrich(base).metadataStatus == RemoteWifiMetadataStatus.NO_MATCH)
        check(acquisitions == 1 && releases == 1 && !leased)
        proxyFails = true
        check(WifiMetadataEnricher({ "fixture-token" }, proxyClient, { now }, proxyProvider)
            .enrich(base).metadataStatus == RemoteWifiMetadataStatus.UNAVAILABLE)
        check(acquisitions == 2 && releases == 2 && !leased)
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val finishAcquire = CompletableDeferred<Unit>()
            val cancellableProvider = RequestProxyLeaseProvider {
                acquisitions++; leased = true; entered.complete(Unit)
                finishAcquire.await()
                fixtureLease()
            }
            val pending = async {
                WifiMetadataEnricher({ "fixture-token" }, proxyClient, { now }, cancellableProvider).enrich(base)
            }
            entered.await(); pending.cancel(); finishAcquire.complete(Unit); pending.join()
        }
        check(acquisitions == 3 && releases == 3 && !leased)
        output.putString("proxy_release_on_success_failure_and_acquisition_cancellation", "PASS")
        val legacyTransportClient = OkHttpClient.Builder().addInterceptor { chain ->
            val payload = if (chain.request().url.encodedPath.endsWith("/profile/user")) """{"success":true}"""
                else JSONObject().put("success", true).put("results", JSONArray().put(JSONObject()
                    .put("netid", first.bssid).put("trilat", first.latitude).put("trilong", first.longitude)
                    .put("ssid", "source-name").put("frequency", 5180))).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(payload.toResponseBody()).build()
        }.build().also(clients::add)
        val legacyClient = WigleClient(WigleTransport(legacyTransportClient))
        check(legacyClient.validateToken("fixture-token"))
        val legacyRecords = JSONArray(legacyClient.fetchWifiData(base.latitude, base.longitude, "fixture-token"))
        check(legacyRecords.length() == 1 && legacyRecords.getJSONObject(0).getInt("frequency") == 5180)
        check(legacyRecords.getJSONObject(0).getString("capabilities").isEmpty())
        val wrongOriginRejected = try {
            WigleTransport(legacyTransportClient).fetch(Request.Builder().url("https://example.com/")
                .header("Authorization", "Basic fixture-token").build()); false
        } catch (_: IllegalArgumentException) { true }
        check(wrongOriginRejected)
        output.putString("legacy_wigle_profile_and_wifi_preserve_metadata", "PASS")
        output.putString("wigle_transport_rejects_foreign_origin", "PASS")
        val settings = GlobalContext.get().get<SettingsRepository>()
        val liveProxy = GlobalContext.get().get<FeedMeProxyLeaseProvider>()
        val proxyBefore = liveProxy.status()
        output.putBoolean("live_proxy_before_running", proxyBefore.optBoolean("running"))
        output.putInt("live_proxy_before_leases", proxyBefore.optInt("leases", -1))
        val diagnosticClient = OkHttpClient.Builder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS).callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: java.io.IOException) {
                    output.putString("direct_attempt_failure_class", ioe.javaClass.simpleName)
                }
            }).addInterceptor { chain ->
                chain.proceed(chain.request()).also { response ->
                    output.putInt("live_http_observed_status", response.code)
                    output.putString("live_response_content_type", response.header("Content-Type").orEmpty())
                    val body = response.peekBody(2_097_152).string()
                    runCatching { JSONObject(body) }.getOrNull()?.let { json ->
                        output.putInt("live_area_result_count", json.optJSONArray("results")?.length() ?: 0)
                        output.putLong("live_area_total_results", json.optLong("totalResults", -1))
                        output.putBoolean("live_area_has_more", !json.isNull("searchAfter") && json.optString("searchAfter").isNotBlank())
                    }
                    val success = Regex("\\\"success\\\"\\s*:\\s*(true|false)").find(body)?.groupValues?.get(1)
                    output.putString("live_response_success", success ?: "not-json-or-missing")
                    val lower = body.lowercase()
                    output.putString("live_rejection_category", when {
                        success == "true" -> "none"
                        "limit" in lower || "quota" in lower -> "quota"
                        "credential" in lower || "authentication" in lower || "api key" in lower || "not logged" in lower -> "authentication"
                        else -> "other"
                    })
                }
            }.build().also(clients::add)
        val liveBase = checkNotNull(GlobalContext.get().get<RemoteWifiRepository>().loadLatest()) { "Live lookup needs a previously fetched Apple preview" }
        val live = WifiMetadataEnricher(settings::getWigleApiToken, diagnosticClient, proxyProvider = liveProxy).enrich(liveBase)
        val proxyAfter = liveProxy.status()
        output.putBoolean("live_proxy_after_running", proxyAfter.optBoolean("running"))
        output.putInt("live_proxy_after_leases", proxyAfter.optInt("leases", -1))
        check(proxyBefore.optInt("leases", -1) == proxyAfter.optInt("leases", -2)) { "Proxy leases were not restored" }
        output.putString("live_metadata_status", live.metadataStatus.name)
        output.putInt("live_metadata_http_status", live.metadataHttpStatus ?: 0)
        output.putInt("live_apple_records", live.points.size)
        output.putInt("live_ssid_matches", live.points.count { it.metadata?.ssid != null })
        output.putInt("live_frequency_matches", live.points.count { it.metadata?.frequencyMHz != null })
        output.putBoolean("live_profile_auth_valid", GlobalContext.get().get<WigleClient>().validateToken(settings.getWigleApiToken()))
        output.putString("result", "PASS")
    } catch (error: Throwable) { output.putString("result", "FAIL: ${error.stackTraceToString()}") }
    finally {
        database.close(); AtomicFile(cacheFile).delete()
        clients.forEach { it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown() }
    }
    output
}
