package com.vincenthzr.locationspoofer.xposed.hooks

import org.junit.Assert.*
import org.junit.Test

class NmeaEncoderTest {
    private val epoch = 1_795_000_000_987L
    private fun fields(sentence: String) = sentence.trim().substringBefore('*').removePrefix("$").split(',')

    @Test fun skyHasUniqueIdentitiesAndBoundedUsedCount() {
        for (count in 4..32) {
            val sky = SatelliteSky.snapshot(count, epoch, true)
            assertEquals(count, sky.size)
            assertEquals(count, sky.map { it.constellation to it.svid }.distinct().size)
            assertEquals(minOf(count, 12), sky.count { it.used })
        }
    }

    @Test fun allSentencesHaveChecksumsAndLineEndings() {
        val sentences = NmeaEncoder.build(-33.8, 151.2, 1.5f, 270f, 32.2, 2.5f, 32, true, epoch)
        for (sentence in sentences) {
            assertTrue(sentence.endsWith("\r\n"))
            assertTrue(sentence.length <= 82)
            val body = sentence.substringBefore('*').removePrefix("$")
            val sum = body.fold(0) { value, char -> value xor char.code }
            assertEquals("%02X".format(sum), sentence.substringAfter('*').trim())
        }
        assertTrue(fields(sentences.first())[1].endsWith(".98"))
        assertEquals("S", fields(sentences.first())[4])
        assertEquals("E", fields(sentences.first())[6])
    }

    @Test fun gsvAndGsaUseExactlyTheGnssSky() {
        for (count in listOf(4, 10, 16, 24, 32)) {
            val sky = SatelliteSky.snapshot(count, epoch, false)
            val sentences = NmeaEncoder.build(30.0, 113.0, 0f, 0f, 25.0, 2.5f, count, false, epoch)
            val seen = mutableSetOf<Pair<String, Int>>()
            val used = mutableSetOf<Pair<String, Int>>()
            for (line in sentences) {
                val values = fields(line)
                val talker = values[0].take(2)
                when (values[0].drop(2)) {
                    "GSV" -> for (i in 4 until values.size step 4) seen += talker to values[i].toInt()
                    "GSA" -> values.subList(3, 15).filter { it.isNotEmpty() }.forEach { used += talker to it.toInt() }
                    "GGA" -> assertEquals(sky.count { it.used }, values[7].toInt())
                }
            }
            assertEquals(sky.map { it.talker to it.svid }.toSet(), seen)
            assertEquals(sky.filter { it.used }.map { it.talker to it.svid }.toSet(), used)
        }
    }

    @Test fun ggaWritesOrthometricHeightAndSeparationWithoutChangingOtherSentences() {
        val legacy = NmeaEncoder.build(30.0, 113.0, 1.5f, 270f, 0.0, 2.5f, 16, false, epoch)
        val projected = NmeaEncoder.build(30.0, 113.0, 1.5f, 270f, NmeaAltitude(-0.6862, 0.6862),
            2.5f, 16, false, epoch)
        assertEquals(listOf("0.0", "M", "0.0", "M"), fields(legacy[1]).subList(9, 13))
        assertEquals(listOf("-0.7", "M", "0.7", "M"), fields(projected[1]).subList(9, 13))
        assertEquals(legacy.filterIndexed { index, _ -> index != 1 },
            projected.filterIndexed { index, _ -> index != 1 })
    }

    @Test fun ggaKeepsFieldPositionsWhenAltitudeIsUnknown() {
        val gga = NmeaEncoder.build(30.0, 113.0, 0f, 0f, null, 2.5f, 16, false, epoch)[1]
        val values = fields(gga)
        assertEquals("GNGGA", values[0])
        assertEquals(15, values.size)
        assertEquals(listOf("", "M", "", "M", "", ""), values.subList(9, 15))
        assertEquals(NmeaEncoder.sentence(gga.substringBefore('*')), gga)
    }

    @Test fun coordinateRoundingNeverProducesSixtyMinutes() {
        assertEquals("9000.00000", NmeaEncoder.coordinate(89.9999999999, true).first)
        assertEquals("18000.00000", NmeaEncoder.coordinate(179.9999999999, false).first)
        assertEquals("W", NmeaEncoder.coordinate(-179.9999999999, false).second)
    }

    @Test fun packedFlagsDoNotOverlapIdentityOrConstellation() {
        val sat = SatelliteSky.snapshot(32, epoch, true).first { it.constellation == 1 }
        for ((svidShift, constellationShift) in listOf(8 to 4, 12 to 8)) {
            val packed = SatelliteSky.packed(sat, svidShift, constellationShift, false)
            assertEquals(sat.svid, packed ushr svidShift)
            assertEquals(sat.constellation, (packed ushr constellationShift) and 15)
            assertEquals(sat.used, packed and 4 != 0)
            assertEquals(sat.carrierHz != null, packed and 8 != 0)
        }
    }

    @Test fun satelliteGeometryDoesNotJumpAtTenSecondBoundary() {
        val before = SatelliteSky.snapshot(32, epoch / 10_000 * 10_000 - 1, true)
        val after = SatelliteSky.snapshot(32, epoch / 10_000 * 10_000, true)
        before.zip(after).forEach { (a, b) ->
            assertTrue(kotlin.math.abs(a.elevation - b.elevation) < 0.01)
            assertTrue(kotlin.math.abs(a.azimuth - b.azimuth) < 0.02)
            assertTrue(kotlin.math.abs(a.cn0 - b.cn0) < 0.5)
        }
    }
}
