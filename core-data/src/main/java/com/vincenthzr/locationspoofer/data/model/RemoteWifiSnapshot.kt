package com.vincenthzr.locationspoofer.data.model

enum class RemoteWifiRegion(val host: String) {
    MAINLAND_CHINA("gspe85-cn-ssl.ls.apple.com"),
    GLOBAL("gspe85-ssl.ls.apple.com")
}

data class RemoteWifiPoint(
    val bssid: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double,
    val metadata: RemoteWifiMetadata? = null
)

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

/** Coordinates are WGS-84. The database provides no observed RSSI, SSID or channel. */
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
