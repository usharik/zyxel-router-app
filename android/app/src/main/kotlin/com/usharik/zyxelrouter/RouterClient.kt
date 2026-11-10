package com.usharik.zyxelrouter

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed class RouterException(message: String) : Exception(message) {
    object BadAddress : RouterException("Укажите локальный IP-адрес роутера, например 192.168.1.1.")
    object InvalidCredentials : RouterException("Неверное имя пользователя или пароль.")
    class Locked(seconds: Int) : RouterException("Роутер временно заблокировал вход. Подождите $seconds с.")
    object SessionExpired : RouterException("Сессия истекла.")
    class Failed(message: String) : RouterException(message)
}

data class RouterBasic(val model: String, val firmware: String, val language: String)
data class RouterDevice(
    val name: String, val ip: String, val mac: String, val connection: String, val online: Boolean, val guest: Boolean, val network: String,
    // Расширенные сведения (многое в прошивке приходит пустым — тогда нулевые/пустые).
    val type: String, val vendorClass: String, val signal: Int, val snr: Int, val phyRate: Int, val wifiStandard: String,
    val bytesDown: Long, val bytesUp: Long, val ipv6: Boolean, val staticIP: Boolean, val leaseRemaining: Long,
    val blocked: Boolean, val details: Map<String, String>,
    val lastSeen: Long = 0,   // когда последний раз был в сети (epoch, сек); 0 — сейчас онлайн
)

/** Запись об известном устройстве, которого сейчас нет в выдаче роутера (из локальной истории). */
fun offlineDevice(mac: String, name: String, type: String, connection: String, lastSeen: Long): RouterDevice =
    RouterDevice(
        name.ifEmpty { mac }, "", mac, connection, false, false,
        if (connection.contains("wi-fi", ignoreCase = true)) "Wi-Fi" else if (connection.isEmpty()) "" else "Проводная сеть",
        type, "", 0, 0, 0, "", 0, 0, false, false, 0, false, emptyMap(), lastSeen,
    )

/** Все непустые поля устройства как строки — для раздела «Все поля»; технические идентификаторы скрыты. */
internal fun deviceDetails(item: JSONObject): Map<String, String> {
    val hidden = setOf("ClientDuid", "ClientID", "icon")
    val out = linkedMapOf<String, String>()
    for (k in item.keys()) {
        if (k in hidden) continue
        val v = item.opt(k)
        val s = when (v) {
            is Boolean -> if (v) "да" else "нет"
            is Number -> v.toString()
            is String -> v
            else -> ""
        }
        val keep = s.isNotEmpty() && (s != "0" || k.contains("Bytes") || k.contains("Rate"))
        if (keep) out[k] = s
    }
    return out
}

// MARK: - Журнал роутера
data class LogEntry(
    val id: Int, val category: String, val severity: String, val process: String, val message: String,
    val month: Int, val day: Int, val time: String, val raw: String,
)
data class LogCount(val label: String, val count: Int)
enum class LogPeriod(val label: String) { DAY("День"), WEEK("Неделя"), MONTH("Месяц") }

private val ruMonths = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
private val engMonths = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

/** Разбирает журнал роутера из расшифрованного текста (массив объектов с полем result — строки). */
internal fun parseLog(text: String?): List<LogEntry> {
    if (text.isNullOrBlank()) return emptyList()
    val lines = mutableListOf<String>()
    fun collect(v: Any?) {
        when (v) {
            is JSONArray -> for (i in 0 until v.length()) collect(v.opt(i))
            is JSONObject -> collect(if (v.has("result")) v.opt("result") else v.opt("Object"))
            is String -> lines.add(v)
        }
    }
    collect(runCatching { JSONArray(text) }.getOrNull() ?: runCatching { JSONObject(text) }.getOrNull())
    return lines.mapIndexedNotNull { index, line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapIndexedNotNull null
        var category = ""; var rest = trimmed
        if (trimmed.startsWith("[") && trimmed.contains("]")) {
            category = trimmed.substring(1, trimmed.indexOf(']'))
            rest = trimmed.substring(trimmed.indexOf(']') + 1).trim()
        }
        val parts = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var month = 0; var day = 0; var time = ""; var severity = ""; var process = ""; var message = rest
        val mi = if (parts.size >= 4) engMonths.indexOf(parts[0].lowercase()) else -1
        if (mi >= 0) {
            month = mi + 1
            day = parts[1].toIntOrNull() ?: 0
            time = parts[2]
            val facility = parts[3]
            severity = if (facility.contains(".")) facility.substringAfterLast(".") else facility
            val tail = parts.drop(4).joinToString(" ")
            if (tail.contains(":")) { process = tail.substringBefore(":"); message = tail.substringAfter(":").trim() } else message = tail
        }
        LogEntry(index, category, severity, process, message, month, day, time, trimmed)
    }
}

