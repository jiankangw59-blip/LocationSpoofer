package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.model.RemoteWifiPoint
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import java.util.Locale
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/** Wire fields documented by acheong08/apple-corelocation-experiments, pb/wifiTiles.proto. */
internal object AppleWifiTileCodec {
    const val MAX_LATITUDE = 85.05112878
    const val RADIUS_METERS = 2_000.0
    private const val LEVEL = 13

    fun tileKey(latitude: Double, longitude: Double): Long {
        require(latitude.isFinite() && latitude in -MAX_LATITUDE..MAX_LATITUDE)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        val size = 1 shl LEVEL
        val normalizedLongitude = if (longitude == 180.0) -180.0 else longitude
        var column = floor((normalizedLongitude + 180.0) / 360.0 * size).toInt()
        val sinLatitude = sin(Math.toRadians(latitude))
        var row = floor(
            (0.5 - ln((1.0 + sinLatitude) / (1.0 - sinLatitude)) / (4.0 * Math.PI)) * size
        ).toInt().coerceIn(0, size - 1)
        return encodeKey(column, row)
    }

    /** Center first, then at most eight adjacent tiles; wraps longitude, never the poles. */
    fun searchTileKeys(latitude: Double, longitude: Double): List<Long> {
        val center = tileKey(latitude, longitude)
        var column = 0; var row = 0
        for (bit in 0 until LEVEL) {
            column = column or (((center ushr (2 * bit)) and 1L).toInt() shl bit)
            row = row or (((center ushr (2 * bit + 1)) and 1L).toInt() shl bit)
        }
        val size = 1 shl LEVEL
        val offsets = listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, 1 to -1, -1 to 1, 1 to 1)
        return offsets.mapNotNull { (dx, dy) ->
            val y = row + dy
            if (y !in 0 until size) null else encodeKey((column + dx + size) % size, y)
        }.distinct()
    }

    private fun encodeKey(inputColumn: Int, inputRow: Int): Long {
        var column = inputColumn; var row = inputRow
        var key = 1L shl (2 * LEVEL)
        for (index in 0 until LEVEL) {
            key = key or ((column and 1).toLong() shl (2 * index))
            key = key or ((row and 1).toLong() shl (2 * index + 1))
            column = column ushr 1
            row = row ushr 1
        }
        return key
    }

    @JvmOverloads
    fun decode(bytes: ByteArray, latitude: Double, longitude: Double,
        maxDistanceMeters: Double = RADIUS_METERS, limit: Int = 100): List<RemoteWifiPoint> {
        require(maxDistanceMeters.isFinite() && maxDistanceMeters in 0.0..50_000.0 && limit in 1..1000)
        val target = LatLng(latitude, longitude)
        val points = ArrayList<RemoteWifiPoint>()
        for (region in Reader(bytes).messages(3)) {
            for (device in Reader(region).messages(2)) {
                decodeDevice(device, target, maxDistanceMeters)?.let { points.add(it) }
            }
        }
        return points.distinctBy { it.bssid }.sortedBy { it.distanceMeters }.take(limit)
    }

    private fun decodeDevice(bytes: ByteArray, target: LatLng, maxDistanceMeters: Double): RemoteWifiPoint? {
        val reader = Reader(bytes)
        var mac: Long? = null
        var position: LatLng? = null
        while (reader.hasRemaining()) {
            val tag = reader.tag()
            when (tag) {
                40 -> mac = reader.varint()
                50 -> position = decodePosition(reader.bytes())
                else -> reader.skip(tag and 7)
            }
        }
        val address = mac ?: return null
        val point = position ?: return null
        if (address <= 0 || address >= 0xFFFFFFFFFFFFL || (address ushr 40 and 1L) != 0L) return null
        val distance = GeoMath.distance(target, point)
        if (!distance.isFinite() || distance > maxDistanceMeters) return null
        val bssid = (5 downTo 0).joinToString(":") { index ->
            String.format(Locale.US, "%02x", address ushr (8 * index) and 255L)
        }
        return RemoteWifiPoint(bssid, point.lat, point.lng, distance)
    }

    private fun decodePosition(bytes: ByteArray): LatLng? {
        val reader = Reader(bytes)
        var latitude: Double? = null
        var longitude: Double? = null
        while (reader.hasRemaining()) {
            when (val tag = reader.tag()) {
                13 -> latitude = reader.fixed32() * 1e-7
                21 -> longitude = reader.fixed32() * 1e-7
                else -> reader.skip(tag and 7)
            }
        }
        val lat = latitude ?: return null
        val lng = longitude ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return LatLng(lat, lng)
    }

    private class Reader(private val data: ByteArray) {
        private var offset = 0

        fun hasRemaining(): Boolean = offset < data.size

        fun tag(): Int {
            val tag = varint()
            require(tag in 8L..0xFFFFFFFFL) { "Invalid protobuf tag" }
            return tag.toInt()
        }

        fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                require(hasRemaining()) { "Truncated protobuf varint" }
                val byte = data[offset++].toInt() and 255
                require(shift != 63 || byte <= 1) { "Protobuf varint overflow" }
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
            }
            throw IllegalArgumentException("Invalid protobuf varint")
        }

        fun fixed32(): Int {
            require(data.size - offset >= 4) { "Truncated protobuf fixed32" }
            var value = 0
            for (index in 0 until 4) {
                value = value or ((data[offset++].toInt() and 255) shl (8 * index))
            }
            return value
        }

        fun bytes(): ByteArray {
            val length = varint()
            require(length in 0L..(data.size - offset).toLong()) { "Invalid protobuf length" }
            val end = offset + length.toInt()
            return data.copyOfRange(offset, end).also { offset = end }
        }

        fun messages(field: Int): List<ByteArray> {
            val messages = ArrayList<ByteArray>()
            while (hasRemaining()) {
                val tag = tag()
                if (tag == (field shl 3 or 2)) messages.add(bytes()) else skip(tag and 7)
            }
            return messages
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> advance(8)
                2 -> {
                    val length = varint()
                    require(length in 0L..(data.size - offset).toLong()) { "Invalid protobuf length" }
                    advance(length.toInt())
                }
                5 -> advance(4)
                else -> throw IllegalArgumentException("Unsupported protobuf wire type")
            }
        }

        private fun advance(length: Int) {
            require(length <= data.size - offset) { "Truncated protobuf field" }
            offset += length
        }
    }
}
