package com.vincenthzr.locationspoofer

import android.os.Bundle
import android.util.AtomicFile
import com.vincenthzr.locationspoofer.data.model.*
import com.vincenthzr.locationspoofer.data.repository.RemoteWifiRepository
import com.vincenthzr.locationspoofer.utils.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/** Lookup status is durable but must never replace a valid preview or follow the user to another point. */
internal fun SystemFilesInstrumentation.verifyLookupStatus(): Bundle = runBlocking {
    val output = Bundle(); val id = UUID.randomUUID().toString()
    val preview = File(targetContext.cacheDir, "status-preview-$id.json")
    val status = File(targetContext.cacheDir, "status-report-$id.json")
    try {
        val lat = 30.0; val lon = 113.0; val region = RemoteWifiRegion.MAINLAND_CHINA
        var available = true
        val source = AppleWifiDiscovery(policy = AppleWifiSearchPolicy(maxTileRequests = 1), tokenProvider = { "fixture" },
            tileLookup = { _, _, _, _, _ -> if (available) listOf(RemoteWifiPoint("02:00:00:00:00:01", lat, lon, 0.0)) else emptyList() },
            wigleLookup = { _, _, _, _, _ -> WigleSeedResult(emptyList(), 2, 0, 0, 0, true, false) })
        val repository = RemoteWifiRepository(AppleWifiTileClient(), preview, discovery = source, statusFile = status)
        val good = repository.query(lat, lon, region)
        check(repository.loadSearchReport(lat, lon, region)?.acceptedRecords == 1)
        check(repository.loadSearchReport(lat + 0.01, lon, region) == null)
        check(repository.loadSearchReport(lat, lon, RemoteWifiRegion.GLOBAL) == null)
        available = false
        try { repository.query(lat, lon, region); error("Expected incomplete lookup") }
        catch (_: RemoteWifiSearchIncompleteException) { }
        check(repository.loadLatest() == good)
        val restarted = RemoteWifiRepository(AppleWifiTileClient(), preview, statusFile = status)
        val restored = checkNotNull(restarted.loadSearchReport(lat, lon, region))
        check(restored.outcome == "INCOMPLETE" && restored.acceptedRecords == 0)
        check(restored.toJson().getString("wifi_coverage") == "UNKNOWN")
        check(!status.readText().contains("fixture"))
        output.putString("success_and_incomplete_status_survive_restart", "PASS")
        output.putString("status_isolated_by_point_and_region", "PASS")
        output.putString("failed_lookup_preserves_valid_preview", "PASS")
        output.putString("incomplete_is_not_a_blind_spot_grade", "PASS")
        output.putString("result", "PASS")
    } catch (error: Exception) { output.putString("result", "FAIL: ${error.stackTraceToString()}") }
    finally { AtomicFile(preview).delete(); AtomicFile(status).delete() }
    output
}
