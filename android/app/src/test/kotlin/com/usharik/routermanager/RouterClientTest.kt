package com.usharik.routermanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterClientTest {
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }
    private fun arr(items: List<JSONObject>) = JSONArray().apply { items.forEach { put(it) } }

    private fun sampleStatus(): JSONObject = obj(
        "DeviceInfo" to obj("ModelName" to "VMG", "SoftwareVersion" to "V1", "UpTime" to 100),
        "WanLanInfo" to arr(listOf(obj(
            "Status" to "Up", "LinkType" to "PTM", "Encapsulation" to "PPPoE", "DefaultGateway" to true,
            "IPv4Address" to arr(listOf(obj("IPAddress" to "203.0.113.7"))),
            "dnsv4Server" to arr(listOf(obj("DNSServer" to "1.1.1.1"))), "pppoeConnectionUpTime" to 60,
        ))),
        "DslChannelInfo" to arr(listOf(obj("Status" to "Up", "DownstreamCurrRate" to 98304, "UpstreamCurrRate" to 20480))),
        "WiFiInfo" to arr(listOf(
            obj("SSID" to "Home", "Enable" to 1, "Channel" to 6, "OperatingStandards" to "b,g,n", "ModeEnabled" to "WPA2-Personal", "wifiPassword" to "SECRET24"),
            obj("SSID" to "Guest", "Enable" to 0, "Channel" to 6, "OperatingStandards" to "b,g,n", "ModeEnabled" to "WPA2-Personal", "wifiPassword" to "SECRETG"),
            obj("SSID" to "Home-5G", "Enable" to true, "Channel" to 44, "OperatingStandards" to "a,n,ac", "ModeEnabled" to "WPA2-Personal", "wifiPassword" to "SECRET5"),
            obj("SSID" to "Guest-5G", "Enable" to false, "Channel" to 44, "OperatingStandards" to "a,n,ac", "ModeEnabled" to "None", "wifiPassword" to ""),
        )),
    )

    private fun sampleHosts(): JSONObject = obj("lanhosts" to arr(listOf(
        obj("Active" to true, "curHostName" to "mac", "PhysAddress" to "14:33:75:00:00:01", "IPAddress" to "192.168.1.52", "X_ZYXEL_ConnectionType" to "Wi-Fi 5GHz", "Layer1Interface" to "Device.WiFi.SSID.5"),
        obj("Active" to 1, "HostName" to "tv", "PhysAddress" to "14:33:75:00:00:02", "IPAddress" to "192.168.1.60", "X_ZYXEL_ConnectionType" to "Ethernet", "Layer1Interface" to "Device.Ethernet.Interface.1"),
        obj("Active" to 1, "DeviceName" to "phone", "PhysAddress" to "14:33:75:00:00:03", "IPAddress" to "192.168.2.33", "X_ZYXEL_ConnectionType" to "Wi-Fi 2.4GHz", "Layer1Interface" to "Device.WiFi.SSID.2"),
    )))

    @Test fun networkNameMapping() {
        val s = parseSnapshot(sampleStatus(), sampleHosts())
        val byName = s.devices.associateBy { it.name }
        assertEquals("Home-5G", byName["mac"]?.network)
        assertEquals("проводное устройство без SSID", "", byName["tv"]?.network)
        assertNull(bandOfConnection(byName["tv"]!!.connection))
        assertEquals("Guest", byName["phone"]?.network)
        assertTrue(byName["phone"]?.guest == true)
        assertEquals("203.0.113.7", s.wanIP)
        assertTrue(s.internetUp)
    }

    @Test fun wifiBandFromStandards() {
        val s = parseSnapshot(sampleStatus(), sampleHosts())
        assertEquals(WifiBand.GHZ_5, s.wifi.first { it.ssid == "Home-5G" }.band)
        assertEquals(WifiBand.GHZ_2_4, s.wifi.first { it.ssid == "Home" }.band)
    }

    @Test fun wifiBandPrefersFrequencyField() {
        // Wi-Fi 6: «ax» есть и у 2,4 ГГц, поэтому решает OperatingFrequencyBand.
        val ax24 = JSONObject().put("OperatingFrequencyBand", "2.4GHz").put("OperatingStandards", "b,g,n,ax")
        assertEquals(WifiBand.GHZ_2_4, wifiBand(ax24, 0, 4))
        assertEquals(WifiBand.GHZ_5, wifiBand(JSONObject().put("OperatingFrequencyBand", "5GHz").put("OperatingStandards", "a,n,ac,ax"), 0, 4))
        assertEquals("без поля — по стандартам", WifiBand.GHZ_2_4, wifiBand(JSONObject().put("OperatingStandards", "b,g,n,ax"), 3, 4))
        assertEquals("только ax — по позиции", WifiBand.GHZ_5, wifiBand(JSONObject().put("OperatingStandards", "ax"), 3, 4))
    }

    @Test fun guestNetworkFromMainSsidFlag() {
        assertFalse(isGuestNetwork(JSONObject().put("X_ZYXEL_MainSSID", true), 1))
        assertTrue(isGuestNetwork(JSONObject().put("X_ZYXEL_MainSSID", false), 0))
        assertTrue("без флага — по позиции", isGuestNetwork(JSONObject(), 1))
    }

    @Test fun sessionPinsCommitOnlyNewFingerprint() {
        val stored = MemoryCertificatePins()
        val session = SessionPins(stored)
        session.put("192.168.1.1", "AA")
        assertEquals("клиент видит отпечаток до входа", "AA", session.get("192.168.1.1"))
        assertNull("но не сохраняет его", stored.get("192.168.1.1"))
        session.commit()
        assertEquals("AA", stored.get("192.168.1.1"))
        stored.put("192.168.1.1", "BB")
        SessionPins(stored).apply { put("192.168.1.1", "CC"); commit() }
        assertEquals("закреплённый отпечаток не перезаписывается", "BB", stored.get("192.168.1.1"))
    }

    @Test fun hostOfAddress() {
        assertEquals("192.168.1.1", RouterClient.hostOf(" 192.168.1.1:8443 "))
    }

    @Test fun addressIsOnlyIpAndPort() {
        assertEquals("192.168.1.1" to null, RouterClient.parseAddress("192.168.1.1"))
        assertEquals("10.0.0.1" to 8443, RouterClient.parseAddress(" 10.0.0.1:8443 "))
        // Всё, что увело бы запрос на другой хост, отвергается ещё до соединения.
        for (bad in listOf("192.168.1.1:80@evil.com", "192.168.1.1/x", "https://192.168.1.1", "192.168.1.1:0", "192.168.1.1:70000", "router.local", "")) {
            assertNull(bad, RouterClient.parseAddress(bad))
            assertThrows(bad, RouterException.BadAddress::class.java) { RouterClient(bad) }
        }
        assertThrows(RouterException.BadAddress::class.java) { RouterClient("8.8.8.8") }
    }

    @Test fun trustedPinsCommitAfterLoginOnly() {
        val stored = MemoryCertificatePins().apply { put("192.168.1.1", "OLD") }
        val trusted = TrustedPins(stored, "192.168.1.1", "NEW")
        val session = SessionPins(trusted)
        assertEquals("клиент сверяет с новым отпечатком", "NEW", session.get("192.168.1.1"))
        assertEquals("до входа хранилище не меняется", "OLD", stored.get("192.168.1.1"))
        session.commit()
        assertEquals("NEW", stored.get("192.168.1.1"))
    }

    @Test fun timeUsesLatinDigitsInAnyLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            for (tag in listOf("fa-IR", "ar-EG", "th-TH-u-nu-thai", "hi-IN-u-nu-deva")) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
                assertEquals(tag, "09:00", normalizeTime("9:00"))
                assertEquals(tag, "22:00-24:00", profileTimeRange("22:00", "0:00"))
            }
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test fun diagnosticsHidesValues() {
        val s = parseSnapshot(sampleStatus(), sampleHosts())
        assertFalse("пароль Wi-Fi утёк", s.schema.contains("SECRET24"))
        assertFalse("IP утёк", s.schema.contains("203.0.113.7"))
        assertTrue(s.schema.contains("wifiPassword"))
    }

    @Test fun emptyPayloads() {
        val s = parseSnapshot(JSONObject(), JSONObject())
        assertEquals(0, s.devices.size)
        assertEquals(0, s.wifi.size)
        assertFalse(s.internetUp)
    }

    @Test fun parentalParsing() {
        val pc = parseParental(listOf(
            obj("PrentalCtlEnable" to true, "MaxLenPrentalCtlPrf" to 20),
            obj("Index" to 1, "pcpEnable" to true, "pcpName" to "Дети", "MACAddressList" to "14:33:75:00:00:03",
                "Days" to "Mon,Tue,Fri", "TimeRangeList" to "21:00-23:59", "URLFilter" to "tiktok.com", "NetworkServiceList" to ""),
        ))
        assertTrue(pc.enabled)
        assertEquals(20, pc.maxProfiles)
        assertEquals(1, pc.profiles.size)
        assertEquals(listOf("Mon", "Tue", "Fri"), pc.profiles[0].days)
        assertEquals(listOf("14-33-75-00-00-03"), pc.profiles[0].macs)
        assertTrue(pc.profiles[0].blocksSites)
    }

    @Test fun routingDistinctAndUniqueIds() {
        val r = parseRouting(
            listOf(obj("Index" to 0, "Enable" to true, "Name" to "office", "ipver" to "IPv4", "DestIPAddress" to "10.0.0.0", "DestSubnetMask" to "255.255.255.0", "GatewayIPAddress" to "192.168.1.2")),
            listOf(obj("Index" to 1, "Enable" to true, "DomainName" to "work.example.com")),
            listOf(obj("Index" to 0, "Enable" to true, "RouteName" to "vpn", "SourceIpAddress" to "192.168.1.60", "Protocol" to "ALL")),
            listOf(obj("Interface" to "Device.IP.Interface.1", "Version" to "RIPv2", "RipOpMode" to "Passive", "Enable" to false)),
        )
        assertEquals("office", r.staticRoutes[0].title)
        assertEquals("work.example.com", r.dnsRoutes[0].title)
        assertEquals("vpn", r.policyRoutes[0].title)
        assertEquals(RouteDetail.Rip("1", "RIPv2", "Passive"), r.rip[0].detail)
        assertEquals(RouteDetail.Static("10.0.0.0 / 255.255.255.0", "192.168.1.2"), r.staticRoutes[0].detail)
        assertEquals(RouteDetail.Policy("192.168.1.60", false, ""), r.policyRoutes[0].detail)
        val ids = (r.staticRoutes + r.dnsRoutes + r.policyRoutes + r.rip).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun timeNormalizeAndMinutes() {
        assertEquals("09:00", normalizeTime("9:00"))
        assertEquals("24:00", normalizeTime("24:00"))
        assertNull(normalizeTime("24:01"))
        assertNull(normalizeTime("9:60"))
        assertNull(normalizeTime("25:00"))
        assertNull(normalizeTime("09"))
        assertNull(normalizeTime("ab:cd"))
        // Главный баг строкового сравнения: 9:00 раньше 10:00 по минутам.
        assertTrue(minutesOfTime("9:00")!! < minutesOfTime("10:00")!!)
        assertTrue(minutesOfTime("09:30")!! < minutesOfTime("09:45")!!)
    }

    @Test fun privateIpv4() {
        assertTrue(RouterClient.isPrivateIPv4("192.168.1.1"))
        assertTrue(RouterClient.isPrivateIPv4("10.0.0.1"))
        assertTrue(RouterClient.isPrivateIPv4("172.16.5.5"))
        assertFalse(RouterClient.isPrivateIPv4("8.8.8.8"))
        assertFalse(RouterClient.isPrivateIPv4("172.32.0.1"))
        assertFalse(RouterClient.isPrivateIPv4("1.2.3"))
        assertFalse(RouterClient.isPrivateIPv4(""))
        // Ведущий ноль часть систем читает как восьмеричное: 010.0.0.1 — это 8.0.0.1, публичный адрес.
        assertFalse(RouterClient.isPrivateIPv4("010.0.0.1"))
        assertFalse(RouterClient.isPrivateIPv4("172.016.0.1"))
        assertNull(RouterClient.parseAddress("010.0.0.1"))
        assertNull(RouterClient.parseAddress("192.168.01.1"))
        assertNull(RouterClient.parseAddress("192.168.1.1:0"))
        assertEquals("192.168.0.1" to null, RouterClient.parseAddress("192.168.0.1"))
        assertTrue("loopback — только в отладочной сборке (мок-роутер)", RouterClient.isPrivateIPv4("127.0.0.1") == BuildConfig.DEBUG)
    }
    @Test fun deviceTypeInference() {
        assertEquals(DeviceType.PHONE, deviceType("android-dhcp-13", ""))
        assertEquals(DeviceType.PHONE, deviceType("HUAWEI:android:QC_Reference_Phone", ""))
        assertEquals(DeviceType.PRINTER, deviceType("XEROX Network Printer", ""))
        assertEquals(DeviceType.COMPUTER, deviceType("MSFT 5.0", ""))
        assertEquals(DeviceType.TV, deviceType("atvbox-4403-czsk", ""))
        assertEquals(DeviceType.IOT, deviceType("udhcp 1.36.0", ""))
        assertEquals(DeviceType.COMPUTER, deviceType("", "MBP-aleks"))
        assertEquals(DeviceType.PHONE, deviceType("", "iPhone"))
        assertEquals(DeviceType.IOT, deviceType("", "xiaomi_vacuum"))
        assertEquals(DeviceType.UNKNOWN, deviceType("*", "неведомое"))
    }

    @Test fun logParseAndAggregate() {
        val json = org.json.JSONArray().put(org.json.JSONObject().put("result", org.json.JSONArray(listOf(
            "[dhcpd] Nov  8 16:54:14 daemon.info dnsmasq-dhcp: lease added",
            "[tr69] Nov  8 10:00:00 daemon.err cwmp: connection failed",
            "[dhcpc] Nov  7 09:00:00 daemon.debug udhcpc: renew",
            "[kernel] Oct 31 23:00:00 kern.warning kernel: oops",
            "",
        )))).toString()
        val e = parseLog(json)
        assertEquals(4, e.size)
        assertEquals("dhcpd", e[0].category)
        assertEquals("info", e[0].severity)
        assertEquals(11, e[0].month)
        assertEquals(8, e[0].day)
        assertEquals("err", e[1].severity)
        assertEquals("connection failed", e[1].message)
        val byDay = logAggregate(e, LogPeriod.DAY)
        assertEquals(3, byDay.size)
        assertEquals(2, byDay[0].count)
        val byMonth = logAggregate(e, LogPeriod.MONTH)
        assertEquals(11, byMonth[0].month)
        assertEquals(LogPeriod.MONTH, byMonth[0].period)
        assertEquals(3, byMonth[0].count)
        assertEquals(2, byMonth.size)
        assertEquals(4, logCountsBy(e) { it.severity }.sumOf { it.count })
    }

    @Test fun macNormalize() {
        assertEquals("AA:BB:CC:DD:EE:FF", normalizeMAC("aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", normalizeMAC("AA-BB-CC-DD-EE-FF"))
        assertNull(normalizeMAC("zz:bb:cc:dd:ee:ff"))
        assertNull(normalizeMAC("aa:bb:cc:dd:ee"))
        assertEquals("без разделителей", "AA:BB:CC:DD:EE:FF", normalizeMAC("aabbccddeeff"))
        assertEquals("формат Cisco", "AA:BB:CC:DD:EE:FF", normalizeMAC("aabb.ccdd.eeff"))
        assertEquals("пробелы из буфера", "AA:BB:CC:DD:EE:FF", normalizeMAC("  aa:bb:cc:dd:ee:ff \n"))
        assertNull("разделители вперемешку", normalizeMAC("aa:bbcc:dd:ee:ff"))
        assertNull(normalizeMAC("aabbccddeeff00"))
        assertNull(normalizeMAC(""))
    }

    @Test fun offlineDeviceFactory() {
        val off = offlineDevice("AA:BB:CC:DD:EE:01", "PC", DeviceType.COMPUTER, "Ethernet", 123)
        assertFalse(off.online)
        assertEquals("AA:BB:CC:DD:EE:01", off.mac)
        assertEquals("PC", off.name)
        assertEquals("", off.network)
        assertEquals(123L, off.lastSeen)
        val w = offlineDevice("AA:BB:CC:DD:EE:02", "", DeviceType.UNKNOWN, "Wi-Fi 5GHz", 0)
        assertEquals("AA:BB:CC:DD:EE:02", w.name)
        assertEquals(WifiBand.GHZ_5, bandOfConnection(w.connection))
    }

    @Test fun bytesFormat() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KB", formatBytes(1536))
        val ru = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        assertEquals("1,7 ГБ", formatBytes(1788431209, ru, java.util.Locale.forLanguageTag("ru")))
        assertEquals("1,5 kB", formatBytes(1536, listOf("B", "kB", "MB", "GB", "TB"), java.util.Locale.forLanguageTag("cs")))
    }

    @Test fun legacyDeviceTypesFromHistory() {
        assertEquals(DeviceType.PRINTER, DeviceType.parse("Принтер"))
        assertEquals(DeviceType.PHONE, DeviceType.parse("Телефон или планшет"))
        assertEquals(DeviceType.TV, DeviceType.parse("TV"))
        assertEquals(DeviceType.UNKNOWN, DeviceType.parse("Неизвестно"))
        assertEquals(DeviceType.UNKNOWN, DeviceType.parse(""))
    }
}

