package com.usharik.zyxelrouter

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {
    private val oui by lazy {
        val asset = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/assets/oui.dat").takeIf(File::isFile) ?: File(it, "src/main/assets/oui.dat") }
            .first { it.isFile }
        OuiDatabase { asset.inputStream() }.also { assertTrue(it.load()) }
    }

    @Test fun privateMacIsTheLocallyAdministeredBit() {
        assertTrue(isPrivateMac("DA:A1:19:5B:10:08"))
        assertTrue(isPrivateMac("02-00-5e-10-00-03"))
        assertFalse(isPrivateMac("00:12:F0:4A:21:01"))
        assertFalse("групповой адрес — не устройство", isPrivateMac("03:00:00:00:00:01"))
        assertFalse(isPrivateMac("not a mac"))
    }

    @Test fun vendorFromBuiltInRegistry() {
        assertEquals("Intel Corporate", oui.vendor("00:12:F0:4A:21:01"))
        assertEquals("LG Electronics", oui.vendor("04-6F-00-3A-10-04"))
        assertEquals("Apple", oui.vendor("000393aabbcc"))
        assertNull("частный адрес", oui.vendor("DA:A1:19:5B:10:08"))
        assertNull(oui.vendor("garbage"))
    }

    @Test fun longestPrefixWins() {
        // Блок MA-S внутри MA-L самого IEEE: производитель — владелец маленького блока.
        assertEquals("DATA ELECTRONIC DEVICES", oui.vendor("8C:1F:64:AF:A0:01"))
    }

    @Test fun unloadedDatabaseIsSilent() {
        val broken = OuiDatabase { throw java.io.IOException("нет файла") }
        assertNull(broken.vendor("00:12:F0:4A:21:01"))
    }

    private fun device(name: String, mac: String, type: DeviceType = DeviceType.UNKNOWN) =
        offlineDevice(mac, name, type, "Wi-Fi 5GHz", 0)

    @Test fun displayNameUsesWhatTheDeviceSaid() {
        val unnamed = device("", "DA:A1:19:5B:10:08")
        assertEquals("Anna's iPhone", displayName(unnamed, NetworkIdentity(name = "Anna's iPhone", hostName = "Annas-iPhone")))
        assertEquals("Annas-iPhone", displayName(unnamed, NetworkIdentity(hostName = "Annas-iPhone")))
        assertEquals("LG webOS TV", displayName(unnamed, NetworkIdentity(manufacturer = "LG", model = "webOS TV")))
        assertEquals("имя от роутера важнее", "kitchen", displayName(device("kitchen", "00:12:F0:4A:21:01"), NetworkIdentity(name = "Other")))
    }

    @Test fun typeFromServicesModelAndVendor() {
        val d = device("", "00:12:F0:4A:21:01")
        assertEquals(DeviceType.PRINTER, refinedType(d, NetworkIdentity(services = setOf(NetService.PRINTER)), null))
        assertEquals(DeviceType.TV, refinedType(d, NetworkIdentity(services = setOf(NetService.CAST)), null))
        assertEquals(DeviceType.PHONE, refinedType(d, NetworkIdentity(model = "iPhone"), null))
        assertEquals(DeviceType.COMPUTER, refinedType(d, NetworkIdentity(model = "MacBook Pro"), null))
        assertEquals(DeviceType.COMPUTER, refinedType(d, NetworkIdentity(hostName = "DESKTOP-1", workgroup = "WORKGROUP"), null))
        assertEquals(DeviceType.IOT, refinedType(d, null, "Espressif"))
        assertEquals(DeviceType.UNKNOWN, refinedType(d, null, "Intel Corporate"))
        assertEquals("роутер уже знает тип", DeviceType.PHONE, refinedType(device("", "00:12:F0:4A:21:01", DeviceType.PHONE), NetworkIdentity(services = setOf(NetService.PRINTER)), null))
    }

    @Test fun identitiesSurviveRestart() {
        val map = mapOf("AA:BB:CC:DD:EE:FF" to NetworkIdentity("TV", "lgtv", "LG", "OLED", setOf(NetService.MEDIA_PLAYER, NetService.AIRPLAY), ""))
        assertEquals(map, identitiesFromJson(identitiesToJson(map)))
        assertEquals(emptyMap<String, NetworkIdentity>(), identitiesFromJson("{broken"))
    }

    @Test fun appleModelCodes() {
        assertEquals("MacBook Pro", appleModelName("MacBookPro18,3"))
        assertEquals("HomePod", appleModelName("AudioAccessory5,1"))
        assertEquals("Apple TV", appleModelName("AppleTV11,1"))
        assertNull(appleModelName("Chromecast"))
    }
}

