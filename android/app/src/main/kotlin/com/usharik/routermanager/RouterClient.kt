package com.usharik.routermanager

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Ошибки работы с роутером. Сообщение — английское, для журналов и Crashlytics; текст для пользователя
 * на его языке строит интерфейс по типу ошибки (`routerErrorText`).
 */
sealed class RouterException(message: String) : Exception(message) {
    object BadAddress : RouterException("Not a private IPv4 address")
    object InvalidCredentials : RouterException("Invalid user name or password")
    class Locked(val seconds: Int) : RouterException("Login locked for $seconds s")
    object SessionExpired : RouterException("Session expired")
    /** Сеанс восстановлен после устаревшего токена, но действие не повторено (создание, перезагрузка). */
    object SessionRefreshed : RouterException("Session refreshed, action not repeated")
    object Unreachable : RouterException("Router unreachable")
    /** Роутер ответил, но ответ не разобрать (не расшифровывается, обрезан): это не «недоступен». */
    object BadResponse : RouterException("Router returned an unreadable response")
    object BadLoginData : RouterException("Router returned malformed login data")
    /**
     * Ответ на вход не разобрать. Отдельно от [BadResponse]: при входе это отказ (повторять нельзя — пароль мог дойти),
     * а оборванный ответ на чтение раздела — обычная ошибка запроса, из сессии она не выкидывает.
     */
    object LoginUnreadable : RouterException("Router returned an unreadable login response")
    object DuplicateSession : RouterException("Another session is open")
    object TooManySessions : RouterException("Maximum number of sessions reached")
    class LoginRejected(val detail: String) : RouterException("Login rejected: $detail")
    class NoData(val oid: String, val status: Int) : RouterException("No data for $oid (HTTP $status)")
    class WriteRejected(val oid: String, val status: Int) : RouterException("Write to $oid rejected (HTTP $status)")
    object ProfileNotFound : RouterException("Profile not found")
    /** Устройства уже нет в списке роутера: блокировать нечего. */
    object DeviceNotFound : RouterException("Device not found")
    class DeleteRejected(val status: Int) : RouterException("Profile delete rejected (HTTP $status)")
    class LogUnavailable(val status: Int) : RouterException("Log unavailable (HTTP $status)")
    class RebootRejected(val status: Int) : RouterException("Reboot rejected (HTTP $status)")
    /** Роутер предъявил не тот сертификат, что при первом подключении; [fingerprint] — SHA-256 нового. */
    class CertificateChanged(val fingerprint: String) : RouterException("Router certificate changed")
}

/** Тип устройства, выведенный из DHCP vendor class и имени хоста. */
enum class DeviceType {
    PHONE, COMPUTER, PRINTER, TV, IOT, UNKNOWN;

    companion object {
        /** Из сохранённой истории: имя константы, а в записях версий до 1.2 — русская подпись. */
        fun parse(stored: String): DeviceType = entries.firstOrNull { it.name == stored } ?: when (stored) {
            "Телефон или планшет" -> PHONE
            "Компьютер" -> COMPUTER
            "Принтер" -> PRINTER
            "ТВ-приставка" -> TV
            "IoT-устройство" -> IOT
            else -> UNKNOWN
        }
    }
}

enum class WifiBand { GHZ_2_4, GHZ_5 }

/** Полоса по строке типа подключения устройства («Wi-Fi 2.4GHz»); null — проводное или неизвестно. */
fun bandOfConnection(connection: String): WifiBand? = when {
    !connection.contains("wi-fi", ignoreCase = true) -> null
    connection.contains("2.4") || connection.contains("2,4") -> WifiBand.GHZ_2_4
    else -> WifiBand.GHZ_5
}

/** Отпечатки сертификатов роутеров (trust on first use): у роутера самоподписанный сертификат, проверить его по цепочке нельзя. */
interface CertificatePins {
    fun get(host: String): String?
    fun put(host: String, fingerprint: String)
}

/**
 * Отпечатки одного клиента: сохранённые плюс увиденный впервые. Новый отпечаток попадает в постоянное хранилище
 * только после успешного входа ([commit]): простое обращение к адресу (поиск роутера) ничего не закрепляет, и
 * пароль не уходит устройству, которому пользователь ещё не доверился.
 */
