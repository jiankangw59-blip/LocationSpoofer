package com.vincenthzr.locationspoofer.data.model

enum class RemoteCellRadio { LTE, NR }

data class RemoteCellIdentity(val radio: RemoteCellRadio, val mcc: Int, val mnc: Int, val tac: Int, val id: Long) {
    fun validate() {
        require(mcc in 1..999 && mnc in 0..999)
        require(tac in 0..if (radio == RemoteCellRadio.LTE) 65_535 else 16_777_215)
        require(id in 1..if (radio == RemoteCellRadio.LTE) 268_435_455L else 68_719_476_735L)
    }
}

data class RemoteCellPoint(val identity: RemoteCellIdentity, val latitude: Double, val longitude: Double, val distanceMeters: Double)

data class RemoteCellSnapshot(
    val latitude: Double, val longitude: Double, val region: RemoteWifiRegion,
    val seed: RemoteCellIdentity, val fetchedAt: Long, val points: List<RemoteCellPoint>
)