fun logDayLabel(e: LogEntry): String = if (e.month in 1..12) "${e.day} ${ruMonths[e.month - 1]}" else "—"

fun logAggregate(entries: List<LogEntry>, period: LogPeriod): List<LogCount> {
    val year = java.time.LocalDate.now().year
    data class B(val order: Int, val label: String, var count: Int)
    val buckets = linkedMapOf<String, B>()
    for (e in entries) {
        if (e.month !in 1..12) continue
        val date = runCatching { java.time.LocalDate.of(year, e.month, e.day.coerceAtLeast(1)) }.getOrNull()
        val (key, order, label) = when (period) {
            LogPeriod.DAY -> Triple("d${e.month * 100 + e.day}", e.month * 100 + e.day, logDayLabel(e))
            LogPeriod.WEEK -> { val w = date?.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()) ?: 0; Triple("w$w", w, "неделя $w") }
            LogPeriod.MONTH -> Triple("m${e.month}", e.month, ruMonths[e.month - 1])
        }
        val b = buckets[key]
        if (b != null) b.count++ else buckets[key] = B(order, label, 1)
    }
    return buckets.values.sortedByDescending { it.order }.map { LogCount(it.label, it.count) }
}

fun logCountsBy(entries: List<LogEntry>, key: (LogEntry) -> String): List<LogCount> {
    val counts = linkedMapOf<String, Int>()
    for (e in entries) { val k = key(e); if (k.isNotEmpty()) counts[k] = (counts[k] ?: 0) + 1 }
    return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { LogCount(it.key, it.value) }
}

/** Нормализует MAC к AA:BB:CC:DD:EE:FF (принимает ":" и "-"), или null. */
fun normalizeMAC(s: String): String? {
    val parts = s.uppercase().replace("-", ":").split(":")
    if (parts.size != 6 || parts.any { it.length != 2 || !it.all { c -> c.isDigit() || c in 'A'..'F' } }) return null
    return parts.joinToString(":")
}

/** Выводит тип устройства из DHCP vendor class и имени хоста. Прошивка не отдаёт тип напрямую. */
fun deviceType(vendorClass: String, hostName: String): String {
    val v = vendorClass.lowercase()
    val h = hostName.lowercase()
    fun any(s: String, needles: List<String>) = needles.any { s.contains(it) }
    return when {
        any(v, listOf("printer", "xerox", "epson", "canon")) || any(h, listOf("printer", "xrx", "xerox", "phaser", "печат")) -> "Принтер"
        any(v, listOf("atvbox", "roku", "appletv", "shield")) || any(h, listOf("tv", "appletv", "chromecast", "shield", "roku")) -> "ТВ-приставка"
        any(h, listOf("iphone", "pixel", "galaxy", "redmi", "oneplus", "phone", "телефон", "ipad", "mediapad", "tab")) -> "Телефон или планшет"
        v.startsWith("android-dhcp") || v.contains(":android:") -> "Телефон или планшет"
        any(v, listOf("msft", "windows")) || any(h, listOf("macbook", "imac", "-pc", "desktop", "mbp", "pc")) -> "Компьютер"
        any(v, listOf("udhcp", "busybox", "dhcpcd")) || any(h, listOf("vacuum", "esp", "shelly", "tuya", "sensor", "cam", "iot")) -> "IoT-устройство"
        h.contains("mac") -> "Компьютер"
        else -> "Неизвестно"
    }
}