/** Ответы mDNS, SSDP и NetBIOS — собранные вручную пакеты, как от настоящих устройств. */
class LocalDiscoveryTest {
    /** Пакет DNS: записи (имя, тип, данные). Имена пишутся целиком, без сжатия, кроме проверки сжатия. */
    private class Dns {
        private val out = ByteArrayOutputStream()
        private val records = mutableListOf<ByteArray>()
        fun name(n: String) = ByteArrayOutputStream().apply { n.split('.').filter { it.isNotEmpty() }.forEach { val b = it.toByteArray(); write(b.size); write(b) }; write(0) }.toByteArray()
        fun rr(owner: String, type: Int, data: ByteArray) {
            records += ByteArrayOutputStream().apply {
                write(name(owner)); write(byteArrayOf(0, type.toByte(), 0x80.toByte(), 1, 0, 0, 0x11, 0x94.toByte(), (data.size shr 8).toByte(), data.size.toByte())); write(data)
            }.toByteArray()
        }
        fun ptr(owner: String, target: String) = rr(owner, 12, name(target))
        fun srv(owner: String, target: String) = rr(owner, 33, byteArrayOf(0, 0, 0, 0, 0x1F, 0x49) + name(target))
        fun txt(owner: String, vararg kv: String) = rr(owner, 16, ByteArrayOutputStream().apply { kv.forEach { val b = it.toByteArray(); write(b.size); write(b) } }.toByteArray())
        fun a(owner: String, ip: String) = rr(owner, 1, ip.split('.').map { it.toInt().toByte() }.toByteArray())
        fun bytes(): ByteArray {
            out.write(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, records.size.toByte(), 0, 0, 0, 0))
            records.forEach(out::write)
            return out.toByteArray()
        }
    }

    @Test fun queryAsksForEveryTypeWithUnicastReply() {
        val q = mdnsQuery(listOf("_googlecast._tcp", "_ipp._tcp"))
        assertEquals(2, q[5].toInt())
        // Вопросы разбираются нашим же разборщиком как пакет без записей.
        assertTrue(parseDns(q).isEmpty())
        assertEquals("бит QU", 0x80, q[q.size - 2].toInt() and 0xFF)
    }

    @Test fun chromecastAnswer() {
        val p = Dns().apply {
            ptr("_googlecast._tcp.local", "Chromecast-1a2b._googlecast._tcp.local")
            srv("Chromecast-1a2b._googlecast._tcp.local", "1a2b3c.local")
            txt("Chromecast-1a2b._googlecast._tcp.local", "id=1a2b", "md=Google Nest Mini", "fn=Кухня")
            a("1a2b3c.local", "192.168.1.70")
        }.bytes()
        val (ids, _) = identitiesFromMdns(parseDns(p), "192.168.1.70")
        val id = ids.getValue("192.168.1.70")
        assertEquals("Кухня", id.name)
        assertEquals("Google Nest Mini", id.model)
        assertEquals("1a2b3c", id.hostName)
        assertEquals(setOf(NetService.CAST), id.services)
    }

    @Test fun appleDeviceInfoAndSleepProxy() {
        // Ответ пришёл от Apple TV (спящий Mac отвечает через неё) — адрес берётся из записи A.
        val p = Dns().apply {
            ptr("_device-info._tcp.local", "MacBook Анны._device-info._tcp.local")
            txt("MacBook Анны._device-info._tcp.local", "model=MacBookPro18,3", "osxvers=24")
            ptr("_companion-link._tcp.local", "MacBook Анны._companion-link._tcp.local")
            srv("MacBook Анны._companion-link._tcp.local", "Annas-MacBook.local")
            a("Annas-MacBook.local", "192.168.1.52")
        }.bytes()
        val ids = identitiesFromMdns(parseDns(p), "192.168.1.20").first
        val mac = ids.getValue("192.168.1.52")
        assertEquals("MacBook Анны", mac.name)
        assertEquals("Apple", mac.manufacturer)
        assertEquals("MacBook Pro", mac.model)
        assertEquals("Annas-MacBook", mac.hostName)
    }

    @Test fun printerAndServiceList() {
        val p = Dns().apply {
            ptr("_services._dns-sd._udp.local", "_ipp._tcp.local")
            ptr("_services._dns-sd._udp.local", "_matter._tcp.local")
            ptr("_ipp._tcp.local", "Brother HL-L2350DW._ipp._tcp.local")
            txt("Brother HL-L2350DW._ipp._tcp.local", "ty=Brother HL-L2350DW series", "usb_MFG=Brother")
        }.bytes()
        val (ids, types) = identitiesFromMdns(parseDns(p), "192.168.1.80")
        assertEquals(setOf("_ipp", "_matter"), types)
        val printer = ids.getValue("192.168.1.80")
        assertEquals("Brother", printer.manufacturer)
        assertEquals("Brother HL-L2350DW series", printer.model)
        assertTrue(NetService.PRINTER in printer.services)
    }

    @Test fun brokenPacketsAreIgnored() {
        // Указатель сжатия на самого себя и обрезанный пакет: пусто, без исключений и зацикливания.
        val loop = byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0, 0xC0.toByte(), 12, 0, 12, 0, 1, 0, 0, 0, 0, 0, 0)
        assertTrue(parseDns(loop).isEmpty())
        val ok = Dns().apply { a("tv.local", "192.168.1.70") }.bytes()
        assertTrue(parseDns(ok.copyOf(ok.size - 3)).isEmpty())
        assertTrue(parseDns(ByteArray(5)).isEmpty())
    }

    @Test fun compressedNamesAreFollowed() {
        val p = Dns().apply { a("tv.local", "192.168.1.70") }.bytes()
        // Второй ответ ссылается на имя первого (смещение 12).
        val second = byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 120, 0, 4, 192.toByte(), 168.toByte(), 1, 71)
        val packet = p.copyOf().also { it[7] = 2 } + second
        val records = parseDns(packet)
        assertEquals(listOf("192.168.1.70", "192.168.1.71"), records.map { it.address })
        assertEquals(listOf("tv", "local"), records[1].name)
    }

    @Test fun ssdpHeadersAndSafeDescriptionUrl() {
        val h = parseSsdpResponse("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.1.70:1400/xml/device_description.xml\r\nST: upnp:rootdevice\r\n\r\n")!!
        assertEquals("upnp:rootdevice", h["st"])
        assertEquals("/xml/device_description.xml", descriptionUrl(h["location"], "192.168.1.70")!!.path)
        assertNull("описание чужого адреса не запрашиваем", descriptionUrl(h["location"], "192.168.1.71"))
        assertNull(descriptionUrl("http://203.0.113.5/desc.xml", "203.0.113.5"))
        assertNull(descriptionUrl("file:///etc/hosts", "192.168.1.70"))
        assertNull(parseSsdpResponse("M-SEARCH * HTTP/1.1\r\n\r\n"))
    }

    @Test fun upnpDescription() {
        val xml = """<?xml version="1.0"?><root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>[TV] Гостиная &amp; кухня</friendlyName><manufacturer>Samsung Electronics</manufacturer>
            <modelName>UE55AU7100</modelName><modelNumber>AU7100</modelNumber>
            <deviceList><device><deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType><friendlyName>inner</friendlyName></device></deviceList>
            </device></root>"""
        val id = parseUpnpDescription(xml)
        assertEquals("[TV] Гостиная & кухня", id.name)
        assertEquals("Samsung Electronics", id.manufacturer)
        assertEquals("модельный номер уже в названии", "UE55AU7100", id.model)
        assertEquals(setOf(NetService.MEDIA_PLAYER, NetService.MEDIA_SERVER), id.services)
    }

    @Test fun netbiosNodeStatus() {
        val q = netbiosStatusQuery(0x4E01)
        assertEquals(50, q.size)
        val names = listOf(Triple("DESKTOP-7Q2", 0x00, 0x04), Triple("WORKGROUP", 0x00, 0x84), Triple("DESKTOP-7Q2", 0x20, 0x04))
        val body = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x4E, 0x01, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0))
            write(32); write(("CK" + "A".repeat(30)).toByteArray()); write(0)
            write(byteArrayOf(0, 0x21, 0, 1, 0, 0, 0, 0))
            val rd = 1 + names.size * 18 + 46
            write(byteArrayOf((rd shr 8).toByte(), rd.toByte(), names.size.toByte()))
            names.forEach { (n, suffix, flags) -> write(n.padEnd(15).toByteArray()); write(suffix); write(flags); write(0) }
            write(ByteArray(46))
        }.toByteArray()
        val id = parseNetbiosStatus(body)!!
        assertEquals("DESKTOP-7Q2", id.hostName)
        assertEquals("WORKGROUP", id.workgroup)
        assertNull("наш же запрос — не ответ", parseNetbiosStatus(q))
    }

    @Test fun answersMergeByAddress() {
        val merged = mergeIdentities(
            mapOf("192.168.1.70" to NetworkIdentity(name = "TV", services = setOf(NetService.AIRPLAY))),
            mapOf("192.168.1.70" to NetworkIdentity(manufacturer = "LG", services = setOf(NetService.MEDIA_PLAYER)), "192.168.1.5" to NetworkIdentity()),
        )
        assertEquals(setOf("192.168.1.70"), merged.keys)
        assertEquals(NetworkIdentity(name = "TV", manufacturer = "LG", services = setOf(NetService.AIRPLAY, NetService.MEDIA_PLAYER)), merged["192.168.1.70"])
    }

    /** Ручная проверка в настоящей сети: ZX_LAN=1 и адреса для NetBIOS в ZX_LAN_IPS через запятую. */
    @Test fun realLan() {
        org.junit.Assume.assumeTrue(System.getenv("ZX_LAN") != null)
        val ips = System.getenv("ZX_LAN_IPS").orEmpty().split(',').filter { it.isNotBlank() }
        val engine = DiscoveryEngine()
        val started = System.currentTimeMillis()
        val result = kotlinx.coroutines.runBlocking { engine.discover(ips) }
        println("LAN ${System.currentTimeMillis() - started} ms, answered ${result.identities.size}")
        result.identities.toSortedMap().forEach { (ip, id) -> println("LAN $ip $id") }
    }

    /** Мини-сервер на 127.0.0.1: [respond] пишет ответ в сокет. */
    private fun server(respond: (java.io.OutputStream) -> Unit): java.net.ServerSocket {
        val server = java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())
        Thread {
            runCatching {
                server.accept().use { c ->
                    c.getInputStream().bufferedReader().let { r -> while (r.readLine()?.isNotEmpty() == true) Unit }
                    respond(c.getOutputStream())
                }
            }
        }.apply { isDaemon = true }.start()
        return server
    }

    @Test fun httpGetReadsDescription() {
        server { it.write("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\n\r\n<root><friendlyName>TV</friendlyName></root>".toByteArray()) }.use { srv ->
            val body = httpGet(java.net.URL("http://127.0.0.1:${srv.localPort}/desc.xml"), System.currentTimeMillis() + 2_000)
            assertEquals("TV", parseUpnpDescription(body!!).name)
        }
        server { it.write("HTTP/1.1 404 Not Found\r\n\r\nnope".toByteArray()) }.use { srv ->
            assertNull(httpGet(java.net.URL("http://127.0.0.1:${srv.localPort}/"), System.currentTimeMillis() + 2_000))
        }
        assertNull("только http", httpGet(java.net.URL("https://127.0.0.1/"), System.currentTimeMillis() + 2_000))
    }

    @Test fun slowDeviceCannotHoldDiscovery() {
        // Отдаёт по байту каждые 100 мс бесконечно: общий срок обрывает чтение.
        server { out -> out.write("HTTP/1.1 200 OK\r\n\r\n".toByteArray()); repeat(1_000) { out.write('x'.code); out.flush(); Thread.sleep(100) } }.use { srv ->
            val started = System.currentTimeMillis()
            assertNull(httpGet(java.net.URL("http://127.0.0.1:${srv.localPort}/"), started + 700))
            assertTrue("уложились в срок", System.currentTimeMillis() - started < 1_500)
        }
    }

    @Test fun invalidCharacterReferencesAreDropped() {
        val id = parseUpnpDescription("<friendlyName>TV&#1114112;&#2000000000;&#65;</friendlyName><manufacturer>LG</manufacturer>")
        assertEquals("TVA", id.name)
        assertEquals("LG", id.manufacturer)
    }

    @Test fun answersAreCleanedAndBounded() {
        val merged = mergeIdentities(mapOf("192.168.1.9" to NetworkIdentity(name = "x".repeat(10_000), model = "A\u0000B\nC")))
        val id = merged.getValue("192.168.1.9")
        assertEquals(80, id.name.length)
        assertEquals("ABC", id.model)
    }
}
