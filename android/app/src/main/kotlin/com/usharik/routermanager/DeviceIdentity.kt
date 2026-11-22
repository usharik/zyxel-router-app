package com.usharik.routermanager

import android.content.Context
import java.io.InputStream
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * MAC с битом «локально назначенный адрес» (второй младший бит первого байта): так iPhone, Android и Windows
 * по умолчанию прячут в Wi-Fi настоящий адрес. Производителя по такому адресу не узнать.
 */
fun isPrivateMac(mac: String): Boolean {
    val first = normalizeMAC(mac)?.take(2)?.toIntOrNull(16) ?: return false
    return first and 0x02 != 0 && first and 0x01 == 0
}

/**
 * Производитель сетевой карты по первой половине MAC — по реестрам IEEE (MA-L, MA-M, MA-S), встроенным в приложение
 * (assets/oui.dat — gzip, собирается tools/build_oui.py; не .gz, иначе сборка Android его распакует и переименует). Работает без сети: адреса устройств никуда не отправляются.
 */
class OuiDatabase(private val open: () -> InputStream) {
    /** Префиксы по длине (9, 7 и 6 шестнадцатеричных цифр) отсортированы для двоичного поиска; номер имени — рядом. */
    private class Table(val names: Array<String>, val prefixes: Map<Int, LongArray>, val nameIndex: Map<Int, IntArray>)

    @Volatile private var table: Table? = null

    val loaded: Boolean get() = table != null

    /** Читает справочник (около 0,2 с); вызывать из фонового потока. Ошибка чтения — просто без производителей. */
    @Synchronized
    fun load(): Boolean {
        if (table == null) table = runCatching { read() }.onFailure { Telemetry.error("oui", it) }.getOrNull()
        return table != null
    }

    private fun read(): Table = GZIPInputStream(open()).bufferedReader().use { r ->
        val count = r.readLine().trim().toInt()
        val names = Array(count) { r.readLine() }
        val byLength = HashMap<Int, MutableList<Pair<Long, Int>>>()
        r.forEachLine { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) byLength.getOrPut(tab) { ArrayList() } += line.substring(0, tab).toLong(16) to line.substring(tab + 1).toInt()
        }
        val sorted = byLength.mapValues { (_, list) -> list.sortedBy { it.first } }
        Table(names, sorted.mapValues { (_, l) -> LongArray(l.size) { l[it].first } }, sorted.mapValues { (_, l) -> IntArray(l.size) { l[it].second } })
    }

    /** Производитель или null: справочник не загружен, адрес частный или префикса нет в реестре. */
    fun vendor(mac: String): String? {
        val t = table ?: return null
        if (isPrivateMac(mac)) return null
        val hex = normalizeMAC(mac)?.replace(":", "") ?: return null
        // Сначала самые длинные префиксы: блоки MA-S и MA-M выделены внутри чужих MA-L.
        for (length in listOf(9, 7, 6)) {
            val prefixes = t.prefixes[length] ?: continue
            val i = prefixes.binarySearch(hex.substring(0, length).toLong(16))
            if (i >= 0) return t.names[t.nameIndex.getValue(length)[i]]
        }
        return null
    }

    companion object {
        fun fromAssets(context: Context) = OuiDatabase { context.assets.open("oui.dat") }
    }
}

/** Службы, которые устройство объявляет в сети. Подписи — в интерфейсе. */
enum class NetService { AIRPLAY, CAST, PRINTER, SCANNER, HOMEKIT, SPOTIFY, FILES, WEB, SSH, MEDIA_SERVER, MEDIA_PLAYER, APPLE, ANDROID_TV, FIRE_TV, ROUTER }

/**
 * Что устройство само сообщило о себе в сети (mDNS, SSDP/UPnP, NetBIOS). Поля пустые, если ничего не известно.
 * [name] — имя, которое дал владелец («Телевизор в гостиной», «MacBook Анны»); [hostName] — сетевое имя.
 */
