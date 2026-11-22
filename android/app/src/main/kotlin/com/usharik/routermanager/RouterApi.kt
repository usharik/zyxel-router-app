package com.usharik.routermanager

import org.json.JSONArray
import org.json.JSONObject

/**
 * Операции с роутером, которые нужны экранам. Реализации: [RouterClient] (настоящий роутер по HTTPS) и [DemoRouter]
 * (встроенные данные без сети). Все методы блокирующие — вызывать из фонового диспетчера.
 */
/** Шаг входа, который показывает экран: связь (TLS и проверка сертификата), вход с паролем, первое чтение данных. */
enum class LoginStep { CONNECTING, SIGNING_IN, LOADING }

/** Сколько разделов читает [RouterApi.routing]: экран показывает «прочитано N из …». */
const val ROUTING_PARTS = 4

interface RouterApi {
    /** Растёт при каждом входе: параллельные восстановления сессии не входят повторно. */
    val sessionGeneration: Int
    fun basicInfo(): RouterBasic
    /** Вход; [onStep] сообщает, на каком шаге он сейчас (вызывается из потока запроса). */
    fun login(username: String, password: String, onStep: (LoginStep) -> Unit = {})
    /** Повторный вход, если с момента [generation] сессию ещё никто не восстановил. */
    fun reloginIfUnchanged(generation: Int, username: String, password: String)
    fun snapshot(): RouterSnapshot
    /** Имя, защита и пароль одной сети — только по просьбе пользователя поделиться ею. */
    fun wifiCredentials(network: WifiNetwork): WifiCredentials
    fun parentalControl(): ParentalControl
    fun setParentalEnabled(on: Boolean)
    fun setProfileEnabled(index: Int, on: Boolean)
    /** Создаёт (index == null) или изменяет профиль. */
    fun saveProfile(index: Int?, name: String, macs: List<String>, days: List<String>, timeRangeList: String, enabled: Boolean)
    fun deleteProfile(index: Int)
    /** Маршруты из [ROUTING_PARTS] разделов; [onProgress] — сколько уже прочитано. */
    fun routing(onProgress: (done: Int) -> Unit = {}): Routing
    fun systemLog(security: Boolean = false): List<LogEntry>
    /** Состояние линии DSL для учёта обрывов; null, если роутер его не отдаёт (не DSL, другая прошивка). */
    fun lineStats(): LineStats? = null
    fun reboot()
    /** Закрывает сессию на роутере и самого клиента. */
    fun logout()
    /** Закрывает клиента сразу, без сети: незавершённые запросы больше не входят заново. */
    fun close() {}
}

/**
 * Демо-режим: роутер со встроенными данными, без сети. Нужен проверяющим Google Play и тем, у кого роутера под рукой нет.
 * Данные проходят через те же разборщики, что и ответы настоящего роутера, поэтому экраны ведут себя так же.
 * Изменения родительского контроля живут в памяти до выхода из демо-режима.
 */
class DemoRouter(private val now: () -> Long = System::currentTimeMillis) : RouterApi {
    private val startedAt = now()
    private val status = JSONObject(DEMO_STATUS)
    private val hosts = JSONObject(DEMO_HOSTS)
    private val parental = JSONArray(DEMO_PARENTAL)
    override val sessionGeneration = 1

    override fun basicInfo() = RouterBasic(MODEL, FIRMWARE, "en")
    override fun login(username: String, password: String, onStep: (LoginStep) -> Unit) = Unit
    override fun reloginIfUnchanged(generation: Int, username: String, password: String) = Unit

    override fun snapshot(): RouterSnapshot {
        val elapsed = (now() - startedAt) / 1000
        val s = JSONObject(status.toString())
        s.getJSONObject("DeviceInfo").put("UpTime", 412_000 + elapsed)
        s.getJSONArray("WanLanInfo").getJSONObject(1).put("pppoeConnectionUpTime", DEMO_WAN_UPTIME + elapsed)
        return parseSnapshot(s, hosts).copy(schema = "")
    }

    override fun wifiCredentials(network: WifiNetwork): WifiCredentials =
        parseWifiCredentials(status, network.index, network.ssid) ?: throw RouterException.NoData("cardpage_status", 200)

    @Synchronized
    override fun parentalControl(): ParentalControl =
        parseParental((0 until parental.length()).map { JSONObject(parental.getJSONObject(it).toString()) })