internal class SessionPins(private val stored: CertificatePins) : CertificatePins {
    private val seen = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(host: String) = stored.get(host) ?: seen[host]
    override fun put(host: String, fingerprint: String) { seen[host] = fingerprint }
    fun commit() {
        seen.forEach { (host, fingerprint) -> if (stored.get(host) == null) stored.put(host, fingerprint) }
        if (stored is TrustedPins) stored.commit()
    }
}

/**
 * Новый отпечаток, которому пользователь решил доверять: для [host] действует только он, а в хранилище
 * он попадает при [SessionPins.commit], то есть после успешного входа. Неудачный вход ничего не меняет.
 */
internal class TrustedPins(private val stored: CertificatePins, private val host: String, private val fingerprint: String) : CertificatePins {
    override fun get(host: String) = if (host == this.host) fingerprint else stored.get(host)
    override fun put(host: String, fingerprint: String) = stored.put(host, fingerprint)
    fun commit() = stored.put(host, fingerprint)
}

class MemoryCertificatePins : CertificatePins {
    private val pins = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(host: String) = pins[host]
    override fun put(host: String, fingerprint: String) { pins[host] = fingerprint }
}

enum class PinCheck { FIRST_USE, MATCH, MISMATCH }

/** Первый сертификат запоминается, следующие обязаны совпасть с ним. */
internal fun checkPin(pinned: String?, actual: String): PinCheck = when {
    pinned.isNullOrEmpty() -> PinCheck.FIRST_USE
    pinned.equals(actual, ignoreCase = true) -> PinCheck.MATCH
    else -> PinCheck.MISMATCH
}

internal fun certificateFingerprint(encoded: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02X".format(java.util.Locale.ROOT, it) }

internal class PinMismatchException(val fingerprint: String) : CertificateException("Router certificate does not match the pinned one")

/**
 * Проверка сертификата роутера по сохранённому отпечатку. Срок действия и издатель не проверяются: роутеры выпускают
 * самоподписанные сертификаты, часто давно просроченные. Защищает от подмены роутера после первого подключения.
 */
internal class PinningTrustManager(private val host: String, private val pins: CertificatePins) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("Client certificates are not used")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Router presented no certificate")
        val actual = certificateFingerprint(leaf.encoded)
        when (checkPin(pins.get(host), actual)) {
            PinCheck.FIRST_USE -> pins.put(host, actual)
            PinCheck.MATCH -> Unit
            PinCheck.MISMATCH -> throw PinMismatchException(actual)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

data class RouterBasic(val model: String, val firmware: String, val language: String)
data class RouterDevice(
    val name: String, val ip: String, val mac: String, val connection: String, val online: Boolean, val guest: Boolean, val network: String,
    // Расширенные сведения (многое в прошивке приходит пустым — тогда нулевые/пустые).
    val type: DeviceType, val vendorClass: String, val signal: Int, val snr: Int, val phyRate: Int, val wifiStandard: String,
    val bytesDown: Long, val bytesUp: Long, val ipv6: Boolean, val staticIP: Boolean, val leaseRemaining: Long,
    val blocked: Boolean, val details: Map<String, String>,
    val lastSeen: Long = 0,   // когда последний раз был в сети (epoch, сек); 0 — сейчас онлайн
)

/** Запись об известном устройстве, которого сейчас нет в выдаче роутера (из локальной истории). */
fun offlineDevice(mac: String, name: String, type: DeviceType, connection: String, lastSeen: Long): RouterDevice =
    RouterDevice(
        name.ifEmpty { mac }, "", mac, connection, false, false, "",
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
            is Boolean -> if (v) "✓" else "✗"
            is Number -> v.toString()
            is String -> v
            else -> ""
        }
        val keep = s.isNotEmpty() && (s != "0" || k.contains("Bytes") || k.contains("Rate"))
        if (keep) out[k] = s
    }
    return out
}

/** Не больше [limit] байт: ответ чужого устройства не должен занять всю память. */
internal fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(chunk, 0, minOf(chunk.size, limit - out.size()))
        if (n < 0) break
        out.write(chunk, 0, n)
    }
    return out.toByteArray()
}

// MARK: - Журнал роутера
data class LogEntry(
    val id: Int, val category: String, val severity: String, val process: String, val message: String,
    val month: Int, val day: Int, val time: String, val raw: String,
)
data class LogCount(val label: String, val count: Int)
enum class LogPeriod { DAY, WEEK, MONTH }

/** Строка статистики журнала за период: подпись (день, неделя, месяц) строит интерфейс на языке пользователя. */
data class PeriodCount(val period: LogPeriod, val month: Int, val day: Int, val week: Int, val count: Int)
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