data class NetworkIdentity(
    val name: String = "",
    val hostName: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val services: Set<NetService> = emptySet(),
    val workgroup: String = "",
) {
    val isEmpty: Boolean get() = this == NetworkIdentity()

    /** Сведения из двух источников: непустые поля этого важнее, службы складываются. */
    operator fun plus(other: NetworkIdentity) = NetworkIdentity(
        name.ifEmpty { other.name }, hostName.ifEmpty { other.hostName }, manufacturer.ifEmpty { other.manufacturer },
        model.ifEmpty { other.model }, services + other.services, workgroup.ifEmpty { other.workgroup },
    )

    /** Строки от устройств в сети — чужие данные: без управляющих символов и не длиннее разумного. */
    fun bounded(): NetworkIdentity {
        fun clean(v: String) = v.filter { !it.isISOControl() }.trim().take(MAX_FIELD)
        return NetworkIdentity(clean(name), clean(hostName), clean(manufacturer), clean(model), services, clean(workgroup))
    }

    fun toJson(): JSONObject = JSONObject().put("name", name).put("host", hostName).put("mfr", manufacturer).put("model", model)
        .put("services", JSONArray(services.map { it.name })).put("wg", workgroup)

    companion object {
        private const val MAX_FIELD = 80

        fun fromJson(o: JSONObject) = NetworkIdentity(
            o.optString("name"), o.optString("host"), o.optString("mfr"), o.optString("model"),
            o.optJSONArray("services")?.let { a -> (0 until a.length()).mapNotNull { i -> NetService.entries.firstOrNull { it.name == a.optString(i) } }.toSet() }.orEmpty(),
            o.optString("wg"),
        )
    }
}

/** Сведения по MAC между запусками: устройство, ответившее однажды, остаётся опознанным и офлайн. */
internal fun identitiesToJson(map: Map<String, NetworkIdentity>): String =
    JSONObject().apply { map.forEach { (mac, id) -> put(mac, id.toJson()) } }.toString()

internal fun identitiesFromJson(json: String?): Map<String, NetworkIdentity> = runCatching {
    val o = JSONObject(json ?: return emptyMap())
    o.keys().asSequence().associateWith { NetworkIdentity.fromJson(o.getJSONObject(it)) }
}.getOrDefault(emptyMap())

/** Имя для списка: если роутер не знает имени (показывает MAC), берём то, что устройство сообщило о себе. */
fun displayName(d: RouterDevice, id: NetworkIdentity?): String {
    val unnamed = d.name.isEmpty() || d.name.equals(d.mac, ignoreCase = true)
    if (!unnamed || id == null) return d.name
    return id.name.ifEmpty { id.hostName }.ifEmpty { listOf(id.manufacturer, id.model).filter { it.isNotEmpty() }.joinToString(" ") }.ifEmpty { d.name }
}

/** Тип устройства: роутер угадывает по DHCP; если не смог — по службам, модели и производителю. */
fun refinedType(d: RouterDevice, id: NetworkIdentity?, vendor: String?): DeviceType {
    if (d.type != DeviceType.UNKNOWN) return d.type
    if (id != null) {
        val s = id.services
        val text = listOf(id.model, id.name, id.hostName).joinToString(" ").lowercase()
        when {
            NetService.PRINTER in s || NetService.SCANNER in s -> return DeviceType.PRINTER
            NetService.CAST in s || NetService.ANDROID_TV in s || NetService.FIRE_TV in s || NetService.MEDIA_PLAYER in s ||
                listOf("appletv", "apple tv", " tv", "tv ").any { it in " $text " } -> return DeviceType.TV
            listOf("iphone", "ipad", "phone", "galaxy", "pixel").any { it in text } -> return DeviceType.PHONE
            listOf("macbook", "imac", "mac mini", "laptop", "desktop").any { it in text } || id.workgroup.isNotEmpty() -> return DeviceType.COMPUTER
            NetService.HOMEKIT in s -> return DeviceType.IOT
        }
        deviceType("", id.hostName).takeIf { it != DeviceType.UNKNOWN }?.let { return it }
    }
    val v = vendor.orEmpty().lowercase()
    return when {
        listOf("espressif", "tuya", "shelly", "itead", "hikvision", "dahua", "ezviz", "ring ", "nest labs", "signify", "philips lighting").any { it in "$v " } -> DeviceType.IOT
        listOf("brother", "seiko epson", "canon", "xerox", "kyocera", "lexmark").any { it in v } -> DeviceType.PRINTER
        listOf("roku", "lg electronics", "vizio", "tcl", "hisense").any { it in v } -> DeviceType.TV
        else -> DeviceType.UNKNOWN
    }
}

/** Модель Apple по внутреннему коду из mDNS: «MacBookPro18,3» → «MacBook Pro», «iPhone15,2» → «iPhone». */
internal fun appleModelName(code: String): String? {
    val family = code.takeWhile { !it.isDigit() && it != ',' }
    return when (family) {
        "MacBookPro" -> "MacBook Pro"
        "MacBookAir" -> "MacBook Air"
        "MacBook" -> "MacBook"
        "iMac", "iMacPro" -> "iMac"
        "Macmini" -> "Mac mini"
        "MacPro" -> "Mac Pro"
        "Mac" -> "Mac"
        "iPhone" -> "iPhone"
        "iPad" -> "iPad"
        "iPod" -> "iPod"
        "AppleTV" -> "Apple TV"
        "AudioAccessory" -> "HomePod"
        "Watch" -> "Apple Watch"
        else -> null
    }
}