    @Synchronized
    override fun setParentalEnabled(on: Boolean) { parental.getJSONObject(0).put("PrentalCtlEnable", on) }

    @Synchronized
    override fun setProfileEnabled(index: Int, on: Boolean) { profile(index).put("pcpEnable", on) }

    @Synchronized
    override fun saveProfile(index: Int?, name: String, macs: List<String>, days: List<String>, timeRangeList: String, enabled: Boolean) {
        val obj = if (index != null) profile(index) else JSONObject().also {
            val next = (1 until parental.length()).maxOfOrNull { i -> parental.getJSONObject(i).optInt("Index") }?.plus(1) ?: 1
            it.put("Index", next).put("URLFilter", "").put("NetworkServiceList", "")
            parental.put(it)
        }
        obj.put("pcpName", name).put("pcpEnable", enabled).put("MACAddressList", macs.joinToString(","))
            .put("Days", days.joinToString(",")).put("TimeRangeList", timeRangeList)
    }

    @Synchronized
    override fun deleteProfile(index: Int) {
        val position = (1 until parental.length()).firstOrNull { parental.getJSONObject(it).optInt("Index") == index }
            ?: throw RouterException.ProfileNotFound
        parental.remove(position)
    }

    override fun routing(onProgress: (done: Int) -> Unit): Routing {
        val r = JSONObject(DEMO_ROUTES)
        fun list(key: String) = r.getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        return parseRouting(list("static"), list("dns"), list("policy"), list("rip"))
    }

    /**
     * Журнал за последние три недели относительно сегодняшнего дня, чтобы статистика по дням не была пустой, и два
     * обрыва связи ([DEMO_DROPS]). Время считается от запуска демо, чтобы обрывы не «переезжали» между чтениями.
     */
    override fun systemLog(security: Boolean): List<LogEntry> {
        val format = java.text.SimpleDateFormat("MMM d HH:mm:ss", java.util.Locale.US)
        fun line(secondsAgo: Long, category: String, severity: String, message: String): Pair<Long, String> {
            val at = startedAt - secondsAgo * 1000
            return at to "[$category] ${format.format(java.util.Date(at))} $severity $message"
        }
        val background = DEMO_LOG.mapIndexed { i, (category, severity, message) -> line(i * 7 * 3600L + 1800, category, severity, message) }
        val drops = DEMO_DROPS.flatMap { (endedAgo, events) -> events.map { (before, category, severity, message) -> line(endedAgo + before, category, severity, message) } }
        val lines = JSONArray((background + drops).sortedBy { it.first }.map { it.second })
        return parseLog(JSONArray().put(JSONObject().put("result", lines)).toString())
    }

    /** Линия держит синхронизацию со времени старого обрыва из [DEMO_DROPS]: он показан как обрыв линии, свежий — как обрыв сессии. */
    override fun lineStats(): LineStats? {
        val elapsed = (now() - startedAt) / 1000
        return parseLineStats(DEMO_LINE.replace("@UPTIME@", "${DEMO_LINE_UPTIME + elapsed} secs"), now() / 1000)
    }

    override fun reboot() = Unit
    override fun logout() = Unit

    private fun profile(index: Int): JSONObject = (1 until parental.length()).map { parental.getJSONObject(it) }
        .firstOrNull { it.optInt("Index") == index } ?: throw RouterException.ProfileNotFound

    companion object {
        const val MODEL = "VMG8623-T50B"
        const val FIRMWARE = "Demo"
    }
}

private const val FIRMWARE_LINE = "V5.50(ABPM.7.3)C0"
/** Текущая сессия PPP началась, когда закончился свежий обрыв из [DEMO_DROPS]; линия DSL — когда закончился старый. */
private const val DEMO_WAN_UPTIME = 11_520L
private const val DEMO_LINE_UPTIME = 2 * 86_400L + 5_400

