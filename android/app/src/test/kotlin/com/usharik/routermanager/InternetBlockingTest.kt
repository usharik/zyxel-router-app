package com.usharik.routermanager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Блокировка интернета флагом `Internet_Blocking_Enable` через PUT `oid=lanhosts`, как в веб-интерфейсе роутера. */
class InternetBlockingTest {
    private fun realHosts(): List<JSONObject> {
        val text = javaClass.classLoader!!.getResourceAsStream("real/lanhosts.json")!!.bufferedReader().readText()
        return JSONObject(text).getJSONArray("Object").getJSONObject(0).items("lanhosts")
    }

    @Test fun bodyMatchesWebInterface() {
        val host = realHosts().first()
        val body = internetBlockingBody(host, true)
        assertEquals("ровно поля веб-интерфейса", setOf("HostName", "DeviceIcon", "MacAddress", "Internet_Blocking_Enable"), body.keys().asSequence().toSet())
        assertEquals(host.getString("PhysAddress"), body.getString("MacAddress"))
        assertEquals("пустой curHostName — имя из DeviceName", host.getString("DeviceName"), body.getString("HostName"))
        assertEquals("иконка из поля icon, а не из пустого DeviceIcon", "ipc", body.getString("DeviceIcon"))
        assertTrue(body.getBoolean("Internet_Blocking_Enable"))
        assertFalse(internetBlockingBody(host, false).getBoolean("Internet_Blocking_Enable"))
    }

    @Test fun everyRealHostKeepsNameAndIcon() {
        for (host in realHosts()) {
            val body = internetBlockingBody(host, true)
            val expected = listOf("curHostName", "DeviceName", "HostName").map { host.optString(it) }.firstOrNull { it.isNotEmpty() } ?: ""
            assertEquals(expected, body.getString("HostName"))
            assertTrue(body.getString("DeviceIcon").startsWith("i"))
        }
    }

    @Test fun customNameAndUnknownIcon() {
        val host = JSONObject().put("curHostName", "Kids tablet").put("DeviceName", "android-1").put("icon", "Weird").put("DeviceIcon", "")
            .put("PhysAddress", "02:00:5E:10:00:03")
        val body = internetBlockingBody(host, true)
        assertEquals("Kids tablet", body.getString("HostName"))
        assertEquals("iothers", body.getString("DeviceIcon"))
        assertEquals("imobile", internetBlockingBody(host.put("icon", "iMobile"), true).getString("DeviceIcon"))
        assertEquals("имя уходит как пришло, с сущностями", "MacBook &amp; Co", internetBlockingBody(host.put("curHostName", "MacBook &amp; Co"), true).getString("HostName"))
    }

    @Test fun demoRouterTogglesFlag() {
        val demo = DemoRouter()
        val phone = demo.snapshot().devices.first { it.name == "Phone" }
        assertFalse(phone.blocked)
        demo.setInternetBlocked(phone.mac.lowercase(), true)
        assertTrue(demo.snapshot().devices.first { it.mac == phone.mac }.blocked)
        demo.setInternetBlocked(phone.mac, false)
        assertFalse(demo.snapshot().devices.first { it.mac == phone.mac }.blocked)
        assertThrows(RouterException.DeviceNotFound::class.java) { demo.setInternetBlocked("AA:BB:CC:DD:EE:FF", true) }
    }
}