/** Человекочитаемый объём: 1536 → «1,5 КБ», 1788431209 → «1,7 ГБ». */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble(); var i = 0
    while (value >= 1024 && i < units.size - 1) { value /= 1024; i++ }
    return if (i == 0) "$bytes Б" else "%.1f".format(java.util.Locale.ROOT, value).replace(".", ",") + " " + units[i]
}
data class WifiNetwork(val ssid: String, val band: String, val enabled: Boolean, val channel: String, val security: String, val guest: Boolean)
data class ParentalProfile(
    val index: Int, val name: String, val enabled: Boolean, val macs: List<String>,
    val days: List<String>, val times: List<String>, val blocksSites: Boolean, val blocksServices: Boolean,
)

data class ParentalControl(val enabled: Boolean, val maxProfiles: Int, val profiles: List<ParentalProfile>)

/** Одна строка маршрута для показа: роутер хранит четыре разных вида, здесь они сведены к заголовку и описанию. */
data class RouteItem(val id: Int, val title: String, val subtitle: String, val enabled: Boolean)

data class Routing(
    val staticRoutes: List<RouteItem>, val dnsRoutes: List<RouteItem>,
    val policyRoutes: List<RouteItem>, val rip: List<RouteItem>,
)

data class RouterSnapshot(
    val model: String, val firmware: String, val uptime: Long, val internetUp: Boolean, val linkType: String, val encapsulation: String,
    val wanIP: String, val dns: String, val internetUptime: Long, val downKbps: Long, val upKbps: Long,
    val devices: List<RouterDevice>, val wifi: List<WifiNetwork>, val schema: String,
)

private fun str(v: Any?): String {
    val raw = when (v) {
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> return ""
    }
    return raw.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}

private fun long(v: Any?): Long = when (v) {
    is Number -> v.toLong()
    is String -> v.toLongOrNull() ?: 0
    else -> 0
}

private fun bool(v: Any?): Boolean = when (v) {
    is Boolean -> v
    is Number -> v.toInt() != 0
    is String -> v.lowercase() in setOf("1", "true", "up", "enabled")
    else -> false
}

