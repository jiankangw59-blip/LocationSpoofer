package com.vincenthzr.locationspoofer.data.repository

import com.vincenthzr.locationspoofer.data.model.RemoteWifiMetadataStatus
import com.vincenthzr.locationspoofer.utils.WigleClient
import com.vincenthzr.locationspoofer.utils.WigleWifiLookupResult
import com.vincenthzr.locationspoofer.utils.CoordinateUtils
import com.vincenthzr.locationspoofer.utils.GeoMath
import com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion

class WifiRepository(
    private val wigleClient: WigleClient,
    private val remote: RemoteWifiRepository,
    private val importer: RemoteEnvironmentImporter
) {

    data class AppleWifiImportResult(
        val recordId: Long,
        val metadataStatus: RemoteWifiMetadataStatus,
        val metadataHttpStatus: Int?,
        val reusedPreview: Boolean,
        val accessPointCount: Int,
        val metadataCount: Int,
        val ssidCount: Int,
        val frequencyCount: Int
    )

    suspend fun fetchAppleWifi(
        gcjLatitude: Double,
        gcjLongitude: Double,
        onRecordsFound: ((Int) -> Unit)? = null
    ): AppleWifiImportResult {
        val position = CoordinateUtils.gcj02ToWgs84(gcjLatitude, gcjLongitude)
        val cached = try { remote.loadLatest() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { null }
        val validCache = cached?.takeIf {
            System.currentTimeMillis() - it.fetchedAt in 0..86_400_000L &&
                GeoMath.distance(position, CoordinateUtils.LatLng(it.latitude, it.longitude)) <= 2.0
        }
        val region = if (position.lat in 18.0..54.0 && position.lng in 73.0..135.0) RemoteWifiRegion.MAINLAND_CHINA else RemoteWifiRegion.GLOBAL
        val snapshot = validCache ?: remote.query(position.lat, position.lng, region, onRecordsFound)
        return AppleWifiImportResult(
            recordId = importer.importWifi(snapshot),
            metadataStatus = snapshot.metadataStatus,
            metadataHttpStatus = snapshot.metadataHttpStatus,
            reusedPreview = validCache != null,
            accessPointCount = snapshot.points.size,
            metadataCount = snapshot.points.count { it.metadata != null },
            ssidCount = snapshot.points.count { !it.metadata?.ssid.isNullOrBlank() },
            frequencyCount = snapshot.points.count { it.metadata?.frequencyMHz != null }
        )
    }

    suspend fun validateToken(token: String): Boolean = wigleClient.validateToken(token)

    suspend fun fetchWifiData(lat: Double, lng: Double, token: String): String =
        wigleClient.fetchWifiData(lat, lng, token)

    suspend fun fetchWifiDataResult(lat: Double, lng: Double, token: String): WigleWifiLookupResult =
        wigleClient.fetchWifiDataResult(lat, lng, token)
}