private val DEMO_STATUS = """
{
  "DeviceInfo": { "ModelName": "VMG8623-T50B", "SoftwareVersion": "$FIRMWARE_LINE", "UpTime": 412000 },
  "DslChannelInfo": [{ "Status": "Up", "DownstreamCurrRate": 98304, "UpstreamCurrRate": 20480 }],
  "WanLanInfo": [
    { "Status": "Up", "LinkType": "None", "X_ZYXEL_SrvName": "Default", "IPv4Address": [{ "IPAddress": "192.168.1.1" }] },
    { "Status": "Up", "LinkType": "PTM", "X_ZYXEL_SrvName": "Internet", "Encapsulation": "PPPoE", "DefaultGateway": true,
      "pppoeConnectionUpTime": $DEMO_WAN_UPTIME, "IPv4Address": [{ "IPAddress": "203.0.113.7" }], "dnsv4Server": [{ "DNSServer": "1.1.1.1" }] }
  ],
  "WiFiInfo": [
    { "SSID": "Home", "Enable": true, "Channel": 6, "OperatingFrequencyBand": "2.4GHz", "OperatingStandards": "b,g,n", "ModeEnabled": "WPA2-Personal", "X_ZYXEL_MainSSID": true, "wifiPassword": "demo-home-24" },
    { "SSID": "Home-Guest", "Enable": true, "Channel": 6, "OperatingFrequencyBand": "2.4GHz", "OperatingStandards": "b,g,n", "ModeEnabled": "WPA2-Personal", "X_ZYXEL_MainSSID": false, "wifiPassword": "welcome-guest" },
    { "SSID": "Home-5G", "Enable": true, "Channel": 44, "OperatingFrequencyBand": "5GHz", "OperatingStandards": "a,n,ac", "ModeEnabled": "WPA2-Personal", "X_ZYXEL_MainSSID": true, "wifiPassword": "demo-home-5g" },
    { "SSID": "Home-Guest-5G", "Enable": false, "Channel": 44, "OperatingFrequencyBand": "5GHz", "OperatingStandards": "a,n,ac", "ModeEnabled": "None", "X_ZYXEL_MainSSID": false, "wifiPassword": "" }
  ]
}
"""

private val DEMO_HOSTS = """
{ "lanhosts": [
  { "Active": true, "curHostName": "Laptop", "IPAddress": "192.168.1.52", "PhysAddress": "00:12:F0:4A:21:01", "X_ZYXEL_ConnectionType": "Wi-Fi 5GHz", "Layer1Interface": "Device.WiFi.SSID.3", "VendorClassID": "MSFT 5.0", "X_ZYXEL_SignalStrength": 4, "X_ZYXEL_SNR": 34, "X_ZYXEL_PhyRate": 585, "X_ZYXEL_OperatingStandard": "ac", "X_ZYXEL_BytesReceived": 106174729, "X_ZYXEL_BytesSent": 1788431209, "LeaseTimeRemaining": 64036 },
  { "Active": true, "curHostName": "Phone", "IPAddress": "192.168.1.60", "PhysAddress": "00:07:AB:5C:10:02", "X_ZYXEL_ConnectionType": "Wi-Fi 5GHz", "Layer1Interface": "Device.WiFi.SSID.3", "VendorClassID": "android-dhcp-14", "X_ZYXEL_SignalStrength": 5, "X_ZYXEL_SNR": 38, "X_ZYXEL_PhyRate": 866, "X_ZYXEL_OperatingStandard": "ac", "X_ZYXEL_BytesReceived": 52428800, "X_ZYXEL_BytesSent": 524288000, "Internet_Blocking_Enable": false },
  { "Active": true, "curHostName": "Kids tablet", "IPAddress": "192.168.1.61", "PhysAddress": "02:00:5E:10:00:03", "X_ZYXEL_ConnectionType": "Wi-Fi 2.4GHz", "Layer1Interface": "Device.WiFi.SSID.1", "VendorClassID": "android-dhcp-13", "X_ZYXEL_SignalStrength": 3, "X_ZYXEL_SNR": 25, "X_ZYXEL_PhyRate": 144, "X_ZYXEL_OperatingStandard": "n", "X_ZYXEL_BytesReceived": 7340032, "X_ZYXEL_BytesSent": 98304000, "Internet_Blocking_Enable": true },
  { "Active": true, "curHostName": "Living room TV", "IPAddress": "192.168.1.70", "PhysAddress": "04:6F:00:3A:10:04", "X_ZYXEL_ConnectionType": "Ethernet", "Layer1Interface": "Device.Ethernet.Interface.2", "VendorClassID": "SmartTV", "X_ZYXEL_BytesReceived": 268435456, "X_ZYXEL_BytesSent": 9663676416 },
  { "Active": true, "curHostName": "Printer", "IPAddress": "192.168.1.80", "PhysAddress": "30:05:5C:7E:10:05", "X_ZYXEL_ConnectionType": "Wi-Fi 2.4GHz", "Layer1Interface": "Device.WiFi.SSID.1", "VendorClassID": "Network Printer", "X_ZYXEL_SignalStrength": 3, "X_ZYXEL_SNR": 21, "X_ZYXEL_PhyRate": 54, "X_ZYXEL_OperatingStandard": "g", "X_ZYXEL_BytesReceived": 271612, "X_ZYXEL_BytesSent": 140293 },
  { "Active": true, "DeviceName": "Guest phone", "IPAddress": "192.168.2.33", "PhysAddress": "02:00:5E:10:00:06", "X_ZYXEL_ConnectionType": "Wi-Fi 2.4GHz", "Layer1Interface": "Device.WiFi.SSID.2", "VendorClassID": "android-dhcp-14", "X_ZYXEL_SignalStrength": 4, "X_ZYXEL_SNR": 29, "X_ZYXEL_PhyRate": 144, "X_ZYXEL_OperatingStandard": "n", "X_ZYXEL_BytesReceived": 1182148, "X_ZYXEL_BytesSent": 24398000 },
  { "Active": true, "curHostName": "", "HostName": "", "IPAddress": "192.168.1.90", "PhysAddress": "DA:A1:19:5B:10:08", "X_ZYXEL_ConnectionType": "Wi-Fi 5GHz", "Layer1Interface": "Device.WiFi.SSID.5", "VendorClassID": "*", "X_ZYXEL_SignalStrength": 4, "X_ZYXEL_SNR": 31, "X_ZYXEL_PhyRate": 650, "X_ZYXEL_OperatingStandard": "ac", "X_ZYXEL_BytesReceived": 734003200, "X_ZYXEL_BytesSent": 52428800 },
  { "Active": false, "curHostName": "Smart plug", "IPAddress": "", "PhysAddress": "00:4B:12:6C:10:07", "X_ZYXEL_ConnectionType": "Wi-Fi 2.4GHz", "Layer1Interface": "Device.WiFi.SSID.1", "VendorClassID": "esp32" }
] }
"""

