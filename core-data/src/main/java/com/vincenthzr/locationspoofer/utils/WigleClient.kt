package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import kotlinx.coroutines.CancellationException
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

sealed interface WigleWifiLookupResult {
    data class Records(val json: String) : WigleWifiLookupResult
    data class HttpFailure(val status: Int) : WigleWifiLookupResult
    data object RequestFailed : WigleWifiLookupResult
    data object InvalidResponse : WigleWifiLookupResult
}

class WigleClient(private val transport: WigleTransport = WigleTransport()) {
    suspend fun validateToken(token: String): Boolean {
        if (token.isBlank()) return false
        return try {
            val request = Request.Builder().url("https://api.wigle.net/api/v2/profile/user")
                .header("Authorization", "Basic ${token.trim()}").header("Accept", "application/json").build()
            JSONObject(transport.fetch(request).toString(Charsets.UTF_8)).optBoolean("success", false)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { false }
    }

    /** WGS-84 input. Missing radio metadata remains unknown; RSSI is modelled from distance. */
    suspend fun fetchWifiData(lat: Double, lng: Double, token: String): String =
        (fetchWifiDataResult(lat, lng, token) as? WigleWifiLookupResult.Records)?.json ?: "[]"

    suspend fun fetchWifiDataResult(lat: Double, lng: Double, token: String): WigleWifiLookupResult {
        if (token.isBlank()) return WigleWifiLookupResult.Records("[]")
        require(lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0)
        val url = "https://api.wigle.net/api/v2/network/search?latrange1=${(lat - 0.002).coerceAtLeast(-90.0)}" +
            "&latrange2=${(lat + 0.002).coerceAtMost(90.0)}&longrange1=${(lng - 0.002).coerceAtLeast(-180.0)}" +
            "&longrange2=${(lng + 0.002).coerceAtMost(180.0)}&resultsPerPage=100"
        val request = Request.Builder().url(url).header("Authorization", "Basic ${token.trim()}")
            .header("Accept", "application/json").build()
        val body = try {
            transport.fetch(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteWifiQueryException) {
            return WigleWifiLookupResult.HttpFailure(error.httpStatus)
        } catch (_: Exception) {
            return WigleWifiLookupResult.RequestFailed
        }

        return try {
            val json = JSONObject(body.toString(Charsets.UTF_8))
            require(json.optBoolean("success", false))
            val results = json.getJSONArray("results")
            val output = JSONArray()
            val seen = mutableSetOf<String>()
            for (index in 0 until minOf(results.length(), 100)) {
                val item = results.optJSONObject(index) ?: continue
                val bssid = item.optString("netid").trim().lowercase(Locale.ROOT)
                if (!bssid.matches(Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")) || !seen.add(bssid)) continue
                val position = LatLng(item.optDouble("trilat", Double.NaN), item.optDouble("trilong", Double.NaN))
                if (position.lat !in -90.0..90.0 || position.lng !in -180.0..180.0) continue
                val distance = GeoMath.distance(LatLng(lat, lng), position)
                if (distance > 2_000.0) continue
                val frequency = item.optInt("frequency", 0).takeIf { WifiMetadataCodec.frequencyToChannel(it) != null } ?: 0
                val ssid = item.optString("ssid").takeIf { it.toByteArray(Charsets.UTF_8).size <= 32 }.orEmpty()
                output.put(JSONObject().put("bssid", bssid).put("ssid", ssid).put("frequency", frequency)
                    .put("level", RemoteSignalModel.wifi(distance)).put("capabilities", "")
                    .put("source", "WiGLE").put("distance_meters", distance))
            }
            WigleWifiLookupResult.Records(output.toString())
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { WigleWifiLookupResult.InvalidResponse }
    }
}
