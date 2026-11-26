package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.model.*
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import java.io.ByteArrayOutputStream

/** Protocol references: apple-corelocation-experiments/pb/BSSIDApple.proto and wavedigger/lib/protobuf/schema.ts. */
internal object AppleCellCodec {
    fun request(seed: RemoteCellIdentity): ByteArray {
        seed.validate()
        val cell = Writer().apply { number(1, seed.mcc.toLong()); number(2, seed.mnc.toLong()); number(3, seed.id); number(4, seed.tac.toLong()) }.bytes()
        val device = Writer().apply { message(1, "iPhone OS17.5/21F79".toByteArray()); message(2, "iPhone12,1".toByteArray()) }.bytes()
        val payload = Writer().apply {
            number(3, 0); number(4, 1) // all matching cells, Wi-Fi disabled (sint32 -1)
            message(if (seed.radio == RemoteCellRadio.LTE) 25 else 29, cell)
            message(33, device)
        }.bytes()
        require(payload.size < 256)
        val prefix = "0001000a656e2d3030315f3030310013636f6d2e6170706c652e6c6f636174696f6e64000c31372e352e312e323146393000000001000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return prefix + byteArrayOf(payload.size.toByte()) + payload
    }

    fun decode(bytes: ByteArray, latitude: Double, longitude: Double, seed: RemoteCellIdentity? = null,
        maxDistanceMeters: Double = 20_000.0): List<RemoteCellPoint> {
        require(bytes.size in 10..2_097_152)
        require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
        require(maxDistanceMeters in 0.0..20_000.0)
        val reader = Reader(bytes.copyOfRange(10, bytes.size))
        val found = linkedMapOf<RemoteCellIdentity, RemoteCellPoint>()
        while (reader.more()) {
            val tag = reader.tag()
            if (tag == (22 shl 3 or 2) || tag == (24 shl 3 or 2)) {
                val radio = if (tag ushr 3 == 22) RemoteCellRadio.LTE else RemoteCellRadio.NR
                val cell = decodeCell(reader.message(), radio, latitude, longitude, maxDistanceMeters) ?: continue
                val identity = cell.identity
                if (seed != null && (identity.radio != seed.radio || identity.mcc != seed.mcc || identity.mnc != seed.mnc)) continue
                val previous = found[identity]
                if (previous == null || cell.distanceMeters < previous.distanceMeters) found[identity] = cell
            } else reader.skip(tag and 7)
        }
        return found.values.sortedBy { it.distanceMeters }.take(100)
    }

    private fun decodeCell(reader: Reader, radio: RemoteCellRadio, lat: Double, lng: Double,
        maxDistanceMeters: Double): RemoteCellPoint? {
        var mcc = 0L; var mnc = 0L; var id = 0L; var tac = 0L
        var position: Pair<Double, Double>? = null
        while (reader.more()) {
            when (val tag = reader.tag()) {
                8 -> mcc = reader.number()
                16 -> mnc = reader.number()
                24 -> id = reader.number()
                32 -> tac = reader.number()
                42 -> position = decodePosition(reader.message())
                else -> reader.skip(tag and 7)
            }
        }
        if (mcc !in 1..999 || mnc !in 0..999 || tac !in 0..16_777_215) return null
        val identity = RemoteCellIdentity(radio, mcc.toInt(), mnc.toInt(), tac.toInt(), id)
        try { identity.validate() } catch (_: IllegalArgumentException) { return null }
        val point = position ?: return null
        val distance = GeoMath.distance(LatLng(lat, lng), LatLng(point.first, point.second))
        if (distance !in 0.0..maxDistanceMeters) return null
        return RemoteCellPoint(identity, point.first, point.second, distance)
    }

    private fun decodePosition(reader: Reader): Pair<Double, Double>? {
        var latitude: Long? = null; var longitude: Long? = null
        while (reader.more()) {
            when (val tag = reader.tag()) {
                8 -> latitude = reader.number()
                16 -> longitude = reader.number()
                else -> reader.skip(tag and 7)
            }
        }
        val lat = (latitude ?: return null) * 1e-8
        val lng = (longitude ?: return null) * 1e-8
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }

    private class Reader(private val bytes: ByteArray) {
        private var offset = 0
        fun more() = offset < bytes.size
        fun tag(): Int {
            val tag = number()
            require(tag in 8..Int.MAX_VALUE.toLong())
            return tag.toInt()
        }
        fun number(): Long {
            var result = 0L
            for (index in 0..9) {
                require(more()) { "Truncated varint" }
                val byte = bytes[offset++].toInt() and 255
                require(index != 9 || byte <= 1) { "Varint overflow" }
                result = result or ((byte and 127).toLong() shl (index * 7))
                if (byte and 128 == 0) return result
            }
            error("Invalid varint")
        }
        fun message(): Reader {
            val length = number()
            require(length in 0..(bytes.size - offset).toLong()) { "Truncated message" }
            return Reader(bytes.copyOfRange(offset, offset + length.toInt())).also { offset += length.toInt() }
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> number()
                1 -> advance(8)
                2 -> { val length = number(); require(length in 0..(bytes.size - offset).toLong()); advance(length.toInt()) }
                5 -> advance(4)
                else -> throw IllegalArgumentException("Unsupported protobuf wire type")
            }
        }
        private fun advance(length: Int) { require(length <= bytes.size - offset); offset += length }
    }

    private class Writer {
        private val output = ByteArrayOutputStream()
        fun number(field: Int, value: Long) { varint((field shl 3).toLong()); varint(value) }
        fun message(field: Int, bytes: ByteArray) { varint((field shl 3 or 2).toLong()); varint(bytes.size.toLong()); output.write(bytes) }
        fun bytes(): ByteArray = output.toByteArray()
        private fun varint(value: Long) {
            var remaining = value
            while (remaining and -128L != 0L) { output.write((remaining.toInt() and 127) or 128); remaining = remaining ushr 7 }
            output.write(remaining.toInt())
        }
    }
}
