package com.vincenthzr.locationspoofer.xposed.hooks.network

import org.junit.Assert.*
import org.junit.Test

class BluetoothScanRecordBuilderTest {
    @Test fun `observed complete AD bytes stay unchanged`() {
        val bytes = hexStringToByteArray("0201060809436f6465784254")
        assertArrayEquals(bytes, buildScanRecordBytes("ignored-name", "0201060809436f6465784254"))
    }

    @Test fun `missing or malformed payload creates no manufacturer or beacon data`() {
        for (hex in listOf("", "zz", "abc", "00112233445566778899aabbccddeeff", "4c000215")) {
            val bytes = buildScanRecordBytes("CodexBT", hex)
            assertArrayEquals(hexStringToByteArray("0201060809436f6465784254"), bytes)
        }
        assertArrayEquals(byteArrayOf(2, 1, 6), buildScanRecordBytes("", ""))
    }

    @Test fun `long UTF8 name is shortened without splitting a code point`() {
        val name = "测试😀蓝牙设备测试😀蓝牙设备"
        val bytes = buildScanRecordBytes(name, "")
        assertTrue(bytes.size <= 31)
        assertEquals(8, bytes[4].toInt())
        val actualName = bytes.copyOfRange(5, bytes.size).toString(Charsets.UTF_8)
        assertFalse(actualName.contains('\uFFFD'))
        assertTrue(name.startsWith(actualName))
        assertTrue(isValidAdStructure(bytes))
    }

    @Test(expected = IllegalArgumentException::class) fun `odd hexadecimal input is rejected`() {
        hexStringToByteArray("020")
    }
}
