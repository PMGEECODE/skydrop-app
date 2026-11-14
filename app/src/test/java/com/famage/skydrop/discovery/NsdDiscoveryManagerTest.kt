package com.famage.skydrop.discovery

import android.content.Context
import com.famage.skydrop.model.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class NsdDiscoveryManagerTest {

    @Test
    fun testSupportedServiceTypes() {
        assertTrue(NsdDiscoveryManager.SUPPORTED_SERVICE_TYPES.contains("_filedrop._tcp"))
        assertTrue(NsdDiscoveryManager.SUPPORTED_SERVICE_TYPES.contains("_skydrop._tcp"))
        assertEquals("_filedrop._tcp", NsdDiscoveryManager.SERVICE_TYPE)
    }

    @Test
    fun testAddManualDeviceFiltersLocalAddress() {
        // Create a mock context or test manual device logic directly
        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = null
        }
        val manager = NsdDiscoveryManager(mockContext)

        // Adding loopback address should be rejected
        manager.addManualDevice("127.0.0.1", 8443, "Local Self")
        assertTrue(manager.devices.value.isEmpty())

        manager.addManualDevice("localhost", 8443, "Local Self")
        assertTrue(manager.devices.value.isEmpty())

        // Adding external remote IP should succeed
        manager.addManualDevice("192.168.1.150", 8443, "Remote Laptop", isTls = true)
        val devList = manager.devices.value
        assertEquals(1, devList.size)
        val dev = devList[0]
        assertEquals("Remote Laptop", dev.name)
        assertEquals("192.168.1.150", dev.host)
        assertEquals(8443, dev.port)
        assertTrue(dev.isTls)

        // Adding duplicate or updating device
        manager.addManualDevice("192.168.1.150", 8443, "Remote Laptop Updated", isTls = true)
        assertEquals(1, manager.devices.value.size)
        assertEquals("Remote Laptop Updated", manager.devices.value[0].name)
    }

    @Test
    fun testStopDiscoveryClearsStateSafely() {
        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = null
        }
        val manager = NsdDiscoveryManager(mockContext)

        // stopDiscovery when not started should not throw
        manager.stopDiscovery()

        // startDiscovery with null NsdManager should degrade safely
        manager.startDiscovery()
        manager.stopDiscovery()
    }
}