fun logAggregate(entries: List<LogEntry>, period: LogPeriod): List<PeriodCount> {
    val year = java.time.LocalDate.now().year
    val buckets = linkedMapOf<Int, PeriodCount>()
    for (e in entries) {
        if (e.month !in 1..12) continue
        val date = runCatching { java.time.LocalDate.of(year, e.month, e.day.coerceAtLeast(1)) }.getOrNull()
        val week = date?.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()) ?: 0
        val bucket = when (period) {
            LogPeriod.DAY -> PeriodCount(period, e.month, e.day, 0, 0)
            LogPeriod.WEEK -> PeriodCount(period, 0, 0, week, 0)
            LogPeriod.MONTH -> PeriodCount(period, e.month, 0, 0, 0)
        }
        val key = bucket.month * 100 + bucket.day + bucket.week * 10_000
        buckets[key] = (buckets[key] ?: bucket).let { it.copy(count = it.count + 1) }
    }
    return buckets.entries.sortedByDescending { it.key }.map { it.value }
}

fun logCountsBy(entries: List<LogEntry>, key: (LogEntry) -> String): List<LogCount> {
    val counts = linkedMapOf<String, Int>()
    for (e in entries) { val k = key(e); if (k.isNotEmpty()) counts[k] = (counts[k] ?: 0) + 1 }
    return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { LogCount(it.key, it.value) }
}

/** Нормализует MAC к AA:BB:CC:DD:EE:FF или null. Принимает «:», «-», «.» (aabb.ccdd.eeff), без разделителей и с пробелами по краям. */
fun normalizeMAC(s: String): String? {
    val hex = s.trim().uppercase().filterNot { it == ':' || it == '-' || it == '.' }
    if (hex.length != 12 || !hex.all { it.isDigit() || it in 'A'..'F' }) return null
    val separators = s.trim().filter { it == ':' || it == '-' || it == '.' }
    // Разделители допустимы только целиком одним из форматов, иначе «AA:BBCC…» — скорее опечатка.
    if (separators.isNotEmpty() && separators.length != 5 && !(separators.length == 2 && separators.all { it == '.' })) return null
    return hex.chunked(2).joinToString(":")
}

/** Выводит тип устройства из DHCP vendor class и имени хоста. Прошивка не отдаёт тип напрямую. */
fun deviceType(vendorClass: String, hostName: String): DeviceType {
    val v = vendorClass.lowercase()
    val h = hostName.lowercase()
    fun any(s: String, needles: List<String>) = needles.any { s.contains(it) }
    return when {
        any(v, listOf("printer", "xerox", "epson", "canon")) || any(h, listOf("printer", "xrx", "xerox", "phaser", "печат")) -> DeviceType.PRINTER
        any(v, listOf("atvbox", "roku", "appletv", "shield")) || any(h, listOf("tv", "appletv", "chromecast", "shield", "roku")) -> DeviceType.TV
        any(h, listOf("iphone", "pixel", "galaxy", "redmi", "oneplus", "phone", "телефон", "ipad", "mediapad", "tab")) -> DeviceType.PHONE
        v.startsWith("android-dhcp") || v.contains(":android:") -> DeviceType.PHONE
        any(v, listOf("msft", "windows")) || any(h, listOf("macbook", "imac", "-pc", "desktop", "mbp", "pc")) -> DeviceType.COMPUTER
        any(v, listOf("udhcp", "busybox", "dhcpcd")) || any(h, listOf("vacuum", "esp", "shelly", "tuya", "sensor", "cam", "iot")) -> DeviceType.IOT
        h.contains("mac") -> DeviceType.COMPUTER
        else -> DeviceType.UNKNOWN
    }
}

/** Человекочитаемый объём: 1536 → «1.5 KB»; единицы и десятичный разделитель — языка пользователя. */
fun formatBytes(bytes: Long, units: List<String> = listOf("B", "KB", "MB", "GB", "TB"), locale: java.util.Locale = java.util.Locale.ROOT): String {
    if (bytes <= 0) return "0 ${units[0]}"
    var value = bytes.toDouble(); var i = 0
    while (value >= 1024 && i < units.size - 1) { value /= 1024; i++ }
    return if (i == 0) "$bytes ${units[0]}" else "%.1f".format(locale, value) + " " + units[i]
}
/** [index] — место сети в WiFiInfo роутера, по нему пароль запрашивается заново только для «Поделиться». */
data class WifiNetwork(val ssid: String, val band: WifiBand, val enabled: Boolean, val channel: String, val security: String, val guest: Boolean, val index: Int = 0)
data class ParentalProfile(
    val index: Int, val name: String, val enabled: Boolean, val macs: List<String>,
    val days: List<String>, val times: List<String>, val blocksSites: Boolean, val blocksServices: Boolean,
)

