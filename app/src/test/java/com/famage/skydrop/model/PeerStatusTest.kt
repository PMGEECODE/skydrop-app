package com.famage.skydrop.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerStatusTest {

    @Test
    fun testPeerStatus_ToDevice() {
        val status = PeerStatus(
            id = "192.168.1.10:8443",
            name = "Test Laptop",
            host = "192.168.1.10",
            port = 8443,
            isTls = true,
            fingerprint = "AA:BB:CC",
            isPaired = true,
            isConnected = true,
            authToken = "secret-token-123"
        )

        assertEquals("https://192.168.1.10:8443", status.baseUrl)
        assertTrue(status.isPaired)
        assertTrue(status.isConnected)

        val device = status.toDevice()
        assertEquals(status.id, device.id)
        assertEquals(status.name, device.name)
        assertEquals(status.host, device.host)
        assertEquals(status.port, device.port)
        assertEquals(status.isTls, device.isTls)
        assertEquals(status.fingerprint, device.fingerprint)
    }

    @Test
    fun testPeerStatus_DisconnectedState() {
        val status = PeerStatus(
            id = "offline-peer-1",
            name = "Offline Laptop",
            host = "offline",
            port = 0,
            isTls = false,
            isPaired = true,
            isConnected = false
        )

        assertTrue(status.isPaired)
        assertFalse(status.isConnected)
        assertEquals("http://offline:0", status.baseUrl)
    }
}
