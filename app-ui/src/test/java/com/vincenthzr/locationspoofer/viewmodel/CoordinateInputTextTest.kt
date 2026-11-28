package com.vincenthzr.locationspoofer.viewmodel

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateInputTextTest {
    // Tests the exact text accepted by the existing start/lookup paths, not a translated label.
    private fun verifyWithFormatLocale(locale: Locale) {
        val old = Locale.getDefault(Locale.Category.FORMAT)
        try {
            Locale.setDefault(Locale.Category.FORMAT, locale)
            for ((degrees, text) in listOf(
                12.3456789 to "12.345679", -0.125 to "-0.125000",
                0.0 to "0.000000", -90.0 to "-90.000000", 180.0 to "180.000000"
            )) {
                val input = coordinateInputText(degrees)
                assertEquals(text, input)
                assertEquals(text.toDouble(), input.toDoubleOrNull())
            }
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, old)
        }
    }

    @Test fun frenchMapPointCanStart() = verifyWithFormatLocale(Locale.FRANCE)
    @Test fun arabicCoordinatesCanBeParsed() = verifyWithFormatLocale(Locale.forLanguageTag("ar-EG"))
    @Test fun englishPrecisionUnchanged() = verifyWithFormatLocale(Locale.US)
}