data class ParentalControl(val enabled: Boolean, val maxProfiles: Int, val profiles: List<ParentalProfile>)

/** Одна строка маршрута для показа: роутер хранит четыре разных вида; описание по [detail] строит интерфейс. */
data class RouteItem(val id: Int, val title: String, val detail: RouteDetail, val enabled: Boolean)

sealed interface RouteDetail {
    data class Static(val destination: String, val gateway: String) : RouteDetail
    data object Dns : RouteDetail
    /** [sourceIp] пусто и [byMac] false — любой источник; [protocol] пусто — любой протокол. */
    data class Policy(val sourceIp: String, val byMac: Boolean, val protocol: String) : RouteDetail
    data class Rip(val iface: String, val version: String, val mode: String) : RouteDetail
}

data class Routing(
    val staticRoutes: List<RouteItem>, val dnsRoutes: List<RouteItem>,
    val policyRoutes: List<RouteItem>, val rip: List<RouteItem>,
)

data class RouterSnapshot(
    val model: String, val firmware: String, val uptime: Long, val internetUp: Boolean, val linkType: String, val encapsulation: String,
    val wanIP: String, val dns: String, val internetUptime: Long, val downKbps: Long, val upKbps: Long,
    val devices: List<RouterDevice>, val wifi: List<WifiNetwork>, val schema: String,
)

/** Строка из ответа роутера с раскодированными HTML-сущностями: прошивка экранирует &, <, > и кавычки. */
internal fun str(v: Any?): String {
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

internal fun JSONObject?.items(key: String): List<JSONObject> {
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
        RouteItem(i, str(o.opt("Name")).ifEmpty { dest }, RouteDetail.Static(dest, gw), bool(o.opt("Enable")))
    }
    // id уникальны по всему экрану (у групп свои диапазоны), иначе keyed-списки путают строки.
    val dnsRoutes = dns.mapIndexed { i, o -> RouteItem(100 + i, str(o.opt("DomainName")), RouteDetail.Dns, bool(o.opt("Enable"))) }
    val policyRoutes = policy.mapIndexed { i, o ->
        val proto = str(o.opt("Protocol"))
        val src = str(o.opt("SourceIpAddress"))
        val byMac = str(o.opt("SourceMacAddress")).isNotEmpty()
        RouteItem(200 + i, str(o.opt("RouteName")), RouteDetail.Policy(src, byMac, if (proto == "ALL") "" else proto), bool(o.opt("Enable")))
    }
    val ripItems = rip.mapIndexed { i, o ->
        val iface = str(o.opt("Interface")).substringAfterLast(".")
        RouteItem(300 + i, "", RouteDetail.Rip(iface, str(o.opt("Version")), str(o.opt("RipOpMode"))), bool(o.opt("Enable")))
    }
    return Routing(staticRoutes, dnsRoutes, policyRoutes, ripItems)
}

/** Сеть устройства: проводная — отдельная подпись, Wi-Fi — SSID той же полосы и роли (основная/гостевая). */
/**
 * Полоса сети Wi-Fi: прежде всего из OperatingFrequencyBand («2.4GHz», «5GHz»), затем по стандартам, затем по позиции.
 * «ax» есть у обеих полос Wi-Fi 6, поэтому сам по себе на 5 ГГц не указывает.
 */
internal fun wifiBand(item: JSONObject, index: Int, count: Int): WifiBand {
    val freq = str(item.opt("OperatingFrequencyBand"))
    if (freq.startsWith("2.4") || freq.startsWith("2,4")) return WifiBand.GHZ_2_4
    if (freq.startsWith("5") || freq.startsWith("6")) return WifiBand.GHZ_5
    val std = str(item.opt("OperatingStandards")).lowercase().split(",").map { it.trim() }
    if (std.contains("b") || std.contains("g")) return WifiBand.GHZ_2_4
    if (std.contains("a") || std.contains("ac")) return WifiBand.GHZ_5
    return if (index < count / 2) WifiBand.GHZ_2_4 else WifiBand.GHZ_5
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
    return "%02d:%02d".format(java.util.Locale.ROOT, t / 60, t % 60)   // строка уходит роутеру: только латинские цифры
}

