package com.vincenthzr.locationspoofer

import android.os.Bundle
import com.vincenthzr.locationspoofer.data.repository.RemoteWifiRepository
import com.vincenthzr.locationspoofer.data.repository.RemoteCellRepository
import kotlinx.coroutines.*
import com.vincenthzr.locationspoofer.utils.XposedModuleStatus
import org.koin.core.context.GlobalContext

/** Refresh previews for their existing target only; do not import or activate simulation. */
internal fun SystemFilesInstrumentation.refreshRemotePreviews(): Bundle = runBlocking {
    val output = Bundle()
    try {
        withTimeout(20_000) { while (XposedModuleStatus.mService == null) delay(100) }
        val koin = GlobalContext.get()
        val wifi = koin.get<RemoteWifiRepository>()
        val oldWifi = checkNotNull(wifi.loadLatest())
        val refreshed = wifi.query(oldWifi.latitude, oldWifi.longitude, oldWifi.region)
        output.putInt("wifi_records", refreshed.points.size)
        output.putString("metadata_status", refreshed.metadataStatus.name)
        output.putInt("metadata_matched", refreshed.points.count { it.metadata != null })
        val cells = koin.get<RemoteCellRepository>()
        val oldCells = checkNotNull(cells.loadLatest())
        val refreshedCells = cells.query(oldCells.latitude, oldCells.longitude, oldCells.region,
            preferredRadio = oldCells.seed.radio)
        output.putInt("cell_records", refreshedCells.points.size)
        output.putString("cell_radio", refreshedCells.seed.radio.name)
        output.putString("result", "PASS")
    } catch (error: Exception) { output.putString("result", "FAIL: ${error.javaClass.simpleName}") }
    output
}
