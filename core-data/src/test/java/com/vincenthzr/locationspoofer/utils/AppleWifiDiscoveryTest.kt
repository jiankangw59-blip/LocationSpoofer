package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.model.*
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TEST_LAT = 30.0
private const val TEST_LON = 113.0
private fun mac(index: Int) = "02:00:00:00:%02x:%02x".format(index / 256, index % 256)
private fun point(index: Int, distance: Double): RemoteWifiPoint {
    val pos = GeoMath.destination(LatLng(TEST_LAT, TEST_LON), 0.0, distance)
    return RemoteWifiPoint(mac(index), pos.lat, pos.lng, distance)
}
private fun varint(value: Long): ByteArray {
    val output = ByteArrayOutputStream(); var rest = value
    do { val bits = (rest and 127).toInt(); rest = rest ushr 7; output.write(bits or if (rest == 0L) 0 else 128) } while (rest != 0L)
    return output.toByteArray()
}
private fun message(field: Int, bytes: ByteArray) = varint((field * 8 + 2).toLong()) + varint(bytes.size.toLong()) + bytes
private fun wifiWire(bssid: String, lat: Double, lon: Double, fields: ByteArray = byteArrayOf()): ByteArray = message(2,
    message(1, bssid.toByteArray()) + message(2,
        byteArrayOf(8) + varint((lat * 1e8).toLong()) + byteArrayOf(16) + varint((lon * 1e8).toLong())) + fields)

private fun channelWire(channel: Long): ByteArray = varint(21L * 8) + varint(channel)

class AppleWifiWlocCodecTest {
    @Test fun canonicalizesUnpaddedUppercaseCompactAndHyphenatedAddresses() {
        assertEquals("0a:0b:0c:0d:0e:0f", WifiBssid.normalize(" A:B:C:D:E:F "))
        assertEquals("0a:0b:0c:0d:0e:0f", WifiBssid.normalize("0A0B0C0D0E0F"))
        assertEquals("0a:0b:0c:0d:0e:0f", WifiBssid.normalize("0A-0B-0C-0D-0E-0F"))
        assertNotNull(WifiBssid.normalize(mac(1))) // locally administered unicast is not assumed mobile
        for (bad in listOf("", "aa:bb:cc", "ff:ff:ff:ff:ff:ff", "01:00:00:00:00:01", "00:00:00:00:00:00"))
            assertNull(WifiBssid.normalize(bad))
    }