class RealFixtureTest {
    private fun load(name: String): JSONObject? {
        val stream = javaClass.classLoader?.getResourceAsStream("real/$name.json") ?: return null
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }
    private fun firstObject(d: JSONObject) = d.getJSONArray("Object").getJSONObject(0)

    @Test fun realSnapshotParses() {
        val card = load("cardpage_status"); val lan = load("lanhosts")
        org.junit.Assume.assumeTrue("нет фикстур", card != null && lan != null)
        val s = parseSnapshot(firstObject(card!!), firstObject(lan!!))
        assertTrue("интернет Up (VDSL), LAN Default/None не мешает", s.internetUp)
        assertTrue(s.wanIP.isNotEmpty())
        assertTrue("устройства разобраны", s.devices.size > 5)
        assertTrue(s.wifi.any { it.band == WifiBand.GHZ_5 } && s.wifi.any { it.band == WifiBand.GHZ_2_4 })
        assertFalse("значения не попадают в диагностику", s.schema.contains("198.51.100") || s.schema.contains("14:33:75"))
    }

    @Test fun fixturesHoldNoPersonalData() {
        val sensitive = Regex("serial|passw|passphrase|psk|ploam|provisioningkey|wepkey|firstusedate|loginbanner|^location$|^domainname$", RegexOption.IGNORE_CASE)
        val publicIpv6 = Regex("^(?!fe80|2001:db8|::)[0-9a-f]{1,4}:[0-9a-f:]*(/\\d+)?$", RegexOption.IGNORE_CASE)
        val leaks = mutableListOf<String>()
        fun walk(v: Any?, key: String) {
            when (v) {
                is JSONObject -> v.keys().forEach { walk(v.get(it), it) }
                is JSONArray -> (0 until v.length()).forEach { walk(v.get(it), key) }
                is String -> if (v.isNotEmpty() && v != "<redacted>") {
                    if (sensitive.containsMatchIn(key)) leaks += key
                    if (v.count { it == ':' } != 5 && publicIpv6.matches(v)) leaks += key
                }
            }
        }
        listOf("cardpage_status", "lanhosts", "paren_ctl_with_profile").forEach { name -> load(name)?.let { walk(it, "") } }
        assertEquals("в фикстурах остались личные данные", emptyList<String>(), leaks)
    }

