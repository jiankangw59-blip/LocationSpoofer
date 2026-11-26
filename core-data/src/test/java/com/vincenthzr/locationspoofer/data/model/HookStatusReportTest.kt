package com.vincenthzr.locationspoofer.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookStatusReportTest {

    private val sample = """
        {"format":1,"process":"SYSTEM_SERVER","updated_at":1000,
         "vendor":{"id":"hyperos","family":"HYPEROS_MIUI","manual":false},
         "system":"HyperOS 4.0 · Android 17","model":"2509FPN0BC",
         "components":[
           {"name":"WIFI_SERVICE","found":true,"searched":true,"class":"com.android.server.wifi.WifiServiceImpl",
            "source":"thread:WifiHandlerThread","methods":{"getScanResults":1,"getConnectionInfo":1}},
           {"name":"CONNECTIVITY_SERVICE","found":true,"searched":true,"class":"com.android.server.ConnectivityService",
            "source":"classLoader","methods":{"getNetworkCapabilities":1,"getActiveNetworkInfo":0}},
           {"name":"APPOPS_SERVICE","found":true,"searched":true,"class":"x.AppOps","source":"classLoader","methods":{"checkOperation":0}},
           {"name":"TELEPHONY_REGISTRY","found":false,"searched":true},
           {"name":"LOCATION_PROVIDER_MANAGER","found":false,"searched":false}
         ],
         "errors":["hookSystemWifiService: NullPointerException: boom"]}
    """.trimIndent()

    @Test
    fun `parses a report and classifies each component`() {
        val report = HookStatusReport.parse(sample)!!
        assertEquals("SYSTEM_SERVER", report.process)
        assertEquals("hyperos", report.vendorId)
        val states = report.components.associate { it.name to it.state }
        assertEquals(HookStatusReport.State.HOOKED, states["WIFI_SERVICE"])
        assertEquals(HookStatusReport.State.HOOKED, states["CONNECTIVITY_SERVICE"]) // 个别备用方法名 ×0 不影响
        assertEquals(HookStatusReport.State.NO_METHODS, states["APPOPS_SERVICE"])
        assertEquals(HookStatusReport.State.NOT_FOUND, states["TELEPHONY_REGISTRY"])
        assertEquals(HookStatusReport.State.PENDING, states["LOCATION_PROVIDER_MANAGER"])
        assertEquals(2, report.hookedCount)
        assertEquals(1, report.errors.size)
        assertTrue(report.isStale(bootTimeMs = 2000))
    }

    @Test
    fun `root command failures are not reports`() {
        assertNull(HookStatusReport.parse("ERROR"))
        assertNull(HookStatusReport.parse("SUCCESS"))
        assertNull(HookStatusReport.parse(""))
    }

    @Test
    fun `distinguishes a different snapshot with the same timestamp from the current one`() {
        val current = HookStatusReport.parse("""{"process":"SYSTEM_SERVER","pid":11,
            "config":{"snapshot_id":"current","modified":1000}}""")!!
        val old = current.copy(process = "PHONE", pid = 12, snapshotId = "previous")
        val legacy = HookStatusReport.parse("""{"process":"BLUETOOTH","pid":13,
            "config":{"modified":1000}}""")
        val check = HookConfigCheck.compare("current", "current", mapOf("SYSTEM_SERVER" to 11, "PHONE" to 12, "BLUETOOTH" to 13),
            mapOf("SYSTEM_SERVER" to current, "PHONE" to old, "BLUETOOTH" to legacy))
        assertEquals(HookConfigCheck.State.COMPARED, check.state)
        assertEquals(HookConfigCheck.HostState.MATCHED, check.hosts["SYSTEM_SERVER"])
        assertEquals(HookConfigCheck.HostState.DIFFERENT_SNAPSHOT, check.hosts["PHONE"])
        assertEquals(HookConfigCheck.HostState.NO_SNAPSHOT_ID, check.hosts["BLUETOOTH"])
    }

    @Test
    fun `a matching snapshot in a report from an old process is not confirmation`() {
        val old = HookStatusReport.parse("""{"process":"SYSTEM_SERVER","pid":11,"config":{"snapshot_id":"current"}}""")!!
        val check = HookConfigCheck.compare("current", "current", mapOf("SYSTEM_SERVER" to 99, "PHONE" to 12),
            mapOf("SYSTEM_SERVER" to old))
        assertEquals(HookConfigCheck.HostState.OLD_PROCESS, check.hosts["SYSTEM_SERVER"])
        assertEquals(HookConfigCheck.HostState.NO_REPORT, check.hosts["PHONE"])
        assertEquals(HookConfigCheck.HostState.NO_RUNNING_TARGET, check.hosts["BLUETOOTH"])
    }

    @Test
    fun `checks require a live target list and a stable framework snapshot`() {
        val changing = HookConfigCheck.compare("first", "second", emptyMap(), emptyMap())
        assertEquals(HookConfigCheck.State.CHANGING, changing.state)
        assertTrue(changing.hosts.isEmpty())
        val unavailable = HookConfigCheck.compare("current", "current", null, emptyMap())
        assertEquals(HookConfigCheck.State.UNAVAILABLE, unavailable.state)
        assertTrue(unavailable.hosts.isEmpty())
    }
}
