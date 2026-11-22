package com.usharik.routermanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHistoryTest {
    private fun device(mac: String, online: Boolean, name: String = mac) =
        offlineDevice(mac, name, DeviceType.PHONE, "Wi-Fi 5GHz", 0).copy(online = online)

    @Test fun remembersDevicesTheRouterForgot() {
        val h = DeviceHistory(null)
        val saved = h.record(listOf(device("AA", true, "Phone"), device("BB", true, "Laptop")), now = 1000)
        val restored = DeviceHistory(saved)
        val merged = restored.merge(listOf(device("AA", true)))
        assertEquals(listOf("AA", "BB"), merged.map { it.mac })
        val laptop = merged.last()
        assertEquals("Laptop", laptop.name)
        assertEquals(false, laptop.online)
        assertEquals(1000L, laptop.lastSeen)
    }

    @Test fun offlineDeviceKeepsLastSeenTime() {
        val h = DeviceHistory(null)
        h.record(listOf(device("AA", true)), now = 1000)
        h.record(listOf(device("AA", false)), now = 5000)
        assertEquals("роутер ещё перечисляет его, но видели его в 1000", 1000L, h.merge(listOf(device("AA", false))).single().lastSeen)
    }

    @Test fun forgetAndLimit() {
        val h = DeviceHistory(null, limit = 2)
        h.record(listOf(device("A", true)), now = 1)
        h.record(listOf(device("B", true)), now = 2)
        h.record(listOf(device("C", true)), now = 3)
        assertEquals("самое старое вытеснено", setOf("B", "C"), h.merge(emptyList()).map { it.mac }.toSet())
        assertTrue(h.forget("B") != null)
        assertNull("повторно забывать нечего", h.forget("B"))
        assertEquals(listOf("C"), h.merge(emptyList()).map { it.mac })
    }

    @Test fun ignoresDevicesWithoutMacAndBrokenStorage() {
        assertNull(DeviceHistory("not json").record(listOf(device("", true)), now = 1))
        assertEquals(emptyList<RouterDevice>(), DeviceHistory("[{\"mac\":\"\"}]").merge(emptyList()))
    }
}