private val DEMO_PARENTAL = """
[
  { "PrentalCtlEnable": true, "MaxLenPrentalCtlPrf": 12 },
  { "Index": 1, "pcpEnable": true, "pcpName": "Kids — school nights", "MACAddressList": "02:00:5E:10:00:03", "Days": "Mon,Tue,Wed,Thu,Sun", "TimeRangeList": "21:30-24:00", "NetworkServiceList": "", "URLFilter": "" },
  { "Index": 2, "pcpEnable": false, "pcpName": "TV at night", "MACAddressList": "04:6F:00:3A:10:04", "Days": "Mon,Tue,Wed,Thu,Fri,Sat,Sun", "TimeRangeList": "00:00-07:00", "NetworkServiceList": "", "URLFilter": "" }
]
"""

private val DEMO_ROUTES = """
{
  "static": [{ "Enable": true, "Name": "office", "ipver": "IPv4", "DestIPAddress": "10.20.0.0", "DestSubnetMask": "255.255.255.0", "GatewayIPAddress": "192.168.1.2" }],
  "dns": [{ "Enable": true, "DomainName": "work.example.com" }],
  "policy": [{ "Enable": false, "RouteName": "tablet-vpn", "SourceIpAddress": "192.168.1.61", "Protocol": "ALL", "SourceMacAddress": "" }],
  "rip": [{ "Interface": "VDSL", "Version": "RIPv2", "RipOpMode": "Passive", "Enable": false }]
}
"""