/** SSID сети Wi-Fi той же полосы и роли (основная/гостевая); пусто для проводных и когда сеть не найдена. */
internal fun networkName(connection: String, guest: Boolean, wifi: List<WifiNetwork>): String {
    val band = bandOfConnection(connection) ?: return ""
    return wifi.firstOrNull { it.band == band && it.guest == guest }?.ssid.orEmpty()
}

/** Гостевая сеть: по X_ZYXEL_MainSSID, если прошивка его отдаёт; иначе по позиции (основная, гостевая, основная, …). */
internal fun isGuestNetwork(item: JSONObject, index: Int): Boolean =
    if (item.has("X_ZYXEL_MainSSID")) !bool(item.opt("X_ZYXEL_MainSSID")) else index % 2 == 1

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
            str(item.opt("Channel")), str(item.opt("ModeEnabled")), isGuestNetwork(item, index), index,
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
            // Счётчики со стороны роутера: «Sent» — отправлено роутером устройству, то есть скачано устройством.
            long(item.opt("X_ZYXEL_BytesSent")), long(item.opt("X_ZYXEL_BytesReceived")),
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

/** Иконки, которые веб-интерфейс роутера передаёт в DeviceIcon. */
private val ROUTER_ICONS = setOf("imobile", "igame", "ipc", "icamera", "itv", "iothers", "istb", "irouter", "istereo", "iprinter", "iserver", "itelephone")

/**
 * Тело PUT `/cgi-bin/DAL?oid=lanhosts`, как его собирает веб-интерфейс роутера на странице подключённых устройств.
 * Вместе с флагом роутер принимает имя и иконку, поэтому они передаются текущими: иначе устройство переименуется.
 */
internal fun internetBlockingBody(host: JSONObject, blocked: Boolean): JSONObject {
    // Строки — как прислал роутер, без раскодирования сущностей: «&amp;» вернулся бы символом «&», который прошивка не принимает.
    fun raw(key: String) = (host.opt(key) as? String).orEmpty()
    val name = raw("curHostName").ifEmpty { raw("DeviceName") }.ifEmpty { raw("HostName") }
    val icon = raw("icon").lowercase().takeIf { it in ROUTER_ICONS } ?: raw("DeviceIcon").ifEmpty { "iothers" }
    return JSONObject()
        .put("HostName", name)
        .put("DeviceIcon", icon)
        .put("MacAddress", raw("PhysAddress"))
        .put("Internet_Blocking_Enable", blocked)
}