private fun JSONObject?.items(key: String): List<JSONObject> {
    val array = this?.optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

/** Field names and types only, so the result is safe to share. */
private fun outline(v: Any?, indent: String): String = when (v) {
    is JSONObject -> v.keys().asSequence().sorted().joinToString("\n") { key ->
        val child = v.opt(key)
        if (child is JSONObject || child is JSONArray) "$indent$key:\n" + outline(child, "$indent  ") else "$indent$key: ${typeName(child)}"
    }
    is JSONArray -> if (v.length() > 0) "$indent[${v.length()}]\n" + outline(v.opt(0), "$indent  ") else "$indent[0]"
    else -> indent + typeName(v)
}

private fun typeName(v: Any?) = when (v) {
    is String -> "string"
    is Boolean -> "bool"
    is Number -> "number"
    else -> "null"
}

internal fun parseParental(objects: List<JSONObject>): ParentalControl {
    val head = objects.firstOrNull() ?: return ParentalControl(false, 0, emptyList())
    val profiles = objects.drop(1).map { item ->
        val macs = str(item.opt("MACAddressList")).split(",").filter { it.isNotBlank() }.map { it.replace(":", "-").uppercase() }
        ParentalProfile(
            long(item.opt("Index")).toInt(), str(item.opt("pcpName")), bool(item.opt("pcpEnable")), macs,
            str(item.opt("Days")).split(",").filter { it.isNotBlank() },
            str(item.opt("TimeRangeList")).split(",").filter { it.isNotBlank() },
            str(item.opt("URLFilter")).isNotEmpty(), str(item.opt("NetworkServiceList")).isNotEmpty(),
        )
    }
    return ParentalControl(bool(head.opt("PrentalCtlEnable")), long(head.opt("MaxLenPrentalCtlPrf")).toInt(), profiles)
}

internal fun parseRouting(st: List<JSONObject>, dns: List<JSONObject>, policy: List<JSONObject>, rip: List<JSONObject>): Routing {
    val staticRoutes = st.mapIndexed { i, o ->
        val v6 = str(o.opt("ipver")) == "IPv6"
        val dest = if (v6) str(o.opt("DestIPPrefix")) else "${str(o.opt("DestIPAddress"))} / ${str(o.opt("DestSubnetMask"))}"
        val gw = if (v6) str(o.opt("NextHop")) else str(o.opt("GatewayIPAddress"))
        RouteItem(i, str(o.opt("Name")).ifEmpty { dest }, "в $dest" + if (gw.isEmpty()) "" else " через $gw", bool(o.opt("Enable")))
    }
    // id уникальны по всему экрану (у групп свои диапазоны), иначе keyed-списки путают строки.
    val dnsRoutes = dns.mapIndexed { i, o -> RouteItem(100 + i, str(o.opt("DomainName")), "запросы DNS через выбранный интерфейс", bool(o.opt("Enable"))) }
    val policyRoutes = policy.mapIndexed { i, o ->
        val proto = str(o.opt("Protocol"))
        val src = str(o.opt("SourceIpAddress"))
        val from = listOf(if (src.isEmpty()) "" else "IP $src", if (str(o.opt("SourceMacAddress")).isEmpty()) "" else "MAC").filter { it.isNotEmpty() }.joinToString(", ")
        RouteItem(200 + i, str(o.opt("RouteName")), (if (from.isEmpty()) "любой источник" else "от $from") + if (proto == "ALL" || proto.isEmpty()) "" else ", $proto", bool(o.opt("Enable")))
    }
    val ripItems = rip.mapIndexed { i, o ->
        val iface = str(o.opt("Interface")).substringAfterLast(".")
        RouteItem(300 + i, if (iface.isEmpty()) "RIP" else "Интерфейс $iface", "${str(o.opt("Version"))} · ${str(o.opt("RipOpMode"))}", bool(o.opt("Enable")))
    }
    return Routing(staticRoutes, dnsRoutes, policyRoutes, ripItems)
}

/** Сеть устройства: проводная — отдельная подпись, Wi-Fi — SSID той же полосы и роли (основная/гостевая). */
/** Полоса сети Wi-Fi: по стандартам (a/ac/ax → 5 ГГц) надёжнее позиционной эвристики; иначе по позиции. */
internal fun wifiBand(item: JSONObject, index: Int, count: Int): String {
    val std = str(item.opt("OperatingStandards")).lowercase()
    if (std.contains("ac") || std.contains("ax") || std.split(",").map { it.trim() }.contains("a")) return "5 ГГц"
    if (std.contains("b") || std.contains("g")) return "2,4 ГГц"
    return if (index < count / 2) "2,4 ГГц" else "5 ГГц"
}

/** Время ЧЧ:ММ в минуты от полуночи, или null. Допускает 24:00 как конец суток. */
internal fun minutesOfTime(s: String): Int? {
    val p = s.split(":")
    if (p.size != 2 || p[0].isEmpty() || p[1].isEmpty()) return null
    if (!p[0].all { it.isDigit() } || !p[1].all { it.isDigit() }) return null
    val h = p[0].toIntOrNull() ?: return null
    val m = p[1].toIntOrNull() ?: return null
    if (h !in 0..24 || m !in 0..59 || (h == 24 && m > 0)) return null
    return h * 60 + m
}

/** Приводит ЧЧ:ММ к виду с ведущими нулями, или null. */
internal fun normalizeTime(s: String): String? {
    val t = minutesOfTime(s) ?: return null
    return "%02d:%02d".format(t / 60, t % 60)
}

internal fun networkName(connection: String, guest: Boolean, wifi: List<WifiNetwork>): String {
    if (!connection.contains("wi-fi", ignoreCase = true)) return if (connection.isEmpty()) "" else "Проводная сеть"
    val band = if (connection.contains("2.4") || connection.contains("2,4")) "2,4 ГГц" else "5 ГГц"
    return wifi.firstOrNull { it.band == band && it.guest == guest }?.ssid
        ?: ("Wi-Fi $band" + if (guest) " (гостевая)" else "")
}

internal fun parseSnapshot(status: JSONObject, hosts: JSONObject): RouterSnapshot {
    val device = status.optJSONObject("DeviceInfo")
    // На реальном роутере LAN приходит как Srv="Default", Link="None" — исключаем из выбора WAN.
    fun isLan(o: JSONObject): Boolean {
        val srv = str(o.opt("X_ZYXEL_SrvName")).uppercase()
        return srv == "LAN" || srv == "DEFAULT" || str(o.opt("Name")).uppercase() == "LAN" || str(o.opt("LinkType")) == "None"
    }
    val wans = status.items("WanLanInfo").filter { !isLan(it) && str(it.opt("Status")) == "Up" }
    val wan = wans.firstOrNull { bool(it.opt("DefaultGateway")) } ?: wans.firstOrNull { str(it.opt("LinkType")).isNotEmpty() }
    val pppoe = str(wan?.opt("Encapsulation")) in setOf("PPPoE", "PPPoA", "PPtPtoPPPoA")
    val dsl = status.items("DslChannelInfo").firstOrNull { str(it.opt("Status")) == "Up" }

    val rawWifi = status.items("WiFiInfo")
    val wifi = rawWifi.mapIndexedNotNull { index, item ->
        val ssid = str(item.opt("SSID"))
        if (ssid.isEmpty()) null else WifiNetwork(
            ssid, wifiBand(item, index, rawWifi.size), bool(item.opt("Enable")),
            str(item.opt("Channel")), str(item.opt("ModeEnabled")), index % 2 == 1,
        )
    }
    val devices = hosts.items("lanhosts").map { item ->
        val mac = str(item.opt("PhysAddress")).uppercase()
        val host = listOf("curHostName", "DeviceName", "HostName").map { str(item.opt(it)) }.firstOrNull { it.isNotEmpty() } ?: ""
        // Layer1Interface бывает "Device.WiFi.SSID.N" и "WiFi.SSID.N" — число после последнего "SSID.".
        val iface = str(item.opt("Layer1Interface")).substringAfterLast("SSID.").toIntOrNull() ?: 0
        val connection = str(item.opt("X_ZYXEL_ConnectionType"))
        val guest = iface in setOf(2, 3, 4, 6, 7, 8)
        val vendorClass = str(item.opt("VendorClassID")).let { if (it == "*") "" else it }
        RouterDevice(
            host.ifEmpty { mac }, str(item.opt("IPAddress")), mac, connection, bool(item.opt("Active")), guest, networkName(connection, guest, wifi),
            deviceType(vendorClass, host), vendorClass, long(item.opt("X_ZYXEL_SignalStrength")).toInt(), long(item.opt("X_ZYXEL_SNR")).toInt(),
            long(item.opt("X_ZYXEL_PhyRate")).toInt(), str(item.opt("X_ZYXEL_OperatingStandard")),
            long(item.opt("X_ZYXEL_BytesReceived")), long(item.opt("X_ZYXEL_BytesSent")),
            str(item.opt("IPAddress6")).isNotEmpty(), bool(item.opt("dhcp4StaticAddrEnable")) || str(item.opt("staticIP")).isNotEmpty(),
            long(item.opt("LeaseTimeRemaining")), bool(item.opt("Internet_Blocking_Enable")), deviceDetails(item),
        )
    }.sortedWith(compareBy({ !it.online }, { it.name.lowercase() }))

    val schema = "cardpage_status:\n" + outline(status, "  ") + "\n\nlanhosts:\n" + outline(hosts, "  ")
    return RouterSnapshot(
        str(device?.opt("ModelName")), str(device?.opt("SoftwareVersion")), long(device?.opt("UpTime")), wan != null,
        str(wan?.opt("LinkType")), str(wan?.opt("Encapsulation")),
        str(wan.items("IPv4Address").firstOrNull()?.opt("IPAddress")), str(wan.items("dnsv4Server").firstOrNull()?.opt("DNSServer")),
        long(wan?.opt(if (pppoe) "pppoeConnectionUpTime" else "ipoeConnectionUpTime")),
        long(dsl?.opt("DownstreamCurrRate")), long(dsl?.opt("UpstreamCurrRate")), devices, wifi, schema,
    )
}

/** Роутер отдаёт Days строкой "Mon,Tue", а на запись подтверждённо принимает массив. */
internal fun daysAsArray(obj: JSONObject): JSONObject {
    val days = obj.opt("Days") as? String ?: return obj
    return obj.put("Days", JSONArray(days.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
}

/** Blocking client for the router's web API; call from a background dispatcher. */
class RouterClient(address: String) {
    private val base: String
    private val cookies = linkedMapOf<String, String>()
    private var aesKey: String? = null
    private var csrf: String? = null

    /** Растёт при каждом входе: параллельные восстановления сессии не должны входить повторно. */
    @Volatile var sessionGeneration = 0
        private set

    init {
        val trimmed = address.trim()
        if (!isPrivateIPv4(trimmed.substringBefore(":"))) throw RouterException.BadAddress
        base = "https://$trimmed"
    }

    private class Reply(val status: Int, val json: JSONObject?, val text: String? = null)

    // Сериализует доступ к сессии (cookies/csrf/aesKey): опрос и действия пользователя идут из разных
    // корутин на Dispatchers.IO параллельно. Без этого — ConcurrentModificationException и потеря CSRF.
    @Synchronized
    private fun send(method: String, path: String, body: String? = null, timeoutMs: Int = 30_000): Reply {
        try {
            val connection = URL(base + path).openConnection() as HttpsURLConnection
            connection.sslSocketFactory = trustingSsl.socketFactory
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { host, _ -> isPrivateIPv4(host) }
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.setRequestProperty("If-Modified-Since", "Thu, 01 Jun 1970 00:00:00 GMT")
            if (cookies.isNotEmpty()) connection.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (method != "GET" && path.contains("cgi-bin")) csrf?.let { connection.setRequestProperty("CSRFToken", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            connection.headerFields.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }.values.flatten().forEach { header ->
                val pair = header.substringBefore(";")
                cookies[pair.substringBefore("=").trim()] = pair.substringAfter("=").trim()
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            var json = runCatching { JSONObject(text) }.getOrNull()
            var plainText = text
            val key = aesKey
            if (json != null && key != null && json.has("iv") && json.has("content") && (path == "/UserLogin" || path.contains("cgi-bin"))) {
                plainText = String(aes(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(json.getString("content")), key, json.getString("iv")))
                json = runCatching { JSONObject(plainText) }.getOrNull()   // журнал — массив, тогда null; используем text
            }
            // Роутер выдаёт новый CSRF-токен после каждой записи; без этого следующая запись вернёт 403.
            str(json?.opt("sessionkey")).takeIf { it.isNotEmpty() }?.let { csrf = it }
            return Reply(status, json, plainText)
        } catch (e: RouterException) {
            throw e
        } catch (e: Exception) {
            // Текст исключения (адрес, внутренности HTTP-клиента) пользователю ничего не скажет.
            throw RouterException.Failed("Роутер недоступен. Проверьте подключение к его сети.")
        }
    }

    fun basicInfo(): RouterBasic {
        val d = send("GET", "/getBasicInformation", timeoutMs = 8_000).json
        return RouterBasic(str(d?.opt("ModelName")), str(d?.opt("SoftwareVersion")), str(d?.opt("CurrentLanguage")))
    }

    // Весь вход под тем же монитором, что и send: иначе параллельный запрос застанет сессию наполовину сброшенной.
    @Synchronized
    fun login(username: String, password: String) {
        try {
            performLogin(username, password)
            sessionGeneration++
        } catch (e: RouterException) {
            throw e
        } catch (e: Exception) {
            throw RouterException.Failed("Не удалось войти: роутер вернул некорректные данные.")   // битый RSA-ключ и т. п.
        }
    }

    /** Повторный вход, если с момента [generation] сессию ещё никто не восстановил. */
    @Synchronized
    fun reloginIfUnchanged(generation: Int, username: String, password: String) {
        if (generation == sessionGeneration) login(username, password)
    }

    private fun performLogin(username: String, password: String) {
        val basic = basicInfo()
        val pem = str(send("GET", "/getRSAPublickKey", timeoutMs = 15_000).json?.opt("RSAPublicKey"))
        val payload = JSONObject()
            .put("Input_Account", username)
            .put("Input_Passwd", Base64.getEncoder().encodeToString(password.toByteArray()))
            .put("currLang", basic.language.ifEmpty { "en" })
            .put("RememberPassword", 0)
            .put("SHA512_password", false)
            .toString()
        cookies.clear()
        csrf = null
        aesKey = null   // не шифровать устаревшим ключом, если сессия вдруг окажется без шифрования
        var body = payload
        if (pem.isNotEmpty() && pem != "None") {
            val key = randomBase64()
            val iv = randomBase64()
            aesKey = key
            body = JSONObject()
                .put("content", Base64.getEncoder().encodeToString(aes(Cipher.ENCRYPT_MODE, payload.toByteArray(), key, iv)))
                .put("key", Base64.getEncoder().encodeToString(rsaEncrypt(key.toByteArray(), pem)))
                .put("iv", iv)
                .toString()
        }
        val reply = send("POST", "/UserLogin", body)
        if (reply.status !in 200..299) {
            val result = str(reply.json?.opt("result"))
            throw when (result) {
                "Invalid Username or Password", "HTTP permission denied" -> RouterException.InvalidCredentials
                "Locked User" -> RouterException.Locked(str(reply.json?.opt("replyMsg")).substringAfterLast("=").toIntOrNull() ?: 0)
                "Duplicated login", "A user exist" -> RouterException.Failed("На роутере уже открыта другая сессия.")
                "Maxium number of login account has reached" -> RouterException.Failed("На роутере открыто максимальное число сессий.")
                else -> RouterException.Failed("Вход не выполнен: ${result.ifEmpty { "HTTP ${reply.status}" }}")
            }
        }
        csrf = str(reply.json?.opt("sessionkey"))
    }

    private fun dal(oid: String): JSONObject {
        val reply = send("GET", "/cgi-bin/DAL?oid=$oid")
        if (reply.status in setOf(401, 403, 504)) throw RouterException.SessionExpired
        val first = reply.json?.takeIf { str(it.opt("result")) == "ZCFG_SUCCESS" }?.optJSONArray("Object")?.optJSONObject(0)
        if (reply.status != 200 || first == null) throw RouterException.Failed("Роутер не вернул данные $oid (HTTP ${reply.status}).")
        return first
    }

    fun snapshot(): RouterSnapshot = parseSnapshot(dal("cardpage_status"), dal("lanhosts"))

    private fun dalObjects(oid: String): List<JSONObject> {
        val reply = send("GET", "/cgi-bin/DAL?oid=$oid")
        if (reply.status in setOf(401, 403, 504)) throw RouterException.SessionExpired
        val arr = reply.json?.takeIf { str(it.opt("result")) == "ZCFG_SUCCESS" }?.optJSONArray("Object")
        if (reply.status != 200 || arr == null) throw RouterException.Failed("Роутер не вернул данные $oid (HTTP ${reply.status}).")
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** Запись в DAL: тело шифруется тем же ключом AES, что и при входе, с пустым полем key — как это делает веб-интерфейс. */
    private fun dalWrite(method: String, oid: String, obj: JSONObject) {
        val payload = obj.toString()
        val key = aesKey
        val body = if (key != null) {
            val iv = randomBase64()
            JSONObject()
                .put("content", Base64.getEncoder().encodeToString(aes(Cipher.ENCRYPT_MODE, payload.toByteArray(), key, iv)))
                .put("key", "")
                .put("iv", iv)
                .toString()
        } else payload
        val reply = send(method, "/cgi-bin/DAL?oid=$oid", body)
        if (reply.status in setOf(401, 403, 504)) throw RouterException.SessionExpired
        if (reply.status !in 200..299 || str(reply.json?.opt("result")) != "ZCFG_SUCCESS") throw RouterException.Failed("Роутер отклонил изменение $oid (HTTP ${reply.status}).")
    }

    fun parentalControl(): ParentalControl = parseParental(dalObjects("paren_ctl"))

    fun setParentalEnabled(on: Boolean) = dalWrite("PUT", "paren_ctl", JSONObject().put("PrentalCtlEnable", if (on) "1" else "0"))

    /** Включает или выключает профиль: читает его текущий объект и возвращает целиком с изменённым флагом. */
    fun setProfileEnabled(index: Int, on: Boolean) {
        val obj = dalObjects("paren_ctl").firstOrNull { long(it.opt("Index")).toInt() == index }
            ?: throw RouterException.Failed("Профиль не найден; обновите список.")
        obj.put("pcpEnable", if (on) 1 else 0)
        dalWrite("PUT", "paren_ctl", daysAsArray(obj))
    }

    /** Создаёт (index == null) или изменяет профиль. MAC с двоеточиями, дни кодами Mon..Sun,
     *  timeRangeList — готовая строка интервалов. При изменении читается текущий объект и правятся
     *  только эти поля, чтобы не стереть фильтры сайтов и сервисов из веб-интерфейса. */
    fun saveProfile(index: Int?, name: String, macs: List<String>, days: List<String>, timeRangeList: String, enabled: Boolean) {
        if (index != null) {
            val obj = dalObjects("paren_ctl").firstOrNull { long(it.opt("Index")).toInt() == index }
                ?: throw RouterException.Failed("Профиль не найден; обновите список.")
            obj.put("pcpName", name).put("pcpEnable", if (enabled) 1 else 0)
                .put("MACAddressList", macs.joinToString(",")).put("Days", JSONArray(days))
                .put("TimeRangeList", timeRangeList)
            dalWrite("PUT", "paren_ctl", obj)
        } else {
            val obj = JSONObject()
                .put("pcpName", name).put("pcpEnable", if (enabled) 1 else 0)
                .put("MACAddressList", macs.joinToString(",")).put("Days", JSONArray(days))
                .put("TimeRangeList", timeRangeList)
                .put("ServicePolicy", "Block").put("UrlPolicy", "Block").put("WebRedirect", 0)
                .put("URLFilter", "").put("NetworkServiceList", "")
            dalWrite("POST", "paren_ctl", obj)
        }
    }

    fun deleteProfile(index: Int) {
        val reply = send("DELETE", "/cgi-bin/DAL?oid=paren_ctl&Index=$index")
        if (reply.status in setOf(401, 403, 504)) throw RouterException.SessionExpired
        if (reply.status !in 200..299 || str(reply.json?.opt("result")) != "ZCFG_SUCCESS") throw RouterException.Failed("Роутер отклонил удаление профиля (HTTP ${reply.status}).")
    }

    fun routing(): Routing = parseRouting(dalObjects("static_route"), dalObjects("dns_route"), dalObjects("policy_route"), dalObjects("rip"))

    /** Читает системный журнал (iid [1] — системный, [2] — безопасность). */
    fun systemLog(security: Boolean = false): List<LogEntry> {
        val iid = if (security) "2" else "1"
        val reply = send("GET", "/cgi-bin/LogResult?action=GET_LOG&oid=RDM_OID_LOG_CLASSIFY&iid=[$iid,0,0,0,0,0]")
        if (reply.status in setOf(401, 403, 504)) throw RouterException.SessionExpired
        if (reply.status !in 200..299) throw RouterException.Failed("Журнал недоступен (HTTP ${reply.status}).")
        return parseLog(reply.text)
    }

    fun reboot() {
        val status = send("POST", "/cgi-bin/Reboot").status
        if (status in setOf(401, 403, 504)) throw RouterException.SessionExpired
        if (status !in 200..299) throw RouterException.Failed("Роутер отклонил перезагрузку (HTTP $status).")
    }

    fun logout() {
        runCatching { send("POST", "/cgi-bin/UserLogout", timeoutMs = 3_000) }
        cookies.clear()
        csrf = null
        aesKey = null
    }

    private fun randomBase64() = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    /** AES-256-CBC. The router sends 32-byte IVs and, like CryptoJS, only the first block is used. */
    private fun aes(mode: Int, input: ByteArray, key: String, iv: String): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val ivBytes = Base64.getDecoder().decode(iv)
        require(ivBytes.size >= 16) { "IV короче 16 байт" }   // не дополнять нулями молча
        cipher.init(mode, SecretKeySpec(Base64.getDecoder().decode(key), "AES"), IvParameterSpec(ivBytes.copyOf(16)))
        return cipher.doFinal(input)
    }

    private fun rsaEncrypt(data: ByteArray, pem: String): ByteArray {
        val der = Base64.getDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString("").trim())
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)))
        return cipher.doFinal(data)
    }

    companion object {
        fun isPrivateIPv4(host: String): Boolean {
            val parts = host.split(".").map { it.toIntOrNull() ?: -1 }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return false
            return parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31)
        }

        // The router's certificate is self-signed; these connections are only ever made to the private address the user entered.
        private val trustingSsl: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), SecureRandom())
        }
    }
}
