package com.vincenthzr.locationspoofer.data.db

import com.vincenthzr.locationspoofer.data.model.SavedLocation
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EnvironmentImportTimestampsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Test fun standardBackupSeparatesMissingTimesFromExplicitTimes() {
        val backup = json.decodeFromString<LocationSpooferDataPackage>("""{
            "version":4,"exportTimestamp":123,"locations":[
                {"location":{"lat":0,"lng":2,"remark":"one"}},
                {"location":{"lat":1,"lng":3,"timestamp":1700000000001}},
                {"location":{"lat":2,"lng":4,"timestamp":null}},
                {"location":{"lat":3,"lng":5,"timestamp":0}}],
            "collectionRoutes":[{"route":{"name":"existing","timestamp":42},"samples":[
                {"location":{"lat":1,"lng":1}},
                {"location":{"lat":2,"lng":2,"timestamp":null}},
                {"location":{"lat":3,"lng":3,"timestamp":1700000000002}}]}],
            "savedRoutes":[{"name":"route","timestamp":987}],
            "savedLocations":[{"lat":3,"lng":4,"name":"bookmark"}],
            "extension":{"location":{"lat":2,"lng":3}}
        }""")
        assertEquals(listOf(0L, 1700000000001L, 0L, 0L), backup.locations.map { it.location.timestamp })
        assertEquals("one", backup.locations.first().location.remark)
        val route = backup.collectionRoutes.single()
        assertEquals(42L, route.route.timestamp)
        assertEquals(listOf(0L, 0L, 1700000000002L), route.samples.map { it.location.timestamp })
        assertEquals(987L, backup.savedRoutes.single().timestamp)
        assertEquals(123L, backup.exportTimestamp)
        assertEquals(SavedLocation("bookmark", 3.0, 4.0), backup.savedLocations.single())
        assertEquals(backup, json.decodeFromString<LocationSpooferDataPackage>(json.encodeToString(backup)))
    }

    @Test fun legacyListPreservesOrderAndOptionalFields() {
        val samples = json.decodeFromString<List<CompleteLocation>>("""[
            {"location":{"lat":1,"lng":1,"timestamp":null,"placeName":"one"}},
            {"location":{"lat":2,"lng":2,"selectedCellKey":"123"}},
            {"location":{"lat":3,"lng":3,"timestamp":1700000000000}}
        ]""")
        assertEquals(listOf(0L, 0L, 1700000000000L), samples.map { it.location.timestamp })
        assertEquals("one", samples[0].location.placeName)
        assertEquals("123", samples[1].location.selectedCellKey)
    }

    @Test fun liveCaptureAndStandaloneLocationRecordKeepTheirClockDefaults() {
        val before = System.currentTimeMillis()
        val capture = LocationRecord(lat = 0.0, lng = 0.0)
        val standalone = json.decodeFromString<LocationRecord>("""{"lat":1,"lng":2}""")
        val after = System.currentTimeMillis()
        assertTrue(capture.timestamp in before..after)
        assertTrue(standalone.timestamp in before..after)
    }

    @Test fun invalidNonNullTimesAndMissingCoordinatesAreNotSilentlyRepaired() {
        for (input in listOf("""{"location":{"lat":1,"lng":2,"timestamp":"invalid"}}""",
                             """{"location":{"lat":1}}""")) {
            try {
                json.decodeFromString<CompleteLocation>(input)
                fail("Malformed record was accepted")
            } catch (expected: SerializationException) {
                assertNotNull(expected)
            }
        }
    }
}
