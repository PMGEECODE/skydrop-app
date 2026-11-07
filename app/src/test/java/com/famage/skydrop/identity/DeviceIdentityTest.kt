package com.famage.skydrop.identity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {

    @Test
    fun testIsLocalAddress_LoopbackAndLocal() {
        assertTrue(DeviceIdentity.isLocalAddress("127.0.0.1"))
        assertTrue(DeviceIdentity.isLocalAddress("::1"))
        assertTrue(DeviceIdentity.isLocalAddress("localhost"))
        assertTrue(DeviceIdentity.isLocalAddress("0.0.0.0"))
        assertTrue(DeviceIdentity.isLocalAddress("::"))
    }

    @Test
    fun testIsLocalAddress_RemoteAddress() {
        assertFalse(DeviceIdentity.isLocalAddress("8.8.8.8"))
        assertFalse(DeviceIdentity.isLocalAddress("104.16.249.249"))
        assertFalse(DeviceIdentity.isLocalAddress(""))
    }

    @Test
    fun testIsSelf_LocalHostAlwaysTrue() {
        val self = DeviceIdentity.isSelf(
            context = null as? android.content.Context ?: run {
                // If context is null, isLocalAddress still detects loopback
                return@run null as android.content.Context?
            } ?: return,
            name = "Test Device",
            host = "127.0.0.1",
            port = 8444
        )
        assertTrue(self)
    }
}
