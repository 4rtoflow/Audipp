package com.audipp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidVersionTest {
    @Test
    fun showsOsReleaseAndApiLevelSeparately() {
        assertEquals("Android 6.0 (API 23)", formatAndroidVersion("6.0", 23))
    }

    @Test
    fun trimsVendorReleaseWhitespace() {
        assertEquals("Android 16 (API 36)", formatAndroidVersion(" 16 ", 36))
    }

    @Test
    fun missingReleaseDoesNotInventAnOsVersion() {
        assertEquals("Android (API 23)", formatAndroidVersion(" ", 23))
    }
}
