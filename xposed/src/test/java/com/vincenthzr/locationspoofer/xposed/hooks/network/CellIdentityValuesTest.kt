package com.vincenthzr.locationspoofer.xposed.hooks.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CellIdentityValuesTest {
    @Test fun preservesNrAbove32Bits() {
        assertEquals(4_294_967_297L, CellIdentityValues.nrIdentity(JSONObject("""{"nci":4294967297}""")))
        assertEquals(68_719_476_735L, CellIdentityValues.nrIdentity(JSONObject("""{"nci":"68719476735"}""")))
    }

    @Test fun rejectsOutOfRangeAndFractionalNci() {
        for (value in listOf("-1", "68719476736", "4294967297.5", "null", "\"not-an-id\"")) {
            assertNull(CellIdentityValues.nrIdentity(JSONObject("""{"nci":$value}""")))
        }
    }

    @Test fun preservesZeroTacAndMncInsteadOfUsingLegacyFallbacks() {
        val cell = JSONObject("""{"tac":0,"lac":321,"mnc":0,"net":11}""")
        assertEquals(0, CellIdentityValues.area(cell, "NR"))
        assertEquals(0, CellIdentityValues.mnc(cell))
        assertEquals(321, CellIdentityValues.area(cell, "GSM"))
    }

    @Test fun invalidExplicitNciCannotFallBackToPlaceholderCi() {
        assertNull(CellIdentityValues.nrIdentity(JSONObject("""{"nci":68719476736,"ci":0}""")))
    }

    @Test fun respectsNr24BitAreaAndLte16BitArea() {
        val cell = JSONObject("""{"tac":16777215}""")
        assertEquals(16_777_215, CellIdentityValues.area(cell, "NR"))
        assertNull(CellIdentityValues.area(cell, "LTE"))
    }

    @Test fun readsLegacyFullWidthCellidAndSkipsUnavailableValues() {
        assertEquals(68_719_476_735L, CellIdentityValues.nrIdentity(JSONObject("""{"cellid":68719476735}""")))
        assertEquals(17, CellIdentityValues.area(JSONObject("""{"tac":2147483647,"area":17}"""), "LTE"))
    }
}
