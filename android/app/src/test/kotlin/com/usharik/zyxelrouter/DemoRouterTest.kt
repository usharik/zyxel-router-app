package com.usharik.zyxelrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Демо-режим видят проверяющие Google Play: каждый экран должен быть заполнен, а изменения — работать. */
class DemoRouterTest {
    private val now = 1_763_000_000_000L   // ноябрь 2025
    private val demo = DemoRouter { now }

    @Test fun snapshotFillsOverviewDevicesAndWifi() {
        val s = demo.snapshot()
        assertTrue(s.internetUp)
        assertEquals(DemoRouter.MODEL, s.model)
        assertTrue(s.devices.count { it.online } >= 5)
        assertTrue("есть офлайн-устройство", s.devices.any { !it.online })
        assertEquals(4, s.wifi.size)
        assertEquals("гостевые по X_ZYXEL_MainSSID", listOf(false, true, false, true), s.wifi.map { it.guest })
        assertEquals(listOf(WifiBand.GHZ_2_4, WifiBand.GHZ_2_4, WifiBand.GHZ_5, WifiBand.GHZ_5), s.wifi.map { it.band })
        assertEquals("в демо не показывается схема диагностики", "", s.schema)
    }

    @Test fun everyNetworkCanBeShared() {
        for (w in demo.snapshot().wifi) {
            val c = demo.wifiCredentials(w)
            assertEquals(w.ssid, c.ssid)
            assertTrue(wifiQrPayload(c)!!.startsWith("WIFI:T:"))
        }
    }

    @Test fun parentalChangesStayInMemory() {
        val before = demo.parentalControl()
        assertTrue(before.enabled)
        demo.saveProfile(null, "New", listOf("02:00:5E:10:00:01"), listOf("Sat"), "10:00-12:00", true)
        val created = demo.parentalControl().profiles.last()
        assertEquals("New", created.name)
        assertEquals(listOf("02-00-5E-10-00-01"), created.macs)
        demo.setProfileEnabled(created.index, false)
        assertFalse(demo.parentalControl().profiles.last().enabled)
        demo.saveProfile(created.index, "Renamed", created.macs.map { it.replace("-", ":") }, listOf("Sun"), "00:00-24:00", true)
        assertEquals(listOf("00:00-24:00"), demo.parentalControl().profiles.last().times)
        demo.deleteProfile(created.index)
        assertEquals(before.profiles.size, demo.parentalControl().profiles.size)
        demo.setParentalEnabled(false)
        assertFalse(demo.parentalControl().enabled)
    }

    @Test(expected = RouterException.ProfileNotFound::class)
    fun deletingUnknownProfileFails() = demo.deleteProfile(99)

    @Test fun routingHasEveryGroup() {
        val r = demo.routing()
        assertTrue(r.staticRoutes.isNotEmpty() && r.dnsRoutes.isNotEmpty() && r.policyRoutes.isNotEmpty() && r.rip.isNotEmpty())
    }

    @Test fun logCoversRecentDays() {
        val log = demo.systemLog()
        assertTrue(log.size > 40)
        val days = logAggregate(log, LogPeriod.DAY)
        assertTrue("статистика по дням не пустая", days.size >= 7)
        assertTrue(log.any { severityGroup(it.severity) == "err" } && log.any { severityGroup(it.severity) == "warning" })
    }
}