private val DEMO_LOG = listOf(
    Triple("dhcpd", "daemon.info", "dnsmasq-dhcp: lease added 192.168.1.61"),
    Triple("wifi", "daemon.notice", "hostapd: station joined Home-5G"),
    Triple("tr69", "daemon.err", "cwmp: connection to ACS failed"),
    Triple("dhcpc", "daemon.debug", "udhcpc: sending renew"),
    Triple("kernel", "kern.warning", "kernel: wlan0 rate limited"),
    Triple("firewall", "daemon.notice", "firewall: dropped inbound connection"),
    Triple("ppp", "daemon.info", "pppd: PPP session established"),
    Triple("dhcpd", "daemon.info", "dnsmasq-dhcp: lease renewed 192.168.1.52"),
    Triple("wifi", "daemon.notice", "hostapd: station left Home"),
    Triple("system", "user.info", "system: configuration saved"),
    Triple("firewall", "daemon.notice", "firewall: dropped inbound connection"),
    Triple("dhcpd", "daemon.info", "dnsmasq-dhcp: lease added 192.168.2.33"),
    Triple("kernel", "kern.err", "kernel: DSL line retrained"),
    Triple("ppp", "daemon.info", "pppd: PPP session established"),
).let { base -> List(4) { base }.flatten() }

private data class DemoEvent(val secondsBeforeEnd: Long, val category: String, val severity: String, val message: String)

/**
 * Обрывы связи для демо: сколько секунд назад инцидент закончился и его строки в формулировках настоящего роутера
 * (VMG8623-T50B). Свежий — оборвалась сессия PPP, связь дважды пропадала; старый — пропала линия DSL.
 */
private val DEMO_DROPS = listOf(
    DEMO_WAN_UPTIME to listOf(
        DemoEvent(120, "pppoe", "daemon.info", "pppd: Connect time 2790.4 minutes."),
        DemoEvent(120, "pppoe", "daemon.info", "pppd: Sent 86973506 bytes, received 528438004 bytes."),
        DemoEvent(120, "pppoe", "daemon.notice", """pppd: Json PPP { "ip": "203.0.113.7", "linkname": "pppd3", "type": "ipv4", "state": "down" }"""),
        DemoEvent(64, "pppoe", "daemon.info", "pppd: Sent PADT"),
        DemoEvent(64, "pppoe", "daemon.notice", "pppd: Connect: ppp2 <--> nas8_0"),
        DemoEvent(61, "pppoe", "daemon.notice", "pppd: PAP authentication succeeded"),
        DemoEvent(61, "pppoe", "daemon.notice", """pppd: Json PPP { "ip": "203.0.113.7", "remote": "203.0.113.1", "linkname": "pppd3", "type": "ipv4", "state": "up" }"""),
        DemoEvent(12, "system", "user.notice", "zcmdModuleCfg: System: Wan interface VDSL connection lost"),
        DemoEvent(0, "system", "user.notice", "zcmdModuleCfg: System: Wan interface VDSL connection ready"),
    ),
    DEMO_LINE_UPTIME to listOf(
        DemoEvent(96, "pppoe", "daemon.info", "pppd: No response to 5 echo-requests"),
        DemoEvent(96, "pppoe", "daemon.notice", "pppd: Serial link appears to be disconnected."),
        DemoEvent(95, "pppoe", "daemon.notice", """pppd: Json PPP { "ip": "203.0.113.7", "linkname": "pppd3", "type": "ipv4", "state": "down" }"""),
        DemoEvent(94, "system", "user.notice", "zcmdModuleCfg: System: Wan interface VDSL connection lost"),
        DemoEvent(-28, "pppoe", "daemon.notice", "pppd: PAP authentication succeeded"),
        DemoEvent(-30, "pppoe", "daemon.notice", """pppd: Json PPP { "ip": "203.0.113.7", "remote": "203.0.113.1", "linkname": "pppd3", "type": "ipv4", "state": "up" }"""),
        DemoEvent(-32, "system", "user.notice", "zcmdModuleCfg: System: Wan interface VDSL connection ready"),
    ),
)

/** Статистика линии в формате страницы «xDSL Statistics» настоящего роутера. */
private val DEMO_LINE = """
       Line State:      Up
       Modulation:      ITU G.993.5(G.Vectoring),G.998.4(G.INP)
near-end interleaved channel bit rate: 98304 kbps
near-end fast channel bit rate: 0 kbps
far-end interleaved channel bit rate: 0 kbps
far-end fast channel bit rate: 20480 kbps
near-end FEC error interleaved: 173906
near-end CRC error interleaved: 45
far-end FEC error fast: 3449
far-end CRC error fast: 2
DSL uptime :@UPTIME@
noise margin downstream: 6.3 dB
output power upstream: 7.5 dbm
attenuation downstream: 9.7 dB
noise margin upstream: 7.5 dB
output power downstream: 12.3 dbm
attenuation upstream: 1.9 dB
"""
