package com.vincenthzr.locationspoofer

import android.os.Bundle
import android.util.AtomicFile
import androidx.room.Room
import com.vincenthzr.locationspoofer.data.db.AppDatabase
import com.vincenthzr.locationspoofer.data.model.*
import com.vincenthzr.locationspoofer.data.repository.*
import com.vincenthzr.locationspoofer.utils.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import java.net.UnknownHostException

internal fun SystemFilesInstrumentation.verifyRemoteCellQuery(): Bundle = runBlocking {
    val output = Bundle()
    val database = Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java).build()
    val settings = SettingsRepository(SettingsManager(targetContext))
    val cacheFile = File(targetContext.cacheDir, "remote-cell-regression.json")
    val liveCache = File(targetContext.cacheDir, "remote-cell-live-regression.json")
    val coldCache = File(targetContext.cacheDir, "remote-cell-cold-regression.json")
    val fallbackCache = File(targetContext.cacheDir, "remote-cell-fallback-regression.json")
    val fixturePrefs = "remote-cell-regression-settings"
    val fixtureContext = object : android.content.ContextWrapper(targetContext) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(fixturePrefs, mode)
    }
    val fixtureSettings = SettingsRepository(SettingsManager(fixtureContext).apply { opencellidApiToken = "fixture-key" })
    val fixture = "00000000000000000000b2011a08cc03100018c0c40720c1022a0c08909cdcb50b10e080e3922a"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val fakeClient = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(fixture.toResponseBody()).build()
    }.build()
    val offlineClient = OkHttpClient.Builder().addInterceptor { throw UnknownHostException("Offline regression") }.build()
    val seedClient = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url
        val box = checkNotNull(url.queryParameter("BBOX")).split(',').map(String::toDouble)
        val maximumArea = (box[2] - box[0]) * (box[3] - box[1]) * 111_320.0 * 111_320.0
        check(maximumArea <= 4_000_000.0) { "OpenCellID area limit exceeded" }
        check(url.queryParameter("radio") == "LTE")
        val json = """{"cells":[{"radio":"LTE","mcc":460,"mnc":0,"tac":0,"lac":321,"cellid":123456,"lat":30.6545,"lon":113.1366}]}"""
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(json.toResponseBody()).build()
    }.build()
    try {
        val seed = RemoteCellIdentity(RemoteCellRadio.LTE, 460, 0, 321, 123456)
        check(AppleCellClient(seedClient, OpenCellSearchClient(seedClient, OpenCellSearchPolicy(rings = 0)))
            .findSeed(30.6545, 113.1366, "fixture-key", RemoteCellRadio.LTE) == seed)
        output.putString("seed_bbox_and_tac_lac_fallback", "PASS")
        var nrLookups = 0
        val nrSeedClient = OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.queryParameter("radio") == "NR"); nrLookups++
            val json = """{"cells":[{"radio":"LTE","mcc":460,"mnc":0,"lac":321,"cellid":123456,"lat":30.6545,"lon":113.1366},{"radio":"NR","mcc":460,"mnc":0,"tac":0,"cellid":68719476735,"lat":30.6545,"lon":113.1366}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody()).build()
        }.build()
        try {
            val nrClient = AppleCellClient(nrSeedClient, OpenCellSearchClient(nrSeedClient, OpenCellSearchPolicy(rings = 0)))
            val nr = nrClient.findSeed(30.6545, 113.1366, "fixture-key", RemoteCellRadio.NR)
            check(nr == RemoteCellIdentity(RemoteCellRadio.NR, 460, 0, 0, 68_719_476_735L) && nrLookups == 1)
            check(nrClient.findSeed(30.6545, 113.1366, "fixture-key", RemoteCellRadio.NR, setOf(nr!!)) == null)
            check(nrLookups == 1) { "Excluded candidates caused a repeated discovery request" }
            output.putString("nr_radio_filter_and_excluded_seed", "PASS")
        } finally { nrSeedClient.connectionPool.evictAll(); nrSeedClient.dispatcher.executorService.shutdown() }
        val repository = RemoteCellRepository(AppleCellClient(fakeClient), database.environmentDao(), settings, cacheFile)
        val snapshot = repository.query(30.6545, 113.1366, RemoteWifiRegion.MAINLAND_CHINA, seed)
        check(snapshot.points.single().identity.mnc == 0)
        val restored = RemoteCellRepository(AppleCellClient(offlineClient), database.environmentDao(), settings, cacheFile)
        check(restored.loadLatest() == snapshot)
        try { restored.query(30.6545, 113.1366, RemoteWifiRegion.MAINLAND_CHINA, seed); error("Offline query succeeded") }
        catch (_: IOException) { }
        check(restored.loadLatest() == snapshot)
        output.putString("cell_cache_round_trip", "PASS")
        output.putString("cell_offline_cache_preserved", "PASS")
        try {
            repository.query(30.6545, 113.1366, RemoteWifiRegion.MAINLAND_CHINA,
                RemoteCellIdentity(RemoteCellRadio.NR, 460, 0, 0, 4_294_967_297L))
            error("LTE response accepted as NR")
        } catch (_: RemoteCellNoCoverageException) { }
        check(repository.loadLatest() == snapshot)
        output.putString("radio_mismatch_cache_preserved", "PASS")
        val fallbackDatabase = Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java).build()
        var wlocCalls = 0; var discoveryCalls = 0
        val fallbackClient = OkHttpClient.Builder().addInterceptor { chain ->
            val bytes = if (chain.request().url.host == "opencellid.org") {
                discoveryCalls++
                """{"cells":[{"radio":"LTE","mcc":460,"mnc":0,"lac":321,"cellid":654321,"lat":30.6545,"lon":113.1366}]}""".toByteArray()
            } else { wlocCalls++; if (wlocCalls == 1) ByteArray(10) else fixture }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody()).build()
        }.build()
        try {
            RemoteEnvironmentImporter(fallbackDatabase).importCells(snapshot)
            val fallback = RemoteCellRepository(AppleCellClient(fallbackClient,
                OpenCellSearchClient(fallbackClient, OpenCellSearchPolicy(rings = 0))), fallbackDatabase.environmentDao(), fixtureSettings, fallbackCache)
                .query(30.6545, 113.1366, RemoteWifiRegion.MAINLAND_CHINA, preferredRadio = RemoteCellRadio.LTE)
            check(fallback.seed.id == 654321L && wlocCalls == 2 && discoveryCalls == 1)
            output.putString("stale_local_seed_remote_recovery", "PASS")
        } finally {
            fallbackDatabase.close(); fallbackClient.connectionPool.evictAll(); fallbackClient.dispatcher.executorService.shutdown()
        }
        val live = RemoteCellRepository(AppleCellClient(), AppDatabase.getDatabase(targetContext).environmentDao(), settings, liveCache)
        output.putBoolean("seed_key_configured", settings.getOpencellidApiToken().isNotBlank())
        try {
            val online = live.query(30.6544979, 113.1366294, RemoteWifiRegion.MAINLAND_CHINA)
            output.putString("live_query", "PASS")
            output.putInt("live_records", online.points.size)
            output.putString("live_seed_radio", online.seed.radio.name)
        } catch (_: RemoteCellNoSeedException) { output.putString("live_query", "NO_NEARBY_SEED") }
        catch (_: RemoteCellNoCoverageException) { output.putString("live_query", "NO_WLOC_COVERAGE") }
        catch (error: RemoteWifiQueryException) { output.putString("live_query", "HTTP_${error.httpStatus}") }
        catch (error: IOException) { output.putString("live_query", "NETWORK_OR_RESPONSE_${error.javaClass.simpleName}") }
        try {
            val cold = RemoteCellRepository(AppleCellClient(), database.environmentDao(), settings, coldCache)
                .query(30.6544979, 113.1366294, RemoteWifiRegion.MAINLAND_CHINA)
            output.putString("coordinate_cold_start", "PASS")
            output.putString("cold_seed_radio", cold.seed.radio.name)
            output.putInt("cold_records", cold.points.size)
        } catch (_: RemoteCellNoSeedException) { output.putString("coordinate_cold_start", "NO_OPENCELLID_SEED") }
        catch (_: RemoteCellNoCoverageException) { output.putString("coordinate_cold_start", "NO_WLOC_COVERAGE") }
        catch (error: RemoteWifiQueryException) { output.putString("coordinate_cold_start", "HTTP_${error.httpStatus}") }
        catch (error: IOException) { output.putString("coordinate_cold_start", "NETWORK_OR_RESPONSE_${error.javaClass.simpleName}") }
        output.putString("result", "PASS")
    } catch (error: Throwable) { output.putString("result", "FAIL: ${error.stackTraceToString()}") }
    finally {
        database.close()
        AtomicFile(cacheFile).delete(); AtomicFile(liveCache).delete()
        AtomicFile(coldCache).delete()
        AtomicFile(fallbackCache).delete()
        targetContext.deleteSharedPreferences(fixturePrefs)
        for (client in listOf(fakeClient, offlineClient, seedClient)) {
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
    output
}
