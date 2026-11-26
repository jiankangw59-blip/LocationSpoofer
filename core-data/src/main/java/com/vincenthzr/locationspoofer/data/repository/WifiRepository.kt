package com.vincenthzr.locationspoofer.data.repository

import com.vincenthzr.locationspoofer.utils.WigleClient
import com.vincenthzr.locationspoofer.utils.CoordinateUtils
import com.vincenthzr.locationspoofer.utils.GeoMath
import com.vincenthzr.locationspoofer.data.model.RemoteWifiRegion

class WifiRepository(
    private val wigleClient: WigleClient,
    private val remote: RemoteWifiRepository,
    private val importer: RemoteEnvironmentImporter
) {

    suspend fun fetchAppleWifi(gcjLatitude: Double, gcjLongitude: Double): Long {
        val position = CoordinateUtils.gcj02ToWgs84(gcjLatitude, gcjLongitude)
        val cached = try { remote.loadLatest() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { null }
        val validCache = cached?.takeIf {
            System.currentTimeMillis() - it.fetchedAt in 0..86_400_000L &&
                GeoMath.distance(position, CoordinateUtils.LatLng(it.latitude, it.longitude)) <= 2.0
        }
        val region = if (position.lat in 18.0..54.0 && position.lng in 73.0..135.0) RemoteWifiRegion.MAINLAND_CHINA else RemoteWifiRegion.GLOBAL
        val snapshot = validCache ?: remote.query(position.lat, position.lng, region)
        return importer.importWifi(snapshot)
    }

    suspend fun validateToken(token: String): Boolean = wigleClient.validateToken(token)

    suspend fun fetchWifiData(lat: Double, lng: Double, token: String): String =
        wigleClient.fetchWifiData(lat, lng, token)
}
