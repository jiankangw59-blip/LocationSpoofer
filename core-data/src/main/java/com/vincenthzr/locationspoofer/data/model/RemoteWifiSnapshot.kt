package com.vincenthzr.locationspoofer.data.model

internal const val MAX_REPORTED_WIFI_CHANNEL = 233

internal fun appleWloc24GhzFrequencyMHz(channel: Int): Int? = when (channel) {
    in 1..13 -> 2407 + channel * 5
    14 -> 2484
    else -> null
}

enum class RemoteWifiRegion(val host: String) {
    MAINLAND_CHINA("gspe85-cn-ssl.ls.apple.com"),
    GLOBAL("gspe85-ssl.ls.apple.com")
}

data class RemoteWifiPoint(
    val bssid: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double,
    val metadata: RemoteWifiMetadata? = null,
    val appleWlocChannel: Int? = null,
    val appleWlocDerivedFrequencyMHz: Int? = null
) {
    /** Keep a WiGLE frequency and its channel together. Apple WLOC does not supply frequency. */
    val reportedChannel: Int?
        get() = metadata?.channel ?: appleWlocChannel.takeIf { metadata?.frequencyMHz == null }

    /** Explicit WiGLE data takes precedence over the separately derived Apple value. */
    val effectiveFrequencyMHz: Int?
        get() = metadata?.frequencyMHz ?: appleWlocDerivedFrequencyMHz?.takeIf {
            metadata?.channel == null && it == appleWlocChannel?.let(::appleWloc24GhzFrequencyMHz)
        }
}

enum class RemoteWifiMetadataStatus { NOT_REQUESTED, NOT_CONFIGURED, MATCHED, NO_MATCH, UNAVAILABLE }
enum class RemoteWifiSource { APPLE_TILE, APPLE_WLOC }

/** Fields actually returned by WiGLE, independent from Apple coordinates and modeled RSSI. */
data class RemoteWifiMetadata(
    val ssid: String? = null,
    val frequencyMHz: Int? = null,
    val channel: Int? = null,
    val encryption: String? = null,
    val fetchedAt: Long,
    val observedAt: Long? = null
)

/** Coordinates are WGS-84. No observed RSSI is available. */
data class RemoteWifiSnapshot(
    val latitude: Double,
    val longitude: Double,
    val region: RemoteWifiRegion,
    val tileKey: Long,
    val fetchedAt: Long,
    val points: List<RemoteWifiPoint>,
    val metadataStatus: RemoteWifiMetadataStatus = RemoteWifiMetadataStatus.NOT_REQUESTED,
    val metadataHttpStatus: Int? = null,
    val source: RemoteWifiSource = RemoteWifiSource.APPLE_TILE
)
