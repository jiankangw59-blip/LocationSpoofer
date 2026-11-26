package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.model.RemoteWifiMetadata
import com.vincenthzr.locationspoofer.data.model.RemoteWifiSnapshot
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

/** Exact BSSID join using WiGLE's WiFiNetwork schema. Channel without a band never implies a frequency. */
internal object WifiMetadataCodec {
    fun frequencyToChannel(frequency: Int): Int? = when {
        frequency == 2484 -> 14
        frequency in 2412..2472 && (frequency - 2407) % 5 == 0 -> (frequency - 2407) / 5
        frequency in 4910..4980 && frequency % 5 == 0 -> (frequency - 4000) / 5
        frequency in 5170..5895 && frequency % 5 == 0 -> (frequency - 5000) / 5
        frequency == 5935 -> 2
        frequency in 5955..7115 && (frequency - 5955) % 20 == 0 -> (frequency - 5950) / 5
        else -> null
    }

    fun decode(json: JSONObject, snapshot: RemoteWifiSnapshot, fetchedAt: Long): Map<String, RemoteWifiMetadata> {
        require(json.optBoolean("success", false)) { "WiGLE did not report success" }
        val results = json.optJSONArray("results") ?: return emptyMap()
        val points = snapshot.points.associateBy { it.bssid.lowercase(Locale.ROOT) }
        val found = mutableMapOf<String, RemoteWifiMetadata>()
        for (index in 0 until minOf(results.length(), 100)) {
            val item = results.optJSONObject(index) ?: continue
            val bssid = item.optString("netid").trim().lowercase(Locale.ROOT)
            val point = points[bssid] ?: continue
            val lat = item.optDouble("trilat", Double.NaN)
            val lng = item.optDouble("trilong", Double.NaN)
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) continue
            // A moved AP or inconsistent source should not overwrite the target area's metadata.
            if (GeoMath.distance(LatLng(point.latitude, point.longitude), LatLng(lat, lng)) > 500.0) continue
            val ssid = item.takeIf { it.has("ssid") && !it.isNull("ssid") }?.optString("ssid")
                ?.takeIf { it.toByteArray(Charsets.UTF_8).size <= 32 }
            var frequency = item.optInt("frequency", 0).takeIf { frequencyToChannel(it) != null }
            var channel = item.optInt("channel", 0).takeIf { it in 1..233 }
            if (frequency != null && channel != null && frequencyToChannel(frequency) != channel) {
                frequency = null; channel = null
            } else if (frequency != null && channel == null) channel = frequencyToChannel(frequency)
            val encryption = item.takeIf { it.has("encryption") && !it.isNull("encryption") }
                ?.optString("encryption")?.takeIf { it.isNotBlank() && it.length <= 64 }
            if (ssid == null && frequency == null && channel == null && encryption == null) continue
            val observedAt = try { Instant.parse(item.optString("lasttime")).toEpochMilli() } catch (_: Exception) { null }
            val metadata = RemoteWifiMetadata(ssid, frequency, channel, encryption, fetchedAt, observedAt)
            if (bssid !in found || (metadata.observedAt ?: 0L) > (found[bssid]?.observedAt ?: 0L)) found[bssid] = metadata
        }
        return found
    }
}