    @Test fun usesCanonicalBssidStringsAndBoundsTheRequestBatch() {
        val request = AppleWifiWlocCodec.request(listOf(" A:B:C:D:E:F ")).bytes
        assertTrue(request.toString(Charsets.ISO_8859_1).contains("0a:0b:0c:0d:0e:0f"))
        assertThrows(IllegalArgumentException::class.java) { AppleWifiWlocCodec.request((1..7).map(::mac)) }
        assertThrows(IllegalArgumentException::class.java) { AppleWifiWlocCodec.request(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { AppleWifiWlocCodec.request(listOf("invalid")) }
    }

    @Test fun decodesSignedCoordinatesUnpaddedAddressesAndDeduplicates() {
        val record = wifiWire("A:B:C:D:E:F", -33.86, 151.20)
        val decoded = AppleWifiWlocCodec.decode(ByteArray(10) + record + record, -33.86, 151.20, 12_000.0)
        assertEquals(1, decoded.size); assertEquals("0a:0b:0c:0d:0e:0f", decoded[0].bssid)
        assertEquals(-33.86, decoded[0].latitude, 1e-7); assertEquals(0.0, decoded[0].distanceMeters, 0.01)
    }

    @Test fun retainsAReportedChannelAcrossDuplicateBssidsWithoutInferringFrequency() {
        val first = wifiWire("2:0:0:0:0:1", TEST_LAT, TEST_LON)
        val duplicate = wifiWire(mac(1), TEST_LAT + 0.0001, TEST_LON, channelWire(11))
        val conflicting = wifiWire(mac(1), TEST_LAT + 0.0002, TEST_LON, channelWire(6))
        val result = AppleWifiWlocCodec.decode(
            ByteArray(10) + first + duplicate + conflicting, TEST_LAT, TEST_LON, 300.0
        ).single()
        assertEquals(mac(1), result.bssid)
        assertEquals(TEST_LAT, result.latitude, 1e-7)
        assertEquals(11, result.appleWlocChannel)
        assertEquals(11, result.reportedChannel)
        assertNull(result.metadata)
        assertNull(result.effectiveFrequencyMHz)
    }

    @Test fun onlyNeighborsWithValidChannelsInAnExplicitBandResponseGainFrequency() {
        var response = ByteArray(10) +
            wifiWire("2:0:0:0:0:1", TEST_LAT, TEST_LON, channelWire(11)) +
            wifiWire(mac(2), TEST_LAT, TEST_LON, channelWire(36)) +
            wifiWire(mac(3), TEST_LAT, TEST_LON, channelWire(6)) +
            wifiWire(mac(4), TEST_LAT, TEST_LON, channelWire(14)) +
            wifiWire(mac(5), TEST_LAT, TEST_LON, channelWire(0)) +
            wifiWire(mac(6), -180.0, -180.0, channelWire(11)) +
            wifiWire(mac(7), TEST_LAT, TEST_LON, channelWire(11) + channelWire(6)) +
            wifiWire(mac(8), TEST_LAT, TEST_LON, channelWire(1)) +
            wifiWire(mac(8), TEST_LAT + 0.0001, TEST_LON, channelWire(11)) +
            wifiWire(mac(8), TEST_LAT + 0.0002, TEST_LON, channelWire(1))
        val sent = Buffer()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            checkNotNull(chain.request().body).writeTo(sent)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(response.toResponseBody()).build()
        }.build()
        try {
            val client = AppleWifiWlocClient(http)
            val seeds = listOf(" 2-0-0-0-0-1 ", "020000000002")
            val points = runBlocking { client.query(seeds, TEST_LAT, TEST_LON, RemoteWifiRegion.MAINLAND_CHINA) }
                .associateBy { it.bssid }
            assertEquals((listOf(1, 2, 3, 4, 5, 7, 8)).map(::mac).toSet(), points.keys)
            assertEquals(2437, points.getValue(mac(3)).appleWlocDerivedFrequencyMHz)
            assertEquals(2484, points.getValue(mac(4)).effectiveFrequencyMHz)
            assertNull(points.getValue(mac(1)).effectiveFrequencyMHz)
            assertNull(points.getValue(mac(2)).effectiveFrequencyMHz)
            assertNull(points.getValue(mac(5)).effectiveFrequencyMHz)
            assertEquals(6, points.getValue(mac(7)).appleWlocChannel)
            assertEquals(1, points.getValue(mac(8)).appleWlocChannel)
            assertNull(points.getValue(mac(7)).effectiveFrequencyMHz)
            assertNull(points.getValue(mac(8)).effectiveFrequencyMHz)
            val expandedBand = listOf(0xf8.toByte(), 0x01.toByte(), 0x01.toByte())
            assertEquals(1, sent.readByteArray().toList().windowed(3).count { it == expandedBand })
            val global = runBlocking { client.query(seeds, TEST_LAT, TEST_LON, RemoteWifiRegion.GLOBAL) }
            assertEquals(points.keys, global.map { it.bssid }.toSet())
            assertTrue(global.all { it.appleWlocDerivedFrequencyMHz == null })
            assertFalse(sent.readByteArray().toList().windowed(3).any { it == expandedBand })
            response += wifiWire(mac(9), TEST_LAT, TEST_LON, channelWire(36))
            val contradicted = runBlocking { client.query(seeds, TEST_LAT, TEST_LON, RemoteWifiRegion.MAINLAND_CHINA) }
                .associateBy { it.bssid }
            assertEquals(6, contradicted.getValue(mac(3)).appleWlocChannel)
            assertEquals(36, contradicted.getValue(mac(9)).appleWlocChannel)
            assertTrue(contradicted.values.all { it.appleWlocDerivedFrequencyMHz == null })
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test fun keepsDerivedFrequencySeparateFromAnExplicitWigleChannel() {
        val neighbor = point(4, 100.0).copy(appleWlocChannel = 11, appleWlocDerivedFrequencyMHz = 2462)
        val descriptionOnly = neighbor.copy(metadata = RemoteWifiMetadata(ssid = "fixture", fetchedAt = 1L))
        assertEquals(2462, descriptionOnly.effectiveFrequencyMHz)
        val channelOnly = neighbor.copy(metadata = RemoteWifiMetadata(channel = 36, fetchedAt = 1L))
        assertEquals(36, channelOnly.reportedChannel)
        assertNull(channelOnly.effectiveFrequencyMHz)
        val explicitFrequency = channelOnly.copy(metadata = channelOnly.metadata?.copy(frequencyMHz = 5180))
        assertEquals(5180, explicitFrequency.effectiveFrequencyMHz)
        assertEquals(2462, explicitFrequency.appleWlocDerivedFrequencyMHz)
    }

    @Test fun doesNotBorrowAChannelFromAnInconsistentDuplicatePosition() {
        val first = wifiWire(mac(1), TEST_LAT, TEST_LON)
        val duplicate = wifiWire(mac(1), TEST_LAT + 0.01, TEST_LON, channelWire(11))
        val result = AppleWifiWlocCodec.decode(ByteArray(10) + first + duplicate, TEST_LAT, TEST_LON, 2_000.0).single()
        assertEquals(TEST_LAT, result.latitude, 1e-7)
        assertNull(result.appleWlocChannel)
    }

    @Test fun treatsMissingZeroWrongWireAndOutOfRangeChannelsAsUnknown() {
        val invalidChannels = listOf(byteArrayOf(), channelWire(0), message(21, byteArrayOf(11)),
            channelWire(234), channelWire(0x1_0000_000bL))
        val response = ByteArray(10) + invalidChannels.flatMapIndexed { index, fields ->
            wifiWire(mac(index + 1), TEST_LAT, TEST_LON, fields).toList()
        }.toByteArray()
        val points = AppleWifiWlocCodec.decode(response, TEST_LAT, TEST_LON, 300.0)
        assertEquals(invalidChannels.size, points.size)
        assertTrue(points.all { it.reportedChannel == null })
    }

    @Test fun missingSentinelMulticastAndDistantPositionsAreNotLocalRecords() {
        val bytes = ByteArray(10) + wifiWire(mac(1), -180.0, -180.0) + wifiWire(mac(2), 0.0, 0.0) +
            wifiWire("01:00:00:00:00:01", TEST_LAT, TEST_LON) + message(2, message(1, mac(3).toByteArray()))
        assertTrue(AppleWifiWlocCodec.decode(bytes, TEST_LAT, TEST_LON, 12_000.0).isEmpty())
    }

    @Test fun rejectsTruncationAndOverflowRatherThanTreatingThemAsNoCoverage() {
        assertThrows(IllegalArgumentException::class.java) { AppleWifiWlocCodec.decode(ByteArray(9), TEST_LAT, TEST_LON, 100.0) }
        assertThrows(IllegalArgumentException::class.java) { AppleWifiWlocCodec.decode(ByteArray(10) + byteArrayOf(18, 127), TEST_LAT, TEST_LON, 100.0) }
        assertThrows(IllegalArgumentException::class.java) { AppleWifiWlocCodec.decode(ByteArray(10) + ByteArray(10) { 0xff.toByte() }, TEST_LAT, TEST_LON, 100.0) }
    }

    @Test fun usesTheExplicitChinaOrGlobalEndpointAndPreservesHttpFailures() {
        val hosts = Collections.synchronizedList(mutableListOf<String>())
        var status = 200
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            hosts += chain.request().url.host
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body((ByteArray(10) + wifiWire(mac(1), TEST_LAT, TEST_LON)).toResponseBody()).build()
        }.build()
        try {
            val client = AppleWifiWlocClient(http)
            runBlocking {
                assertEquals(1, client.query(listOf(mac(1)), TEST_LAT, TEST_LON, RemoteWifiRegion.MAINLAND_CHINA).size)
                client.query(listOf(mac(1)), TEST_LAT, TEST_LON, RemoteWifiRegion.GLOBAL)
            }
            assertEquals(listOf("gs-loc-cn.apple.com", "gs-loc.apple.com"), hosts.toList())
            status = 503
            val error = assertThrows(RemoteWifiQueryException::class.java) {
                runBlocking { client.query(listOf(mac(1)), TEST_LAT, TEST_LON, RemoteWifiRegion.GLOBAL) }
            }
            assertEquals(503, error.httpStatus)
        } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }
}

class AppleWifiDiscoveryTest {
    private val region = RemoteWifiRegion.MAINLAND_CHINA
    private val oneTile = AppleWifiSearchPolicy(maxTileRequests = 1)
    private fun emptySeeds() = WigleSeedResult(emptyList(), 1, 0, 0, 0, false, false)

    private fun repeatWlocLookup(earlierDistance: Double, nearby: RemoteWifiPoint): RemoteWifiPoint {
        val discovery = AppleWifiDiscovery(policy = oneTile, localSeeds = { _, _ -> listOf(mac(1)) },
            tileLookup = { _, _, _, _, _ -> emptyList() },
            wlocLookup = { seeds, _, _, _, _ ->
                if (seeds == listOf(mac(1))) listOf(point(2, earlierDistance).copy(
                    appleWlocChannel = 6, appleWlocDerivedFrequencyMHz = 2437
                ))
                else {
                    assertEquals(listOf(mac(2)), seeds)
                    listOf(nearby)
                }
            })
        val result = runBlocking { discovery.query(TEST_LAT, TEST_LON, region) }.points.single()
        assertEquals(nearby.latitude, result.latitude, 0.0)
        return result
    }

    @Test fun repeatedWlocLookupKeepsKnownChannelWhenNewerPositionOmitsIt() {
        val retained = repeatWlocLookup(310.0, point(2, 100.0))
        assertEquals(6, retained.appleWlocChannel)
        assertEquals(2437, retained.appleWlocDerivedFrequencyMHz)
    }

    @Test fun repeatedWlocLookupKeepsMovedRecordsUnknownAndPrefersANewReportedChannel() {
        val moved = repeatWlocLookup(1_000.0, point(2, 100.0))
        assertNull(moved.appleWlocChannel)
        assertNull(moved.appleWlocDerivedFrequencyMHz)
        val changed = repeatWlocLookup(310.0, point(2, 100.0).copy(appleWlocChannel = 11))
        assertEquals(11, changed.appleWlocChannel)
        assertNull(changed.appleWlocDerivedFrequencyMHz)
    }

    @Test fun aSeedSeenInABatchCanStillBeRefinedWithOneIndividualRequest() {
        var calls = 0
        val discovery = AppleWifiDiscovery(policy = oneTile, localSeeds = { _, _ -> listOf(mac(1), mac(2)) },
            tileLookup = { _, _, _, _, _ -> emptyList() }, wlocLookup = { seeds, _, _, _, _ ->
                calls++
                if (calls == 1) listOf(point(2, 4000.0)) else {
                    assertEquals(listOf(mac(2)), seeds); listOf(point(3, 100.0))
                }
            })
        val result = runBlocking { discovery.query(TEST_LAT, TEST_LON, region) }
        assertEquals(2, calls); assertEquals(1, result.points.size)
    }

    @Test fun localTileWithinAdoptionRadiusIsFastAndKeepsItsOriginalPosition() {
        val near = point(1, 100.0)
        val discovery = AppleWifiDiscovery(policy = oneTile, tileLookup = { _, _, _, _, _ -> listOf(near) },
            wlocLookup = { _, _, _, _, _ -> error("unneeded WLOC") }, wigleLookup = { _, _, _, _, _ -> error("unneeded WiGLE") })
        val result = runBlocking { discovery.query(TEST_LAT, TEST_LON, region) }
        assertEquals(RemoteWifiSource.APPLE_TILE, result.source)
        assertEquals(near.latitude, result.points.single().latitude, 0.0)
        assertEquals(near.longitude, result.points.single().longitude, 0.0)
        assertEquals(100.0, result.points.single().distanceMeters, 0.01)
        assertEquals(1, discovery.lastReport!!.tileRequests); assertEquals(0, discovery.lastReport!!.wlocRequests)
    }

    @Test fun walksFromFourKilometersThroughOneKilometerToANearbyRecordedPoint() {
        var calls = 0
        val discovery = AppleWifiDiscovery(policy = oneTile, localSeeds = { _, _ -> listOf(mac(1)) },
            tileLookup = { _, _, _, _, _ -> emptyList() }, wlocLookup = { seeds, _, _, _, _ ->
                calls++; assertEquals(listOf(mac(calls)), seeds)
                listOf(point(calls + 1, listOf(4000.0, 1000.0, 100.0)[calls - 1]))
            })
        val result = runBlocking { discovery.query(TEST_LAT, TEST_LON, region) }
        assertEquals(RemoteWifiSource.APPLE_WLOC, result.source); assertEquals(3, calls)
        assertEquals(100.0, result.points.single().distanceMeters, 0.1)
        val hops = discovery.lastReport!!.hops.map { it.getDouble("nearest_meters") }
        assertTrue(hops.zipWithNext().all { (a, b) -> b < a })
        assertEquals("WITHIN_ADOPTION_RADIUS", discovery.lastReport!!.hops.last().getString("status"))
    }

    @Test fun aDistantDeadEndIsReportedWithoutRelocatingOrAdoptingIt() {
        var calls = 0
        val discovery = AppleWifiDiscovery(policy = oneTile, localSeeds = { _, _ -> listOf(mac(1)) },
            tileLookup = { _, _, _, _, _ -> emptyList() }, wlocLookup = { _, _, _, _, _ ->
                calls++; listOf(point(if (calls == 1) 2 else 1, if (calls == 1) 4000.0 else 4100.0))
            })
        assertThrows(RemoteWifiNoCoverageException::class.java) { runBlocking { discovery.query(TEST_LAT, TEST_LON, region) } }
        assertEquals(2, calls); assertEquals(0, discovery.lastReport!!.acceptedRecords)
        assertEquals(4000.0, discovery.lastReport!!.nearestRecordedMeters!!, 0.1)
    }

    @Test fun providerDistancesCannotTurnADistantRecordIntoALocalOne() {
        val far = point(1, 5000.0).copy(distanceMeters = 0.0)
        val discovery = AppleWifiDiscovery(policy = oneTile, tileLookup = { _, _, _, _, _ -> listOf(far) },
            wlocLookup = { _, _, _, _, _ -> emptyList() })
        assertThrows(RemoteWifiNoCoverageException::class.java) { runBlocking { discovery.query(TEST_LAT, TEST_LON, region) } }
        assertEquals(5000.0, discovery.lastReport!!.nearestRecordedMeters!!, 0.1)
        assertEquals(0, discovery.lastReport!!.acceptedRecords)
    }

    @Test fun retriesNewSeedsButDoesNotRequeryTheSameBssidAtEveryRadius() {
        val radii = mutableListOf<Double>(); var calls = 0
        val discovery = AppleWifiDiscovery(policy = oneTile, tokenProvider = { "private" },
            localSeeds = { _, _ -> listOf(mac(1)) }, tileLookup = { _, _, _, _, _ -> emptyList() },
            wlocLookup = { _, _, _, _, _ -> calls++; emptyList() }, wigleLookup = { _, _, radius, _, _ ->
                radii += radius
                WigleSeedResult(listOf(WifiSeed(mac(1), 10.0)), 1, 1, 0, 0, false, false)
            })
        assertThrows(RemoteWifiNoCoverageException::class.java) { runBlocking { discovery.query(TEST_LAT, TEST_LON, region) } }
        assertEquals(listOf(1000.0, 3000.0, 8000.0), radii); assertEquals(1, calls)
        assertFalse(discovery.lastReport!!.toJson().toString().contains("private"))
        assertFalse(discovery.lastReport!!.toJson().toString().contains(mac(1)))
    }

    @Test fun maximumWlocBudgetStopsAnUnfinishedWalk() {
        val discovery = AppleWifiDiscovery(policy = oneTile.copy(maxWlocRequests = 2),
            localSeeds = { _, _ -> listOf(mac(1)) }, tileLookup = { _, _, _, _, _ -> emptyList() },
            wlocLookup = { seeds, _, _, _, _ ->
                if (seeds.first() == mac(1)) listOf(point(2, 4000.0)) else listOf(point(3, 3000.0))
            })
        assertThrows(RemoteWifiSearchIncompleteException::class.java) { runBlocking { discovery.query(TEST_LAT, TEST_LON, region) } }
        assertEquals(2, discovery.lastReport!!.wlocRequests)
        assertTrue("WLOC_REQUEST_LIMIT" in discovery.lastReport!!.limits)
    }

    @Test fun repeatedOrTruncatedWiglePagesCannotDeclareTheAreaEmpty() {
        for (repeatPage in listOf(true, false)) {
            val discovery = AppleWifiDiscovery(policy = oneTile, tokenProvider = { "key" },
                tileLookup = { _, _, _, _, _ -> emptyList() },
                wigleLookup = { _, _, _, _, _ -> WigleSeedResult(emptyList(), 2, 0, 0, 0, repeatPage, !repeatPage) })
            assertThrows(RemoteWifiSearchIncompleteException::class.java) {
                runBlocking { discovery.query(TEST_LAT, TEST_LON, region) }
            }
            assertEquals("INCOMPLETE", discovery.lastReport!!.outcome)
            assertEquals(6, discovery.lastReport!!.wigleRequests)
        }
    }

    @Test fun hopBudgetDoesNotDeclareTheUnsearchedAreaEmpty() {
        val discovery = AppleWifiDiscovery(policy = oneTile.copy(maxHops = 1), localSeeds = { _, _ -> listOf(mac(1)) },
            tileLookup = { _, _, _, _, _ -> emptyList() }, wlocLookup = { _, _, _, _, _ -> listOf(point(2, 4000.0)) })
        assertThrows(RemoteWifiSearchIncompleteException::class.java) { runBlocking { discovery.query(TEST_LAT, TEST_LON, region) } }
        assertTrue("HOP_LIMIT" in discovery.lastReport!!.limits)
    }

    @Test fun firstTileDnsFailureStillQueriesSavedSeedAndReturnsNearbyWloc() {
        var tileCalls = 0
        var wlocCalls = 0
        val nearby = point(2, 100.0)
        val discovery = AppleWifiDiscovery(policy = oneTile.copy(maxTileRequests = 3),
            tokenProvider = { "fixture" },
            localSeeds = { _, _ -> listOf(mac(1)) },
            tileLookup = { _, _, _, _, _ -> tileCalls++; throw UnknownHostException("fixture tile DNS") },
            wlocLookup = { seeds, _, _, _, _ ->
                wlocCalls++
                assertEquals(listOf(mac(1)), seeds)
                listOf(nearby)
            }, wigleLookup = { _, _, _, _, _ -> error("Unexpected WiGLE request") })

        val snapshot = runBlocking { discovery.query(TEST_LAT, TEST_LON, region) }

        assertEquals(RemoteWifiSource.APPLE_WLOC, snapshot.source)
        assertEquals(nearby.latitude, snapshot.points.single().latitude, 0.0)
        assertEquals(nearby.longitude, snapshot.points.single().longitude, 0.0)
        assertEquals(1, tileCalls)
        assertEquals(1, wlocCalls)
        val report = checkNotNull(discovery.lastReport)
        assertEquals(1, report.tileRequests)
        assertEquals(1, report.wlocRequests)
        assertEquals(0, report.wigleRequests)
        assertEquals("NEAR_WIFI_CONFIRMED", report.toJson().getString("wifi_coverage"))
    }

    @Test fun firstTileDnsFailureWithAnEmptyWlocRethrowsTheSameError() {
        assertUnresolvedTileDnsPreserved(listOf(mac(1)), expectedWlocCalls = 1)
    }

    @Test fun firstTileDnsFailureWithoutASavedSeedDoesNotRepeatTheHost() {
        assertUnresolvedTileDnsPreserved(emptyList(), expectedWlocCalls = 0)
    }

    private fun assertUnresolvedTileDnsPreserved(saved: List<String>, expectedWlocCalls: Int) {
        var tileCalls = 0
        var wlocCalls = 0
        val dns = UnknownHostException("fixture tile DNS")
        val discovery = AppleWifiDiscovery(policy = oneTile.copy(maxTileRequests = 3),
            tokenProvider = { "fixture" }, localSeeds = { _, _ -> saved },
            tileLookup = { _, _, _, _, _ -> tileCalls++; throw dns },
            wlocLookup = { _, _, _, _, _ -> wlocCalls++; emptyList() },
            wigleLookup = { _, _, _, _, _ -> error("Unexpected WiGLE request") })

        val failure = assertThrows(UnknownHostException::class.java) {
            runBlocking { discovery.query(TEST_LAT, TEST_LON, region) }
        }

        // Coroutine stacktrace recovery may wrap the thrown exception.
        assertSame(dns, generateSequence<Throwable>(failure) { it.cause }.last())
        assertEquals(1, tileCalls)
        assertEquals(expectedWlocCalls, wlocCalls)
        val report = checkNotNull(discovery.lastReport)
        assertEquals(1, report.tileRequests)
        assertEquals(expectedWlocCalls, report.wlocRequests)
        assertEquals(0, report.wigleRequests)
        assertEquals("ERROR_UnknownHostException", report.outcome)
        assertEquals("UNKNOWN", report.toJson().getString("wifi_coverage"))
    }

    @Test fun httpAndProtocolErrorsStopInsteadOfBecomingEmptyCoverage() {
        val discovery = AppleWifiDiscovery(policy = oneTile, tileLookup = { _, _, _, _, _ -> throw RemoteWifiQueryException(503) })
        assertThrows(RemoteWifiQueryException::class.java) { runBlocking { discovery.query(TEST_LAT, TEST_LON, region) } }
        assertTrue(discovery.lastReport!!.outcome.startsWith("ERROR"))
        assertEquals(1, discovery.lastReport!!.tileRequests)
    }

    @Test fun failedWiglePageKeepsTheAttemptAndEarlierPageWithoutAdoptingSeeds() {
        val fixture = WiglePagingFixture { 503 to "" }
        fixture.use {
            val discovery = wigleDiscovery(fixture.http)
            val failure = assertThrows(RemoteWifiQueryException::class.java) {
                runBlocking { discovery.query(TEST_LAT, TEST_LON, region) }
            }
            assertEquals(503, failure.httpStatus)
            assertWigleProgress(discovery, "ERROR_RemoteWifiQueryException", pages = 1)
        }
    }

    @Test fun cancellingTheSecondWiglePageKeepsTheAttemptAndEarlierPage() {
        val secondRequest = CompletableDeferred<Unit>()
        val releaseHttp = CountDownLatch(1)
        WiglePagingFixture {
            secondRequest.complete(Unit)
            check(releaseHttp.await(10, TimeUnit.SECONDS))
            503 to ""
        }.use { fixture ->
            val discovery = wigleDiscovery(fixture.http)
            try {
                runBlocking {
                    val job = launch { discovery.query(TEST_LAT, TEST_LON, region) }
                    withTimeout(5_000) { secondRequest.await() }
                    job.cancelAndJoin()
                    assertTrue(job.isCancelled)
                }
                assertWigleProgress(discovery, "CANCELLED", pages = 1)
            } finally {
                releaseHttp.countDown()
            }
        }
    }

    @Test fun completedWigleLookupRecordsEachPageOnce() {
        WiglePagingFixture { 200 to """{"success":true,"results":[]}""" }.use { fixture ->
            val discovery = wigleDiscovery(fixture.http) { listOf(point(1, 100.0)) }
            assertEquals(RemoteWifiSource.APPLE_WLOC, runBlocking { discovery.query(TEST_LAT, TEST_LON, region) }.source)
            assertWigleProgress(discovery, "APPLE_WLOC", pages = 2)
        }
    }

    @Test fun aDiagnosticFailureDoesNotReplaceCancellingWiglePageTwo() {
        val secondRequest = CompletableDeferred<Unit>()
        val releaseHttp = CountDownLatch(1)
        WiglePagingFixture {
            secondRequest.complete(Unit)
            check(releaseHttp.await(10, TimeUnit.SECONDS))
            503 to ""
        }.use { fixture ->
            val source = WigleSeedSource(WigleTransport(fixture.http), WigleSeedFilters(null, null))
            val observed = CompletableDeferred<CancellationException>()
            try {
                runBlocking {
                    val job = launch {
                        try {
                            source.query(TEST_LAT, TEST_LON, 1_000.0, "fixture", 2) { error("diagnostic failure") }
                        } catch (failure: CancellationException) {
                            observed.complete(failure)
                            throw failure
                        }
                    }
                    withTimeout(5_000) { secondRequest.await() }
                    job.cancel(CancellationException("lookup cancelled"))
                    job.join()
                    val failure = withTimeout(5_000) { observed.await() }
                    assertEquals("lookup cancelled", failure.message)
                    assertEquals("diagnostic failure", failure.suppressed.single().message)
                }
            } finally {
                releaseHttp.countDown()
            }
        }
    }

    private fun wigleDiscovery(http: OkHttpClient, wloc: () -> List<RemoteWifiPoint> = { error("unexpected WLOC request") }) =
        AppleWifiDiscovery(policy = oneTile, tokenProvider = { "fixture" },
            wigle = WigleSeedSource(WigleTransport(http), WigleSeedFilters(null, null)),
            tileLookup = { _, _, _, _, _ -> emptyList() }, wlocLookup = { _, _, _, _, _ -> wloc() })

    private fun assertWigleProgress(discovery: AppleWifiDiscovery, outcome: String, pages: Int) {
        val report = checkNotNull(discovery.lastReport)
        assertEquals(outcome, report.outcome)
        assertEquals(2, report.wigleRequests)
        assertEquals(1, report.wigleRawRecords)
        assertEquals(pages, report.wiglePages.size)
        assertEquals(1_000.0, report.wiglePages.first().getDouble("radius_meters"), 0.0)
    }

    private class WiglePagingFixture(nextPage: () -> Pair<Int, String>) : AutoCloseable {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (status, body) = when (request.url.queryParameter("searchAfter")) {
                null -> 200 to """{"success":true,"searchAfter":"opaque","results":[{"netid":"02:00:00:00:00:01","trilat":30.0,"trilong":113.0}]}"""
                "opaque" -> nextPage()
                else -> error("unexpected WiGLE cursor")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        override fun close() {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test fun deadlineAndCallerCancellationHaveDifferentOutcomes() {
        val discovery = AppleWifiDiscovery(policy = oneTile.copy(timeBudgetMillis = 20),
            tileLookup = { _, _, _, _, _ -> delay(100); emptyList() })
        assertThrows(RemoteWifiTimeoutException::class.java) { runBlocking { discovery.query(TEST_LAT, TEST_LON, region) } }
        assertEquals("INCOMPLETE", discovery.lastReport!!.outcome)
        val entered = CompletableDeferred<Unit>()
        val other = AppleWifiDiscovery(policy = oneTile, tileLookup = { _, _, _, _, _ -> entered.complete(Unit); awaitCancellation() })
        runBlocking { val job = launch { other.query(TEST_LAT, TEST_LON, region) }; entered.await(); job.cancelAndJoin() }
        assertEquals("CANCELLED", other.lastReport!!.outcome)
    }

    @Test fun exactSeedMetadataCanBeKeptWithoutInventingRadioFields() {
        val metadata = RemoteWifiMetadata(ssid = "source-name", fetchedAt = 1)
        val discovery = AppleWifiDiscovery(policy = oneTile, tokenProvider = { "key" },
            tileLookup = { _, _, _, _, _ -> emptyList() }, wlocLookup = { _, _, _, _, _ -> listOf(point(1, 100.0)) },
            wigleLookup = { _, _, _, _, _ -> WigleSeedResult(listOf(WifiSeed(mac(1), 100.0, metadata = metadata)), 1, 1, 0, 0, false, false) })
        val result = runBlocking { discovery.query(TEST_LAT, TEST_LON, region) }
        assertEquals("source-name", result.points.single().metadata!!.ssid)
        assertNull(result.points.single().metadata!!.frequencyMHz)
    }

    @Test fun tilePlanIsBoundedAndDatelineSafe() {
        for ((lat, lon) in listOf(TEST_LAT to TEST_LON, 0.0 to 180.0, 85.0 to -179.99)) {
            val keys = AppleWifiDiscovery.seedTileKeys(lat, lon)
            assertTrue(keys.size in 1..13); assertEquals(keys.distinct(), keys)
            assertEquals(AppleWifiTileCodec.tileKey(lat, lon), keys.first())
        }
    }
}

class WigleSeedSourceTest {
    private val now = 1_795_152_000_000L
    private fun record(index: Int, seen: Any? = null): JSONObject = JSONObject().put("netid", mac(index))
        .put("trilat", TEST_LAT).put("trilong", TEST_LON).put("ssid", "observed")
        .put("frequency", 2412).apply { seen?.let { put("lasttime", it) } }
    private fun reply(vararg records: JSONObject, cursor: String? = null): String = JSONObject().put("success", true)
        .put("results", JSONArray(records.toList())).apply { cursor?.let { put("searchAfter", it) } }.toString()
    private fun fixture(reply: (Request, Int) -> Pair<Int, String>, check: (WigleSeedSource, List<Request>) -> Unit) {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request(); val (status, body) = reply(chain.request(), requests.size)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        try { check(WigleSeedSource(WigleTransport(http)) { now }, requests) }
        finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }

    @Test fun shortPagesWithCursorsAreFollowedAndDistancesAreNotRewritten() = fixture(reply = { request, index ->
        assertNull(request.url.queryParameter("closestLat"))
        if (index == 1) 200 to reply(record(1), cursor = "next & page")
        else { assertEquals("next & page", request.url.queryParameter("searchAfter")); 200 to reply(record(2)) }
    }) { source, calls ->
        val result = runBlocking { source.query(TEST_LAT, TEST_LON, 1000.0, "key") }
        assertEquals(2, calls.size); assertEquals(2, result.seeds.size)
        assertTrue(result.seeds.all { it.metadata?.frequencyMHz == 2412 })
        assertFalse(result.truncated)
    }

    @Test fun repeatedBssidsStopEvenWithADifferentCursor() = fixture(reply = { _, index ->
        200 to if (index == 1) reply(record(1), record(2), cursor = "one") else reply(record(2), record(1), cursor = "two")
    }) { source, calls ->
        val result = runBlocking { source.query(TEST_LAT, TEST_LON, 3000.0, "key") }
        assertEquals(2, calls.size); assertEquals(2, result.seeds.size); assertTrue(result.repeatedPage)
    }

    @Test fun lastSeenIsUsedForAgeAndDatabaseUpdateDoesNotMakeAnOldObservationFresh() = fixture(reply = { _, _ ->
        200 to reply(record(1, "2000-01-01T00:00:00Z").put("lastupdt", now),
            record(2), record(3, now / 1000), record(4, now / 1000 + 301))
    }) { source, _ ->
        val result = runBlocking { source.query(TEST_LAT, TEST_LON, 1000.0, "key") }
        assertEquals(listOf(mac(3), mac(2)), result.seeds.map { it.bssid })
        assertEquals(1, result.staleRecords); assertEquals(1, result.invalidRecords)
        assertNull(result.seeds.last().observedAt)
    }

    @Test fun filtersInvalidAndOutOfRadiusRecordsButDoesNotDiscardLocalUnicastAddresses() = fixture(reply = { _, _ ->
        200 to reply(record(1), record(2).put("trilat", TEST_LAT + 1), record(3).put("netid", "ff:ff:ff:ff:ff:ff"))
    }) { source, _ ->
        val result = runBlocking { source.query(TEST_LAT, TEST_LON, 1000.0, "key") }
        assertEquals(listOf(mac(1)), result.seeds.map { it.bssid }); assertEquals(2, result.invalidRecords)
    }

    @Test fun exhaustedPageBudgetIsReportedEvenIfEachPageIsShort() = fixture(reply = { _, index ->
        200 to reply(record(index), cursor = index.toString())
    }) { source, calls ->
        val result = runBlocking { source.query(TEST_LAT, TEST_LON, 8000.0, "key") }
        assertEquals(2, calls.size); assertTrue(result.truncated)
    }

    @Test fun datelineIsSplitWithoutSendingAnInvertedBoundingBox() = fixture(reply = { request, _ ->
        assertTrue(request.url.queryParameter("longrange1")!!.toDouble() < request.url.queryParameter("longrange2")!!.toDouble())
        200 to reply()
    }) { source, calls ->
        runBlocking { source.query(0.0, 180.0, 1000.0, "key") }; assertEquals(2, calls.size)
    }

    @Test fun unauthorizedAndThrottledResponsesStopImmediately() {
        for (status in listOf(401, 429)) fixture(reply = { _, _ -> status to "unavailable" }) { source, calls ->
            val error = assertThrows(RemoteWifiQueryException::class.java) { runBlocking { source.query(TEST_LAT, TEST_LON, 1000.0, "key") } }
            assertEquals(status, error.httpStatus); assertEquals(1, calls.size)
        }
    }

    @Test fun absentCredentialsDoNotSpendRequests() = fixture(reply = { _, _ -> error("network") }) { source, calls ->
        val result = runBlocking { source.query(TEST_LAT, TEST_LON, 1000.0, "") }
        assertTrue(result.seeds.isEmpty()); assertTrue(calls.isEmpty())
    }

    @Test fun sendsOfficialCursorAndDateQualityParametersWithoutLegacyOffsets() = fixture(reply = { request, index ->
        assertEquals("1", request.url.queryParameter("minQoS"))
        assertTrue(request.url.queryParameter("lastupdt")!!.matches(Regex("[0-9]{8}")))
        assertNull(request.url.queryParameter("first")); assertNull(request.url.queryParameter("search_after"))
        if (index == 1) 200 to reply(record(1), cursor = "opaque+/ =&next")
        else {
            assertEquals("opaque+/ =&next", request.url.queryParameter("searchAfter"))
            200 to reply(record(2))
        }
    }) { source, _ ->
        val result = runBlocking { source.query(TEST_LAT, TEST_LON, 3000.0, "private-key") }
        assertEquals(2, result.pages.size)
        assertEquals(result.pages[0].getString("response_cursor_hash"), result.pages[1].getString("request_cursor_hash"))
        assertFalse(result.pages.toString().contains("private-key")); assertFalse(result.pages.toString().contains("opaque+/"))
    }

    @Test fun rejectsRecordsThatViolateTheRequestedUpdateDateOrQuality() = fixture(reply = { _, _ ->
        200 to reply(record(1).put("lastupdt", "2001-01-01T00:00:00Z").put("qos", 7),
            record(2).put("lastupdt", now).put("qos", 0), record(3).put("lastupdt", now).put("qos", 1))
    }) { source, _ ->
        val result = runBlocking { source.query(TEST_LAT, TEST_LON, 3000.0, "key") }
        assertEquals(listOf(mac(3)), result.seeds.map { it.bssid })
        assertEquals(2, result.filterViolations); assertTrue("FILTER_CONTRACT_MISMATCH" in result.limits)
    }

    @Test fun cannotFollowAnIntegerCoercedIntoAStringCursor() = fixture(reply = { _, _ ->
        200 to JSONObject(reply(record(1))).put("searchAfter", 123).put("search_after", 456).toString()
    }) { source, calls ->
        val result = runBlocking { source.query(TEST_LAT, TEST_LON, 3000.0, "key") }
        assertEquals(1, calls.size); assertTrue(result.truncated)
        assertTrue("INVALID_CURSOR" in result.limits)
    }

    @Test fun neverFallsBackToTheDeprecatedIntegerCursor() = fixture(reply = { _, _ ->
        200 to JSONObject(reply(record(1))).put("search_after", 123).toString()
    }) { source, calls ->
        runBlocking { source.query(TEST_LAT, TEST_LON, 3000.0, "key") }
        assertEquals(1, calls.size); assertNull(calls.single().url.queryParameter("searchAfter"))
    }

    @Test fun partialOverlapPreservesNewBssidsButAWholePageOfOldOnesStops() = fixture(reply = { _, index ->
        200 to if (index == 1) reply(record(1), record(2), cursor = "one") else reply(record(2), record(3), cursor = "two")
    }) { source, calls ->
        val result = runBlocking { source.query(TEST_LAT, TEST_LON, 3000.0, "key") }
        assertEquals(2, calls.size); assertEquals(3, result.seeds.size)
        assertEquals(1, result.pages[1].getInt("new_bssids")); assertEquals(1, result.pages[1].getInt("duplicate_bssids"))
        assertFalse(result.repeatedPage); assertTrue(result.truncated)
    }

    @Test fun repeatingTheCursorStopsEvenWhenThePageContainsNewBssids() = fixture(reply = { _, index ->
        200 to reply(record(index), cursor = "same")
    }) { source, calls ->
        val result = runBlocking { source.query(TEST_LAT, TEST_LON, 3000.0, "key") }
        assertEquals(2, calls.size); assertEquals(2, result.seeds.size)
        assertTrue(result.repeatedPage); assertTrue("REPEATED_CURSOR" in result.limits)
    }

    @Test fun explicitMalformedObservationTimesAreNotTreatedAsUnknownFreshness() = fixture(reply = { _, _ ->
        200 to reply(record(1, "malformed"), record(2).put("lastupdt", "malformed"), record(3))
    }) { source, _ ->
        val result = runBlocking { source.query(TEST_LAT, TEST_LON, 3000.0, "key") }
        assertEquals(listOf(mac(3)), result.seeds.map { it.bssid }); assertEquals(2, result.invalidRecords)
    }
}