    @Test fun realParentalFormat() {
        val pc = load("paren_ctl_with_profile")
        org.junit.Assume.assumeTrue("нет фикстуры", pc != null)
        val objs = pc!!.getJSONArray("Object").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val parsed = parseParental(objs)
        assertEquals(12, parsed.maxProfiles)
        assertEquals(listOf("Mon"), parsed.profiles[0].days)   // Days приходит строкой "Mon"
        assertEquals(listOf("09:00-10:00"), parsed.profiles[0].times)
        assertTrue(parsed.profiles[0].macs[0].contains("-"))
    }
    @Test fun realDeviceExtras() {
        val card = load("cardpage_status"); val lan = load("lanhosts")
        org.junit.Assume.assumeTrue(card != null && lan != null)
        val s = parseSnapshot(firstObject(card!!), firstObject(lan!!))
        assertTrue("реальный трафик: принятые байты", s.devices.any { it.bytesDown > 0 })
        assertTrue("реальный трафик: отправленные байты", s.devices.any { it.bytesUp > 0 })
        // ТВ-приставка почти только скачивает: роутер отправил ей 3,9 ГБ, а получил от неё 81 МБ.
        val tvBox = s.devices.single { it.vendorClass.startsWith("atvbox") }
        assertEquals(3_919_238_647L, tvBox.bytesDown)
        assertEquals(81_247_759L, tvBox.bytesUp)
        assertTrue("сигнал 1..5", s.devices.any { it.signal in 1..5 })
        assertTrue("все поля заполнены", s.devices.all { it.details.isNotEmpty() })
        assertTrue("распознан принтер", s.devices.any { it.type == DeviceType.PRINTER })
        assertTrue("распознан Android", s.devices.any { it.type == DeviceType.PHONE })
    }
}
