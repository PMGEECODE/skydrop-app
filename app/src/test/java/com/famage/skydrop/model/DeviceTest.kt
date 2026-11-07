package com.famage.skydrop.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTest {

    @Test
    fun testDevice_BaseUrl_Tls() {
        val device = Device(
            id = "192.168.1.100:8443",
            name = "Linux Laptop",
            host = "192.168.1.100",
            port = 8443,
            isTls = true,
            fingerprint = "AA:BB:CC"
        )

        assertEquals("https://192.168.1.100:8443", device.baseUrl)
        assertEquals("AA:BB:CC", device.fingerprint)
        assertTrue(device.isTls)
    }

    @Test
    fun testDevice_BaseUrl_PlainHttp() {
        val device = Device(
            id = "192.168.1.50:8080",
            name = "Plain Device",
            host = "192.168.1.50",
            port = 8080,
            isTls = false
        )

        assertEquals("http://192.168.1.50:8080", device.baseUrl)
    }
}