/** Роутер отдаёт Days строкой "Mon,Tue", а на запись подтверждённо принимает массив. */
internal fun daysAsArray(obj: JSONObject): JSONObject {
    val days = obj.opt("Days") as? String ?: return obj
    return obj.put("Days", JSONArray(days.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
}

/** Blocking client for the router's web API; call from a background dispatcher. */
class RouterClient(address: String, storedPins: CertificatePins = MemoryCertificatePins()) : RouterApi {
    private val base: String
    private val host: String
    // Параметр назван иначе, чем свойство: в инициализаторах ниже «pins» должно значить обёртку, а не само хранилище.
    private val pins = SessionPins(storedPins)
    private val cookies = linkedMapOf<String, String>()
    private var aesKey: String? = null
    private var csrf: String? = null
    /** Клиент закрыт выходом, отказом во входе или концом сессии: ни запросов, ни повторного входа с сохранённым паролем. */
    @Volatile private var closed = false

    /** Растёт при каждом входе: параллельные восстановления сессии не должны входить повторно. */
    @Volatile override var sessionGeneration = 0
        private set

    init {
        val (h, port) = parseAddress(address) ?: throw RouterException.BadAddress
        if (!isPrivateIPv4(h)) throw RouterException.BadAddress
        host = h
        base = "https://$h" + (port?.let { ":$it" } ?: "")
    }

    /** Закрывает клиента без обращения к роутеру: запросы, ещё стоящие в очереди, получат [RouterException.SessionExpired]. */
    override fun close() { closed = true }

    // Сертификат самоподписанный; соединения идут только на частный адрес, который ввёл пользователь.
    private val ssl: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinningTrustManager(host, pins)), SecureRandom()) }
    }

    // Фабрика и проверка имени — одни на клиента: Android переиспользует соединение, только если они те же самые объекты.
    // Новые на каждый запрос означали бы новое TLS-рукопожатие (на VMG8623 — около 0,2 с, возобновления сессий нет).
    private val socketFactory by lazy { ssl.socketFactory }
    // В сертификате роутера нет его IP: имя сверяем с адресом, который ввёл пользователь, а подлинность — по отпечатку.
    private val hostnameVerifier = javax.net.ssl.HostnameVerifier { requested, _ -> requested == host }

    private class Reply(val status: Int, val json: JSONObject?, val text: String? = null)

    // Сериализует доступ к сессии (cookies/csrf/aesKey): опрос и действия пользователя идут из разных
    // корутин на Dispatchers.IO параллельно. Без этого — ConcurrentModificationException и потеря CSRF.
    @Synchronized
    private fun send(method: String, path: String, body: String? = null, timeoutMs: Int = 30_000, evenIfClosed: Boolean = false): Reply {
        if (closed && !evenIfClosed) throw RouterException.SessionExpired
        var connected = false
        try {
            val connection = URL(base + path).openConnection() as HttpsURLConnection
            connection.sslSocketFactory = socketFactory
            // Перенаправление увело бы запрос (и пароль) на другой адрес.
            connection.instanceFollowRedirects = false
            connection.hostnameVerifier = hostnameVerifier
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
            connected = true
            connection.headerFields.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }.values.flatten().forEach { header ->
                val pair = header.substringBefore(";")
                cookies[pair.substringBefore("=").trim()] = pair.substringAfter("=").trim()
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            // Предел размера: устройство по частному адресу (в том числе при проверке ещё не закреплённого) не должно
            // занять всю память бесконечным ответом. Самое большое у роутера — журнал, это сотни килобайт.
            val bytes = stream?.use { it.readAtMost(MAX_RESPONSE_BYTES + 1) } ?: ByteArray(0)
            if (bytes.size > MAX_RESPONSE_BYTES) throw RouterException.BadResponse
            val text = String(bytes, Charsets.UTF_8)
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
            generateSequence<Throwable>(e) { it.cause }.filterIsInstance<PinMismatchException>().firstOrNull()?.let {
                throw RouterException.CertificateChanged(it.fingerprint)
            }
            // Ответ получен, но не читается (расшифровка, обрыв) — роутер на связи, это не «недоступен»:
            // иначе опрос продолжился бы и снова входил с паролем. Текст исключения пользователю ничего не скажет.
            throw if (connected || e !is java.io.IOException) RouterException.BadResponse else RouterException.Unreachable
        }
    }

    override fun basicInfo(): RouterBasic {
        val d = send("GET", "/getBasicInformation", timeoutMs = 8_000).json
        return RouterBasic(str(d?.opt("ModelName")), str(d?.opt("SoftwareVersion")), str(d?.opt("CurrentLanguage")))
    }

    // Весь вход под тем же монитором, что и send: иначе параллельный запрос застанет сессию наполовину сброшенной.
    @Synchronized
    override fun login(username: String, password: String, onStep: (LoginStep) -> Unit) {
        if (closed) throw RouterException.SessionExpired
        try {
            performLogin(username, password, onStep)
            pins.commit()   // сертификат закрепляется только у роутера, который принял пароль
            sessionGeneration++
        } catch (e: RouterException) {
            // Отказ во входе закрывает клиента: запросы, ждущие своей очереди, не повторят вход с тем же паролем.
            if (e !is RouterException.Unreachable) closed = true
            throw if (e is RouterException.BadResponse) RouterException.LoginUnreadable else e
        } catch (e: Exception) {
            closed = true
            throw RouterException.BadLoginData   // битый RSA-ключ и т. п.
        }
    }

    /** Повторный вход, если с момента [generation] сессию ещё никто не восстановил. */
    @Synchronized
    override fun reloginIfUnchanged(generation: Int, username: String, password: String) {
        if (generation == sessionGeneration) login(username, password)
    }

    private fun performLogin(username: String, password: String, onStep: (LoginStep) -> Unit) {
        onStep(LoginStep.CONNECTING)
        val basic = basicInfo()
        onStep(LoginStep.SIGNING_IN)
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
        // Роутер уже ответил на два запроса выше: обрыв здесь значит, что пароль мог дойти. Повторять вход по кругу нельзя.
        val reply = try { send("POST", "/UserLogin", body) } catch (_: RouterException.Unreachable) { throw RouterException.LoginRejected("no response") }
        if (reply.status !in 200..299) {
            val result = str(reply.json?.opt("result"))
            throw when (result) {
                "Invalid Username or Password", "HTTP permission denied" -> RouterException.InvalidCredentials
                "Locked User" -> RouterException.Locked(str(reply.json?.opt("replyMsg")).substringAfterLast("=").toIntOrNull() ?: 0)
                "Duplicated login", "A user exist" -> RouterException.DuplicateSession
                "Maxium number of login account has reached" -> RouterException.TooManySessions
                else -> RouterException.LoginRejected(result.ifEmpty { "HTTP ${reply.status}" })
            }
        }
        csrf = str(reply.json?.opt("sessionkey"))
    }

    private fun dal(oid: String): JSONObject {
        val reply = send("GET", "/cgi-bin/DAL?oid=$oid")
        if (reply.status in setOf(401, 403, 504)) throw RouterException.SessionExpired
        val first = reply.json?.takeIf { str(it.opt("result")) == "ZCFG_SUCCESS" }?.optJSONArray("Object")?.optJSONObject(0)
        if (reply.status != 200 || first == null) throw RouterException.NoData(oid, reply.status)
        return first
    }

    override fun snapshot(): RouterSnapshot = parseSnapshot(dal("cardpage_status"), dal("lanhosts"))

    /** Имя, защита и пароль одной сети — только по просьбе пользователя поделиться ею; в снимок пароль не попадает. */
    override fun wifiCredentials(network: WifiNetwork): WifiCredentials =
        parseWifiCredentials(dal("cardpage_status"), network.index, network.ssid) ?: throw RouterException.NoData("cardpage_status", 200)

    private fun dalObjects(oid: String): List<JSONObject> {
        val reply = send("GET", "/cgi-bin/DAL?oid=$oid")
        if (reply.status in setOf(401, 403, 504)) throw RouterException.SessionExpired
        val arr = reply.json?.takeIf { str(it.opt("result")) == "ZCFG_SUCCESS" }?.optJSONArray("Object")
        if (reply.status != 200 || arr == null) throw RouterException.NoData(oid, reply.status)
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
        if (reply.status !in 200..299 || str(reply.json?.opt("result")) != "ZCFG_SUCCESS") throw RouterException.WriteRejected(oid, reply.status)
    }

    override fun parentalControl(): ParentalControl = parseParental(dalObjects("paren_ctl"))

    override fun setParentalEnabled(on: Boolean) = dalWrite("PUT", "paren_ctl", JSONObject().put("PrentalCtlEnable", if (on) "1" else "0"))

    /** Включает или выключает профиль: читает его текущий объект и возвращает целиком с изменённым флагом. */
    override fun setProfileEnabled(index: Int, on: Boolean) {
        val obj = dalObjects("paren_ctl").firstOrNull { long(it.opt("Index")).toInt() == index }
            ?: throw RouterException.ProfileNotFound
        obj.put("pcpEnable", if (on) 1 else 0)
        dalWrite("PUT", "paren_ctl", daysAsArray(obj))
    }

    /** Создаёт (index == null) или изменяет профиль. MAC с двоеточиями, дни кодами Mon..Sun,
     *  timeRangeList — готовая строка интервалов. При изменении читается текущий объект и правятся
     *  только эти поля, чтобы не стереть фильтры сайтов и сервисов из веб-интерфейса. */
    override fun saveProfile(index: Int?, name: String, macs: List<String>, days: List<String>, timeRangeList: String, enabled: Boolean) {
        if (index != null) {
            val obj = dalObjects("paren_ctl").firstOrNull { long(it.opt("Index")).toInt() == index }
                ?: throw RouterException.ProfileNotFound
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

    /** Читает устройство заново (имя и иконка могли поменяться в веб-интерфейсе) и меняет только флаг блокировки. */
    override fun setInternetBlocked(mac: String, blocked: Boolean) {
        val host = dal("lanhosts").items("lanhosts").firstOrNull { str(it.opt("PhysAddress")).equals(mac, ignoreCase = true) }
            ?: throw RouterException.DeviceNotFound
        dalWrite("PUT", "lanhosts", internetBlockingBody(host, blocked))
    }

    override fun deleteProfile(index: Int) {
        val reply = send("DELETE", "/cgi-bin/DAL?oid=paren_ctl&Index=$index")
        if (reply.status in setOf(401, 403, 504)) throw RouterException.SessionExpired
        if (reply.status !in 200..299 || str(reply.json?.opt("result")) != "ZCFG_SUCCESS") throw RouterException.DeleteRejected(reply.status)
    }

    /**
     * Маршруты из четырёх разделов. Прошивки без какого-то раздела (нет RIP или маршрутов по DNS) отвечают на него
     * ошибкой — тогда раздел пуст, а не весь экран. Если не прочитался ни один, ошибка уходит наверх.
     */
    override fun routing(onProgress: (done: Int) -> Unit): Routing {
        val parts = listOf("static_route", "dns_route", "policy_route", "rip").mapIndexed { i, oid ->
            runCatching { dalObjects(oid) }.also { onProgress(i + 1) }
        }
        parts.mapNotNull { it.exceptionOrNull() }.firstOrNull { it !is RouterException.NoData }?.let { throw it }
        if (parts.all { it.isFailure }) throw parts.first().exceptionOrNull()!!
        val (st, dns, policy, rip) = parts.map { it.getOrDefault(emptyList()) }
        return parseRouting(st, dns, policy, rip)
    }

    /** Читает системный журнал (iid [1] — системный, [2] — безопасность). */
    override fun systemLog(security: Boolean): List<LogEntry> {
        val iid = if (security) "2" else "1"
        val reply = send("GET", "/cgi-bin/LogResult?action=GET_LOG&oid=RDM_OID_LOG_CLASSIFY&iid=[$iid,0,0,0,0,0]")
        if (reply.status in setOf(401, 403, 504)) throw RouterException.SessionExpired
        if (reply.status !in 200..299) throw RouterException.LogUnavailable(reply.status)
        return parseLog(reply.text)
    }

    /** Статистика линии со страницы «xDSL Statistics» (только чтение). Не DSL или другая прошивка — null, это не ошибка. */
    override fun lineStats(): LineStats? {
        val reply = send("GET", "/cgi-bin/xDSLStatistics_handle?line=0")
        if (reply.status in setOf(401, 403, 504)) throw RouterException.SessionExpired
        if (reply.status !in 200..299) return null
        return parseLineStats(lineStatsText(reply.text), System.currentTimeMillis() / 1000)
    }

    override fun reboot() {
        val status = send("POST", "/cgi-bin/Reboot").status
        if (status in setOf(401, 403, 504)) throw RouterException.SessionExpired
        if (status !in 200..299) throw RouterException.RebootRejected(status)
    }

    @Synchronized
    override fun logout() {
        val wasOpen = sessionGeneration > 0
        closed = true
        if (wasOpen) runCatching { send("POST", "/cgi-bin/UserLogout", timeoutMs = 3_000, evenIfClosed = true) }
        cookies.clear()
        csrf = null
        aesKey = null
    }

    private fun randomBase64() = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    /** AES-256-CBC. The router sends 32-byte IVs and, like CryptoJS, only the first block is used. */
    private fun aes(mode: Int, input: ByteArray, key: String, iv: String): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val ivBytes = Base64.getDecoder().decode(iv)
        require(ivBytes.size >= 16) { "IV shorter than 16 bytes" }   // не дополнять нулями молча
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
        /** Октеты без ведущих нулей: «010.0.0.1» часть систем читает как восьмеричное 8.0.0.1 — публичный адрес. */
        private val ADDRESS = Regex("""((?:0|[1-9]\d{0,2})(?:\.(?:0|[1-9]\d{0,2})){3})(?::([1-9]\d{0,4}))?""")
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

        /** IPv4 и необязательный порт; всё остальное (имя, «@», путь, схема) — null: адрес должен вести только на этот IP. */
        fun parseAddress(address: String): Pair<String, Int?>? {
            val m = ADDRESS.matchEntire(address.trim()) ?: return null
            val port = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
            if (port != null && port !in 1..65535) return null
            return m.groupValues[1] to port
        }

        /** Хост без порта: по нему хранятся отпечатки сертификатов. */
        fun hostOf(address: String): String = parseAddress(address)?.first ?: address.trim().substringBefore(":")

        fun isPrivateIPv4(host: String): Boolean {
            val raw = host.split(".")
            if (raw.any { it.length > 1 && it.startsWith('0') }) return false   // см. ADDRESS: ведущий ноль — восьмеричное
            val parts = raw.map { it.toIntOrNull() ?: -1 }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return false
            // Loopback — только для отладки с мок-роутером: в релизе там мог бы слушать другой процесс на телефоне.
            return parts[0] == 10 || (parts[0] == 127 && BuildConfig.DEBUG) || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31)
        }
    }
}
