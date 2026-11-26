package com.vincenthzr.locationspoofer.xposed.hooks

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.*

internal object NmeaEncoder {
    fun coordinate(value: Double, latitude: Boolean): Pair<String, String> {
        require(value.isFinite() && abs(value) <= if (latitude) 90.0 else 180.0)
        // Round the total minutes before splitting degrees, so 59.999999 minutes carries correctly.
        val total = round(abs(value) * 60 * 100_000) / 100_000
        val degrees = (total / 60).toInt()
        val minutes = total - degrees * 60
        return String.format(Locale.US, if (latitude) "%02d%08.5f" else "%03d%08.5f", degrees, minutes) to
            (if (latitude) { if (value >= 0) "N" else "S" } else { if (value >= 0) "E" else "W" })
    }

    fun sentence(body: String): String {
        val raw = body.removePrefix("$").substringBefore('*')
        val checksum = raw.fold(0) { sum, char -> sum xor char.code }
        return "$" + raw + String.format(Locale.US, "*%02X\r\n", checksum)
    }

    fun build(latitude: Double, longitude: Double, speedMps: Float, bearing: Float, altitude: Double,
        accuracy: Float, satelliteCount: Int, jitter: Boolean, epochMs: Long): List<String> {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = epochMs }
        val time = String.format(Locale.US, "%02d%02d%02d.%02d", utc.get(Calendar.HOUR_OF_DAY),
            utc.get(Calendar.MINUTE), utc.get(Calendar.SECOND), utc.get(Calendar.MILLISECOND) / 10)
        val date = String.format(Locale.US, "%02d%02d%02d", utc.get(Calendar.DAY_OF_MONTH),
            utc.get(Calendar.MONTH) + 1, utc.get(Calendar.YEAR) % 100)
        val (lat, ns) = coordinate(latitude, true)
        val (lon, ew) = coordinate(longitude, false)
        val sky = SatelliteSky.snapshot(satelliteCount, epochMs, jitter)
        val hdop = (accuracy / 3.0).coerceIn(0.5, 3.0)
        val vdop = hdop * 1.125
        val pdop = hypot(hdop, vdop)
        fun f(value: Double, places: Int = 1) = String.format(Locale.US, "%.${places}f", value)
        val lines = mutableListOf(
            sentence("GNRMC,$time,A,$lat,$ns,$lon,$ew,${f(speedMps.coerceAtLeast(0f) * 1.943844, 2)},${f(bearing.toDouble())},$date,,,A"),
            sentence("GNGGA,$time,$lat,$ns,$lon,$ew,1,${sky.count { it.used }},${f(hdop)},${f(altitude)},M,0.0,M,,")
        )
        for ((talker, satellites) in sky.groupBy { it.talker }) {
            val used = satellites.filter { it.used }.map { String.format(Locale.US, "%02d", it.svid) }
            val slots = List(12) { used.getOrNull(it).orEmpty() }.joinToString(",")
            lines += sentence("${talker}GSA,A,${if (used.isEmpty()) 1 else 3},$slots,${f(pdop)},${f(hdop)},${f(vdop)},${satellites.first().systemId}")
            val pages = satellites.chunked(4)
            pages.forEachIndexed { index, page ->
                val fields = page.joinToString(",") { satellite -> String.format(Locale.US, "%02d,%02d,%03d,%02d",
                    satellite.svid, satellite.elevation.roundToInt(), satellite.azimuth.roundToInt() % 360, satellite.cn0.roundToInt()) }
                lines += sentence("${talker}GSV,${pages.size},${index + 1},${satellites.size},$fields")
            }
        }
        return lines
    }
}
