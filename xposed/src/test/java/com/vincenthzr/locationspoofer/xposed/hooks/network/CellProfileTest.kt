package com.vincenthzr.locationspoofer.xposed.hooks.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CellProfileTest {
    @Test fun wideNrIdentitiesAndZeroFieldsSurviveProjectionWithoutSourceMutation() {
        val config=JSONObject("""{"cell_json":[
            {"type":"NR","mcc":460,"mnc":0,"tac":0,"nci":4294967297,"dbm":-80},
            {"type":"NR","mcc":460,"mnc":0,"tac":0,"nci":68719476735,"dbm":-90}]}""")
        val before=config.toString();val visible=CellProfile.cells(config)
        assertEquals(2,visible.size);assertEquals(1,visible.count { it.optBoolean("isRegistered") })
        assertNotEquals(CellProfile.identity(visible[0]),CellProfile.identity(visible[1]))
        assertEquals(before,config.toString());assertEquals(20,CellProfile.network(config))
    }
    @Test fun missingAndInvalidIdentitiesCannotCreateFallbackTowers() {
        val config=JSONObject("""{"cell_json":[{"type":"LTE","mcc":460,"mnc":0,"tac":0},
            {"type":"NR","mcc":460,"mnc":0,"tac":0,"nci":68719476736}]}""")
        assertTrue(CellProfile.cells(config).isEmpty());assertEquals(0,CellProfile.network(config))
    }
    @Test fun legacyRadioFieldAndExplicitZeroIdentityRemainValid() {
        val config=JSONObject("""{"cell_json":[{"radio":"LTE","mcc":460,"mnc":0,"tac":0,"ci":0}]}""")
        assertEquals(1,CellProfile.cells(config).size);assertEquals(13,CellProfile.network(config))
    }
}
