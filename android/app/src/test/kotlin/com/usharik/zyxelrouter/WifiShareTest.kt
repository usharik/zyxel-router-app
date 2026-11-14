package com.usharik.zyxelrouter

import com.google.zxing.qrcode.decoder.Decoder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WifiShareTest {
    private fun status(vararg nets: Pair<String, String>, password: String = "secret pass") = JSONObject().put(
        "WiFiInfo", JSONArray(nets.map { (ssid, mode) -> JSONObject().put("SSID", ssid).put("ModeEnabled", mode).put("wifiPassword", password) }),
    )

    @Test fun securityModes() {
        assertEquals(WifiSecurity.OPEN, WifiSecurity.parse("None"))
        assertEquals(WifiSecurity.OPEN, WifiSecurity.parse(""))
        assertEquals(WifiSecurity.WEP, WifiSecurity.parse("WEP-128"))
        assertEquals(WifiSecurity.WPA, WifiSecurity.parse("WPA2-Personal"))
        assertEquals(WifiSecurity.WPA, WifiSecurity.parse("WPA-WPA2-Personal"))
        assertEquals(WifiSecurity.WPA, WifiSecurity.parse("WPA2-WPA3-Personal"))
        assertEquals(WifiSecurity.WPA3, WifiSecurity.parse("WPA3-Personal"))
        assertEquals(WifiSecurity.ENTERPRISE, WifiSecurity.parse("WPA2-Enterprise"))
    }

    @Test fun payloadEscapesSpecialCharacters() {
        val c = WifiCredentials("""My;Net, "5G"""", WifiSecurity.WPA, """p:a\ss;""")
        assertEquals("""WIFI:T:WPA;S:My\;Net\, \"5G\";P:p\:a\\ss\;;;""", wifiQrPayload(c))
    }

    @Test fun openAndUnsupportedNetworks() {
        assertEquals("WIFI:T:nopass;S:Cafe;;", wifiQrPayload(WifiCredentials("Cafe", WifiSecurity.OPEN, "")))
        assertEquals("WIFI:T:SAE;S:N;P:12345678;;", wifiQrPayload(WifiCredentials("N", WifiSecurity.WPA3, "12345678")))
        assertNull(wifiQrPayload(WifiCredentials("Corp", WifiSecurity.ENTERPRISE, "")))
    }

    @Test fun credentialsByIndexAndName() {
        val s = status("Home" to "WPA2-Personal", "Guest" to "None")
        assertEquals(WifiCredentials("Home", WifiSecurity.WPA, "secret pass"), parseWifiCredentials(s, 0, "Home"))
        assertEquals("у открытой сети пароль не берётся", "", parseWifiCredentials(s, 1, "Guest")?.password)
        assertNull("сеть переименовали — чужой пароль не показываем", parseWifiCredentials(s, 0, "Guest"))
        assertNull(parseWifiCredentials(s, 5, "Home"))
        assertNull("защищённая сеть без пароля в ответе", parseWifiCredentials(status("Home" to "WPA2-Personal", password = ""), 0, "Home"))
    }

    @Test fun passwordNotInToString() {
        assertFalse(WifiCredentials("Home", WifiSecurity.WPA, "hunter22").toString().contains("hunter22"))
    }

    @Test fun qrRoundTrip() {
        val payload = wifiQrPayload(WifiCredentials("Дом 5G", WifiSecurity.WPA, "пароль;1"))!!
        assertEquals(payload, Decoder().decode(qrMatrix(payload)).text)
    }

    @Test fun realFixtureNetworksShareable() {
        val stream = javaClass.classLoader?.getResourceAsStream("real/cardpage_status.json") ?: return
        val status = JSONObject(stream.bufferedReader().use { it.readText() }).getJSONArray("Object").getJSONObject(0)
        val snapshotWifi = parseSnapshot(status, JSONObject()).wifi
        for (w in snapshotWifi) {
            val c = parseWifiCredentials(status, w.index, w.ssid)
            assertEquals(w.ssid, c?.ssid)
            assertEquals("<redacted>", c?.password)
        }
    }

    @Test fun htmlEntitiesDecodedLikeSnapshot() {
        // Прошивка экранирует & и < в SSID и пароле; снимок раскодирует их, значит и пароль должен.
        val status = JSONObject().put("WiFiInfo", JSONArray().put(
            JSONObject().put("SSID", "Tom&amp;Jerry").put("ModeEnabled", "WPA2-Personal").put("wifiPassword", "a&amp;b&lt;c"),
        ))
        val ssid = parseSnapshot(status, JSONObject()).wifi.single().ssid
        assertEquals("Tom&Jerry", ssid)
        assertEquals("a&b<c", parseWifiCredentials(status, 0, ssid)?.password)
    }
}
