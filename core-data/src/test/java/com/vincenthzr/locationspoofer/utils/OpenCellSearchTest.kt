package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.data.model.RemoteCellRadio
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OpenCellSearchTest {
    private val now = 1_795_152_000_000L
    private val lat = 28.41075894581519
    private val lon = 117.59126442904986
    private val oneBox = OpenCellSearchPolicy(rings = 0)
    private fun cell(id: Long = 1, updated: Long? = now / 1000, radio: String = "LTE", latitude: Double = lat,
        longitude: Double = lon): JSONObject = JSONObject().put("radio", radio).put("mcc", 460).put("mnc", 0)
        .put("lac", 321).put("tac", 0).put("cellid", id).put("lat", latitude).put("lon", longitude)
        .apply { updated?.let { put("updated", it) } }
    private fun page(vararg cells: JSONObject, count: Int = cells.size) = JSONObject()
        .put("count", count).put("cells", JSONArray(cells.toList())).toString()

    private fun fixture(policy: OpenCellSearchPolicy = oneBox, clock: () -> Long = { now },
        reply: (Request, Int) -> Pair<Int, String>, check: (OpenCellSearchClient, List<Request>) -> Unit) {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            val (code, body) = reply(request, requests.size)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        try { check(OpenCellSearchClient(http, policy, clock), requests) }
        finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }

    @Test fun gridCoversTwentyFiveUniqueSmallBoxesCenterFirst() {
        val boxes = OpenCellGrid.boxes(lat, lon, OpenCellSearchPolicy())
        assertEquals(29, boxes.size); assertEquals(boxes.size, boxes.distinct().size)
        assertTrue(boxes.first().contains(lat, lon))
        assertTrue(boxes.all { it.areaMetersSquared in 3_000_000.0..4_000_000.0 })
        val dense = boxes.take(25)
        val northSouth = GeoMath.distance(CoordinateUtils.LatLng(dense.minOf { it.latMin }, lon),
            CoordinateUtils.LatLng(dense.maxOf { it.latMax }, lon))
        assertEquals(9000.0, northSouth, 1.0)
    }

    @Test fun gridsAtDatelineAndHighLatitudeKeepEveryRequestWithinTheApiAreaLimit() {
        for ((latitude, longitude) in listOf(0.0 to 180.0, 0.0 to -180.0, 85.0 to 179.999, -85.0 to -179.999)) {
            val boxes = OpenCellGrid.boxes(latitude, longitude, OpenCellSearchPolicy())
            assertTrue(boxes.all { it.latMin < it.latMax && it.lonMin < it.lonMax && it.areaMetersSquared in 0.0..4_000_000.0 })
            assertTrue(boxes.all { it.lonMin >= -180 && it.lonMax <= 180 })
        }
        assertEquals(OpenCellGrid.boxes(0.0, 180.0, oneBox), OpenCellGrid.boxes(0.0, -180.0, oneBox))
    }

    @Test fun invalidTargetAndUnboundedPoliciesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { OpenCellGrid.boxes(Double.NaN, lon, oneBox) }
        assertThrows(IllegalArgumentException::class.java) { OpenCellSearchPolicy(rings = 3) }
        assertThrows(IllegalArgumentException::class.java) { OpenCellSearchPolicy(maxRequests = 100) }
        assertThrows(IllegalArgumentException::class.java) { OpenCellSearchPolicy(pageSize = 51) }
    }

    @Test fun shortPagesFollowCountAndUseActualOffsetsUntilTheAreaIsExhausted() = fixture(reply = { request, _ ->
        val offset = request.url.queryParameter("offset")!!.toInt()
        200 to if (offset < 2) page(cell(offset + 1L), count = 2) else page()
    }) { search, requests ->
        val result = runBlocking { search.search(lat, lon, "secret") }
        assertEquals(listOf("0", "1"), requests.map { it.url.queryParameter("offset") })
        assertEquals(2, result.records.size); assertTrue(result.report.complete)
        assertEquals(0, result.records.first().mnc)
        assertFalse(result.report.toJson().toString().contains("secret"))
    }

    @Test fun emptyPagesAfterFullPagesFinishWithoutAnExtraWholeGridRetry() = fixture(
        policy = oneBox.copy(pageSize = 1), reply = { _, index -> 200 to if (index == 1) page(cell()) else page() }) { search, calls ->
        val result = runBlocking { search.search(lat, lon, "key") }
        assertEquals(2, calls.size); assertTrue(result.report.complete); assertEquals(1, result.records.size)
    }

    @Test fun anEmptyFirstPageWithAReportedPositiveCountIsIncomplete() = fixture(
        reply = { _, _ -> 200 to page(count = 2) }) { search, calls ->
        val result = runBlocking { search.search(lat, lon, "key") }
        assertEquals(1, calls.size)
        assertTrue(result.records.isEmpty())
        assertEquals(0, result.report.completedBoxes)
        assertTrue("EMPTY_PAGE_WITH_REMAINING_COUNT" in result.report.limits)
        assertFalse(result.report.complete)
    }

    @Test fun anEmptyLaterPageWithoutACountRetainsThePreviousCountEvidence() = fixture(reply = { _, pageNumber ->
        200 to if (pageNumber == 1) page(cell(), count = 3) else """{"cells":[]}"""
    }) { search, calls ->
        val result = runBlocking { search.search(lat, lon, "key") }
        assertEquals(listOf("0", "1"), calls.map { it.url.queryParameter("offset") })
        assertEquals(1, result.records.size)
        assertEquals(0, result.report.completedBoxes)
        assertTrue("EMPTY_PAGE_WITH_REMAINING_COUNT" in result.report.limits)
        assertFalse(result.report.complete)
    }

    @Test fun anEmptyLaterPageCanConfirmThatTheReportedCountShrank() = fixture(reply = { _, pageNumber ->
        200 to if (pageNumber == 1) page(cell(), count = 3) else page(count = 1)
    }) { search, calls ->
        val result = runBlocking { search.search(lat, lon, "key") }
        assertEquals(listOf("0", "1"), calls.map { it.url.queryParameter("offset") })
        assertEquals(1, result.records.size)
        assertEquals(1, result.report.completedBoxes)
        assertTrue(result.report.complete)
    }

    @Test fun aShortLaterPageWithoutACountContinuesWhileTheKnownCountHasRemainingRows() = fixture(reply = { _, pageNumber ->
        200 to if (pageNumber == 1) page(cell(), count = 3)
            else JSONObject().put("cells", JSONArray(listOf(cell(pageNumber.toLong())))).toString()
    }) { search, calls ->
        val result = runBlocking { search.search(lat, lon, "key") }
        assertEquals(listOf("0", "1", "2"), calls.map { it.url.queryParameter("offset") })
        assertEquals(3, result.records.size)
        assertTrue(result.report.complete)
    }

    @Test fun aShortLaterPageUsesTheNewCountWhenItShrinks() = fixture(reply = { _, pageNumber ->
        200 to if (pageNumber == 1) page(cell(), count = 3) else page(cell(2), count = 2)
    }) { search, calls ->
        val result = runBlocking { search.search(lat, lon, "key") }
        assertEquals(listOf("0", "1"), calls.map { it.url.queryParameter("offset") })
        assertEquals(2, result.records.size)
        assertTrue(result.report.complete)
    }

    @Test fun repeatedLogicalPagesStopEvenWhenOrderAndNonIdentityFieldsChange() = fixture(
        policy = oneBox.copy(pageSize = 2), reply = { _, index ->
            val a = cell(1).put("samples", index); val b = cell(2)
            200 to if (index == 1) page(a, b, count = 100) else page(b, a, count = 100)
        }) { search, calls ->
        val result = runBlocking { search.search(lat, lon, "key") }
        assertEquals(2, calls.size); assertEquals(2, result.records.size)
        assertTrue("REPEATED_PAGE" in result.report.limits); assertFalse(result.report.complete)
    }

    @Test fun distinctOldPagesDoNotLookLikeRepeatedEmptyCandidatePages() = fixture(reply = { _, index ->
        200 to if (index == 1) page(cell(1, updated = 1), count = 2) else page(cell(2), count = 2)
    }) { search, calls ->
        val result = runBlocking { search.search(lat, lon, "key") }
        assertEquals(2, calls.size); assertEquals(2L, result.records.single().cellId)
        assertEquals(1, result.report.rejected["STALE"]); assertTrue(result.report.complete)
    }

    @Test fun globalRequestBudgetDoesNotPretendTheWholeGridWasQueried() = fixture(
        policy = OpenCellSearchPolicy(maxRequests = 2), reply = { _, _ -> 200 to page() }) { search, calls ->
        val result = runBlocking { search.search(lat, lon, "key") }
        assertEquals(2, calls.size); assertEquals(29, result.report.plannedBoxes)
        assertEquals(2, result.report.completedBoxes); assertTrue("REQUEST_LIMIT" in result.report.limits)
        assertFalse(result.report.complete)
    }

    @Test fun perBoxPageBudgetIsReportedWithoutClaimingCompleteCoverage() = fixture(
        policy = oneBox.copy(maxPagesPerBox = 2), reply = { _, index -> 200 to page(cell(index.toLong()), count = 100) }) { search, calls ->
        val result = runBlocking { search.search(lat, lon, "key") }
        assertEquals(2, calls.size); assertTrue("PAGE_LIMIT" in result.report.limits); assertFalse(result.report.complete)
    }

    @Test fun staleIsRejectedUnknownIsRetainedAndFreshIsRankedFirst() = fixture(reply = { _, _ ->
        200 to page(cell(1, updated = 1), cell(2, updated = null), cell(3, latitude = lat + 0.001))
    }) { search, _ ->
        val result = runBlocking { search.search(lat, lon, "key") }
        assertEquals(listOf(3L, 2L), result.records.map { it.cellId })
        assertEquals(1, result.report.recentRecords); assertEquals(1, result.report.unknownAgeRecords)
        assertEquals(1, result.report.rejected["STALE"])
        assertNull(result.records.last().updatedAtMillis)
    }

    @Test fun futureTimestampIsRejectedAndMillisecondsAreNotMistakenForSeconds() = fixture(reply = { _, _ ->
        200 to page(cell(1, updated = now), cell(2, updated = now / 1000 + 301))
    }) { search, _ ->
        val result = runBlocking { search.search(lat, lon, "key") }
        assertEquals(now, result.records.single().updatedAtMillis)
        assertEquals(1, result.report.rejected["FUTURE_UPDATED"])
    }

    @Test fun invalidRadioIdentityPositionAndAreaDoNotBecomeAutomaticSeeds() = fixture(reply = { _, _ ->
        200 to page(cell(1).put("radio", "mystery"), cell(2).apply { remove("mcc") },
            cell(3).put("mnc", -1), cell(4, latitude = 91.0), cell(5, latitude = lat + 1.0))
    }) { search, _ ->
        val result = runBlocking { search.search(lat, lon, "key") }
        assertTrue(result.records.isEmpty())
        assertEquals(1, result.report.rejected["UNSUPPORTED_RADIO"])
        assertEquals(2, result.report.rejected["INVALID_IDENTITY"])
        assertEquals(1, result.report.rejected["INVALID_POSITION"])
        assertEquals(1, result.report.rejected["OUTSIDE_BOX"])
    }

    @Test fun fullNrIdentityAndLegitimateZeroTacAndMncArePreserved() = fixture(reply = { _, _ ->
        200 to page(cell(68_719_476_735L, radio = "NR").put("lac", 0))
    }) { search, _ ->
        val record = runBlocking { search.search(lat, lon, "key") }.records.single()
        assertEquals(68_719_476_735L, record.appleSeed()!!.id)
        assertEquals(0, record.area); assertEquals(0, record.mnc)
        val output = JSONArray(runBlocking { OpenCellIdClient(search).fetchCellData(lat, lon, "key") }).getJSONObject(0)
        assertEquals(68_719_476_735L, output.getLong("nci"))
        assertEquals(Int.MAX_VALUE, output.getInt("pci"))
        assertEquals("DISTANCE_MODEL", output.getString("signal_source"))
        assertFalse(output.getBoolean("isRegistered"))
    }

    @Test fun pciZeroIsKeptRatherThanReplacedWithAnIdentityDerivedValue() = fixture(reply = { _, _ ->
        200 to page(cell().put("pci", 0).put("dbm", -99))
    }) { search, _ ->
        val output = JSONArray(runBlocking { OpenCellIdClient(search).fetchCellData(lat, lon, "key") }).getJSONObject(0)
        assertEquals(0, output.getInt("pci")); assertEquals(-99, output.getInt("dbm"))
        assertEquals("RECORDED", output.getString("signal_source"))
    }

    @Test fun moreRecentlyUpdatedDuplicateReplacesAnOlderRecord() = fixture(reply = { _, index ->
        200 to page(cell(1, updated = if (index == 1) now / 1000 - 100 else now / 1000), count = 2)
    }) { search, _ ->
        val result = runBlocking { search.search(lat, lon, "key") }
        assertEquals(now, result.records.single().updatedAtMillis); assertEquals(1, result.report.duplicates)
    }

    @Test fun denseResultsAreBoundedAndLogicalCellsAtOnePositionAreNotCollapsed() = fixture(
        policy = oneBox.copy(maxRecords = 2), reply = { _, _ -> 200 to page(cell(1), cell(2), cell(3)) }) { search, _ ->
        val result = runBlocking { search.search(lat, lon, "key") }
        assertEquals(2, result.records.size); assertTrue("RECORD_LIMIT" in result.report.limits)
    }

    @Test fun radioFilterIsSentAndMismatchingReturnedRecordsAreNotUsed() = fixture(reply = { request, _ ->
        assertEquals("NR", request.url.queryParameter("radio"))
        200 to page(cell(1), cell(2, radio = "NR"))
    }) { search, _ ->
        val result = runBlocking { search.search(lat, lon, "key", RemoteCellRadio.NR) }
        assertEquals("NR", result.records.single().radio); assertEquals(1, result.report.rejected["RADIO_FILTER"])
    }

    @Test fun directFallbackAndSeedDiscoveryReuseTheSameCoordinateAndCredentialResult() = fixture(reply = { _, _ ->
        200 to page(cell())
    }) { search, calls ->
        val first = runBlocking { search.search(lat, lon, "key") }
        val second = runBlocking { search.search(lat, lon, "key") }
        assertFalse(first.report.fromCache); assertTrue(second.report.fromCache); assertEquals(1, calls.size)
        assertEquals(1, JSONArray(runBlocking { OpenCellIdClient(search).fetchCellData(lat, lon, "key") }).length())
        assertEquals(1, calls.size)
        runBlocking { search.search(lat, lon, "different-key") }; assertEquals(2, calls.size)
        runBlocking { search.search(lat, lon, "different-key", forceRefresh = true) }; assertEquals(3, calls.size)
    }

    @Test fun httpFailuresStopImmediatelyAndErrorsDoNotExposeCredentials() {
        for (status in listOf(401, 403, 429, 503)) fixture(reply = { _, _ -> status to "error" }) { search, calls ->
            val error = assertThrows(OpenCellLookupException::class.java) { runBlocking { search.search(lat, lon, "private-key") } }
            assertEquals(status, error.httpStatus); assertEquals(1, calls.size)
            assertTrue("ERROR" in search.lastReport!!.limits)
            assertFalse(error.toString().contains("private-key"))
            assertFalse(search.lastReport!!.toJson().toString().contains("private-key"))
        }
    }

    @Test fun http200ApiErrorIsNotMistakenForEmptyCoverage() = fixture(reply = { _, _ ->
        200 to """{"error":"message could echo a private key","code":7}"""
    }) { search, calls ->
        val error = assertThrows(OpenCellLookupException::class.java) { runBlocking { search.search(lat, lon, "key") } }
        assertEquals(7, error.apiCode); assertEquals(1, calls.size)
        assertFalse(error.message!!.contains("message"))
    }

    @Test fun invalidProtocolDoesNotLookLikeAValidEmptyResponse() {
        for (body in listOf("not json", "{}", """{"count":0}""")) fixture(reply = { _, _ -> 200 to body }) { search, _ ->
            assertThrows(OpenCellLookupException::class.java) { runBlocking { search.search(lat, lon, "key") } }
            assertFalse(search.lastReport!!.complete)
        }
    }

    @Test fun emptyTokenDoesNotRequestAnythingOrInventCoverage() = fixture(reply = { _, _ -> error("network") }) { search, calls ->
        val result = runBlocking { search.search(lat, lon, "") }
        assertTrue(calls.isEmpty()); assertTrue(result.records.isEmpty()); assertFalse(result.report.complete)
        assertTrue("NOT_CONFIGURED" in result.report.limits)
    }

    @Test fun deadlineCancelsTheHttpCallAndReportsPartialSearch() {
        val cancelled = AtomicBoolean()
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.set(true) }
        }).addInterceptor { chain ->
            Thread.sleep(200)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(page().toResponseBody()).build()
        }.build()
        try {
            val result = runBlocking { OpenCellSearchClient(http, oneBox.copy(timeBudgetMillis = 40)).search(lat, lon, "key") }
            assertTrue("TIME_LIMIT" in result.report.limits); assertTrue(cancelled.get()); assertFalse(result.report.complete)
        } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }

    @Test fun callerCancellationPropagatesInsteadOfTriggeringAnotherSearch() {
        val started = CountDownLatch(1); val cancelled = AtomicBoolean()
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.set(true) }
        }).addInterceptor { chain ->
            started.countDown(); Thread.sleep(200)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(page().toResponseBody()).build()
        }.build()
        try {
            val search = OpenCellSearchClient(http, oneBox)
            runBlocking {
                val job = launch(Dispatchers.Default) { search.search(lat, lon, "key") }
                assertTrue(started.await(2, TimeUnit.SECONDS)); job.cancelAndJoin()
                assertTrue(job.isCancelled)
            }
            assertTrue(cancelled.get()); assertEquals(setOf("CANCELLED"), search.lastReport!!.limits)
        } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }

    @Test fun tokenValidationUsesOneSmallCountRequest() = fixture(reply = { request, _ ->
        assertEquals("/cell/getInAreaSize", request.url.encodedPath)
        200 to """{"count":0}"""
    }) { search, calls ->
        assertTrue(runBlocking { search.validateToken("key") }); assertEquals(1, calls.size)
        assertNull(search.lastReport)
    }
}
