package com.famage.skydrop.security

import com.famage.skydrop.model.PairedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingManagerTest {

    @Test
    fun testParsePairingUri_Valid() {
        val rawUri = "skydrop://pair?device=LinuxLaptop&host=192.168.74.19&fingerprint=AA%3ABB%3ACC%3ADD&port=8443&secret=123456"
        val data = PairingManager.parsePairingUri(rawUri)

        assertNotNull(data)
        assertEquals("LinuxLaptop", data?.deviceName)
        assertEquals("192.168.74.19", data?.host)
        assertEquals("AA:BB:CC:DD", data?.fingerprint)
        assertEquals(8443, data?.port)
        assertEquals("123456", data?.secret)
    }

    @Test
    fun testParsePairingUri_InvalidScheme() {
        val rawUri = "https://example.com/pair?device=LinuxLaptop"
        val data = PairingManager.parsePairingUri(rawUri)
        assertNull(data)
    }

    @Test
    fun testPairedDevice_Properties() {
        val paired = PairedDevice(
            id = "peer-1",
            name = "Linux Laptop",
            host = "192.168.1.50",
            port = 8443,
            isTls = true,
            fingerprint = "AA:BB:CC",
            authToken = "token-secret-123456"
        )

        assertEquals("peer-1", paired.id)
        assertEquals("Linux Laptop", paired.name)
        assertEquals("192.168.1.50", paired.host)
        assertEquals(8443, paired.port)
        assertTrue(paired.isTls)
        assertEquals("token-secret-123456", paired.authToken)
    }
}
