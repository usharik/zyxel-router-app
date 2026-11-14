package com.usharik.zyxelrouter

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import javax.net.SocketFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Опрос домашней сети с телефона: устройства сами рассказывают о себе по mDNS (Apple, Chromecast, принтеры, умный дом),
 * SSDP/UPnP (телевизоры, приставки, медиасерверы) и NetBIOS (компьютеры Windows). Порты не сканируются, адреса —
 * только частные; ответы остаются на телефоне. Ответ приходит обычным пакетом на порт телефона, поэтому не нужны
 * ни приём широковещательных пакетов, ни дополнительные разрешения.
 */
interface LocalDiscovery {
    /** [ips] — адреса устройств из ответа роутера (для NetBIOS); ответ — сведения по IP. */
    suspend fun discover(ips: List<String>): DiscoveryResult
}

/** [onLan] = false: телефон не в Wi-Fi или Ethernet, опрашивать некого. */
data class DiscoveryResult(val identities: Map<String, NetworkIdentity>, val onLan: Boolean = true)

// MARK: - mDNS

/** Типы служб, о которых спрашиваем сразу; остальные узнаём из перечня «_services._dns-sd._udp». */
internal val MDNS_TYPES = listOf(
    "_services._dns-sd._udp", "_device-info._tcp", "_googlecast._tcp", "_airplay._tcp", "_raop._tcp", "_companion-link._tcp",
    "_ipp._tcp", "_ipps._tcp", "_printer._tcp", "_pdl-datastream._tcp", "_scanner._tcp", "_uscan._tcp", "_hap._tcp",
    "_spotify-connect._tcp", "_smb._tcp", "_afpovertcp._tcp", "_workstation._tcp", "_http._tcp", "_ssh._tcp",
    "_androidtvremote2._tcp", "_amzn-wplay._tcp",
)

/** Метка типа службы, которую можно спросить: «_googlecast», «_matter». */
private val SERVICE_LABEL = Regex("_[A-Za-z0-9-]{1,40}")

internal fun serviceOf(type: String): NetService? = when (type.lowercase()) {
    "_airplay", "_raop" -> NetService.AIRPLAY
    "_googlecast" -> NetService.CAST
    "_ipp", "_ipps", "_printer", "_pdl-datastream" -> NetService.PRINTER
    "_scanner", "_uscan", "_uscans" -> NetService.SCANNER
    "_hap" -> NetService.HOMEKIT
    "_spotify-connect" -> NetService.SPOTIFY
    "_smb", "_afpovertcp", "_nfs" -> NetService.FILES
    "_http", "_https" -> NetService.WEB
    "_ssh", "_sftp-ssh" -> NetService.SSH
    "_companion-link", "_device-info", "_apple-mobdev2", "_sleep-proxy" -> NetService.APPLE
    "_androidtvremote", "_androidtvremote2" -> NetService.ANDROID_TV
    "_amzn-wplay" -> NetService.FIRE_TV
    else -> null
}

/** Запрос PTR на несколько типов служб. Бит QU (ответить напрямую) и порт отправителя не 5353 — ответ придёт телефону. */
internal fun mdnsQuery(types: List<String>, id: Int = 0): ByteArray {
    val out = ByteArrayOutputStream()
    fun u16(v: Int) { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
    u16(id); u16(0); u16(types.size); u16(0); u16(0); u16(0)
    for (t in types) {
        for (label in "$t.local".split('.')) {
            val bytes = label.toByteArray()
            out.write(bytes.size); out.write(bytes)
        }
        out.write(0)
        u16(12)        // PTR
        u16(0x8001)    // IN + просьба ответить напрямую
    }
    return out.toByteArray()
}

/** Запись DNS: имя — метками (имя экземпляра может содержать точки), данные зависят от типа. */
internal data class DnsRecord(val name: List<String>, val type: Int, val target: List<String> = emptyList(), val txt: Map<String, String> = emptyMap(), val address: String = "")

/** Разбирает ответ DNS (все разделы). Битый пакет — пустой список, а не исключение: в сети бывает всякое. */
internal fun parseDns(buf: ByteArray, length: Int = buf.size): List<DnsRecord> = runCatching {
    fun u8(p: Int) = buf[p].toInt() and 0xFF
    fun u16(p: Int) = u8(p) shl 8 or u8(p + 1)
    /** Имя с учётом сжатия; возвращает метки и позицию сразу после имени в исходном месте. */
    fun name(start: Int): Pair<List<String>, Int> {
        val labels = mutableListOf<String>()
        var p = start
        var end = -1
        var jumps = 0
        while (true) {
            require(p < length)
            val len = u8(p)
            when {
                len == 0 -> { if (end < 0) end = p + 1; break }
                len and 0xC0 == 0xC0 -> {
                    require(++jumps < 32)
                    if (end < 0) end = p + 2
                    p = (len and 0x3F) shl 8 or u8(p + 1)
                }
                else -> {
                    require(p + 1 + len <= length)
                    labels += String(buf, p + 1, len, Charsets.UTF_8)
                    p += 1 + len
                }
            }
        }
        return labels to end
    }
    val qd = u16(4)
    val rr = u16(6) + u16(8) + u16(10)
    var p = 12
    repeat(qd) { p = name(p).second + 4 }
    val out = mutableListOf<DnsRecord>()
    repeat(rr) {
        val (owner, after) = name(p)
        val type = u16(after)
        val rdLength = u16(after + 8)
        val data = after + 10
        require(data + rdLength <= length)
        out += when (type) {
            12 -> DnsRecord(owner, type, target = name(data).first)
            33 -> DnsRecord(owner, type, target = name(data + 6).first)
            16 -> {
                val txt = linkedMapOf<String, String>()
                var t = data
                while (t < data + rdLength) {
                    val len = u8(t)
                    val entry = String(buf, t + 1, minOf(len, data + rdLength - t - 1), Charsets.UTF_8)
                    if (entry.isNotEmpty()) txt.putIfAbsent(entry.substringBefore('=').lowercase(), entry.substringAfter('=', ""))
                    t += 1 + len
                }
                DnsRecord(owner, type, txt = txt)
            }
            1 -> DnsRecord(owner, type, address = if (rdLength == 4) (0 until 4).joinToString(".") { u8(data + it).toString() } else "")
            else -> DnsRecord(owner, type)
        }
        p = data + rdLength
    }
    out
}.getOrDefault(emptyList())

/** Тип службы из полного имени экземпляра: ["Living Room", "_googlecast", "_tcp", "local"] → "_googlecast". */
private fun serviceTypeOf(name: List<String>): String? {
    val i = name.indexOfFirst { it == "_tcp" || it == "_udp" }
    return if (i >= 1) name[i - 1] else null
}

/**
 * Сведения из одного ответа mDNS. Устройство обычно отвечает за себя; если за него ответил кто-то другой (Apple
 * sleep proxy), адрес берём из записи A его сетевого имени. Второй результат — типы служб из общего перечня.
 */
internal fun identitiesFromMdns(records: List<DnsRecord>, sender: String): Pair<Map<String, NetworkIdentity>, Set<String>> {
    val addresses = records.filter { it.type == 1 && it.address.isNotEmpty() }.associate { it.name.joinToString(".").lowercase() to it.address }
    val types = records.filter { it.type == 12 && it.name.firstOrNull() == "_services" }.mapNotNull { serviceTypeOf(it.target) }.toSet()
    // Экземпляры служб: из PTR (тип → экземпляр), SRV и TXT.
    val instances = (records.filter { it.type == 12 && it.name.firstOrNull() != "_services" }.map { it.target } +
        records.filter { it.type == 33 || it.type == 16 }.map { it.name }).filter { serviceTypeOf(it) != null && it.size >= 4 }.distinct()
    val result = mutableMapOf<String, NetworkIdentity>()
    // Имена по надёжности: имя Chromecast из TXT, имя устройства Apple, затем остальные службы.
    val nameRank = mutableMapOf<String, Int>()
    fun srvOf(instance: List<String>) = records.firstOrNull { it.type == 33 && it.name == instance }?.target
    // Адрес по имени устройства: у «_device-info» нет SRV, но то же имя есть у других его служб.
    val ipByLabel = instances.mapNotNull { i -> srvOf(i)?.let { addresses[it.joinToString(".").lowercase()] }?.let { i.first() to it } }.toMap()
    for (instance in instances) {
        val type = serviceTypeOf(instance)!!
        val label = instance.first()
        val srv = srvOf(instance)
        val txt = records.filter { it.type == 16 && it.name == instance }.fold(emptyMap<String, String>()) { a, r -> a + r.txt }
        val host = srv?.firstOrNull()?.takeIf { it.isNotEmpty() }.orEmpty()
        val ip = srv?.let { addresses[it.joinToString(".").lowercase()] } ?: ipByLabel[label] ?: sender
        val appleModel = (txt["model"] ?: txt["am"])?.let(::appleModelName)
        val model = appleModel ?: txt["md"] ?: txt["ty"] ?: txt["usb_mdl"] ?: txt["model"].orEmpty()
        val manufacturer = when {
            appleModel != null -> "Apple"
            else -> txt["usb_mfg"] ?: txt["manufacturer"] ?: txt["mfg"] ?: ""
        }
        val (name, rank) = when (type) {
            "_googlecast" -> (txt["fn"] ?: "") to 3
            "_device-info", "_companion-link", "_airplay" -> label to 2
            "_raop" -> label.substringAfter('@') to 1   // «AABBCCDDEEFF@Гостиная»
            "_http", "_workstation" -> "" to 0          // часто служебные имена вроде «Web Interface»
            else -> label to 1
        }
        val found = NetworkIdentity(
            hostName = host.removeSuffix(".local"), manufacturer = manufacturer, model = model,
            services = setOfNotNull(serviceOf(type)),
        )
        result[ip] = (result[ip] ?: NetworkIdentity()) + found
        if (name.isNotBlank() && rank > (nameRank[ip] ?: -1)) {
            nameRank[ip] = rank
            result[ip] = result.getValue(ip).copy(name = name.trim())
        }
    }
    // Ответ без служб, но с записью A своего имени: хотя бы сетевое имя.
    for ((host, ip) in addresses) {
        val current = result[ip] ?: NetworkIdentity()
        if (current.hostName.isEmpty()) result[ip] = current.copy(hostName = host.removeSuffix(".local"))
    }
    return result to types
}

// MARK: - SSDP / UPnP

internal fun ssdpSearch(target: String): ByteArray =
    "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $target\r\n\r\n".toByteArray()

/** Заголовки ответа на M-SEARCH (ключи в нижнем регистре); null — не ответ «200 OK». */
internal fun parseSsdpResponse(text: String): Map<String, String>? {
    val lines = text.split("\r\n", "\n")
    if (!lines.first().startsWith("HTTP/1.1 200")) return null
    return lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
}

/** Адрес описания устройства: только http и только тот же IP, что ответил, — иначе ответ мог бы отправить телефон куда угодно. */
internal fun descriptionUrl(location: String?, sender: String): URL? {
    val url = runCatching { URL(location ?: return null) }.getOrNull() ?: return null
    return url.takeIf { it.protocol == "http" && it.host == sender && RouterClient.isPrivateIPv4(sender) }
}

private val xmlEntities = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'")

private fun xmlText(s: String): String {
    var t = s.trim()
    xmlEntities.forEach { (k, v) -> t = t.replace(k, v) }
    // Номер символа от устройства может быть любым: недопустимый просто пропускаем, а не роняем разбор.
    return Regex("&#(\\d+);").replace(t) { m -> m.groupValues[1].toIntOrNull()?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: "" }.trim()
}

/** Описание устройства UPnP: имя, производитель, модель (первое, то есть корневое устройство) и роли всех вложенных. */
internal fun parseUpnpDescription(xml: String): NetworkIdentity {
    fun first(tag: String) = Regex("<$tag>([^<]*)</$tag>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)?.let(::xmlText).orEmpty()
    val roles = Regex("<deviceType>([^<]*)</deviceType>", RegexOption.IGNORE_CASE).findAll(xml).mapNotNull { m ->
        val t = m.groupValues[1].lowercase()
        when {
            "mediarenderer" in t -> NetService.MEDIA_PLAYER
            "mediaserver" in t -> NetService.MEDIA_SERVER
            "internetgatewaydevice" in t || "wandevice" in t -> NetService.ROUTER
            "printer" in t -> NetService.PRINTER
            "scanner" in t -> NetService.SCANNER
            else -> null
        }
    }.toSet()
    val model = first("modelName").let { name -> first("modelNumber").takeIf { it.isNotEmpty() && it !in name }?.let { "$name $it" } ?: name }
    return NetworkIdentity(name = first("friendlyName"), manufacturer = first("manufacturer"), model = model, services = roles)
}

/**
 * GET по HTTP без системного клиента: Android запрещает приложениям незашифрованный HTTP (targetSdk ≥ 28), а описания
 * UPnP отдаются только так. Один общий срок [deadline] на соединение, запрос и чтение — медленное устройство,
 * отдающее по байту, не задержит опрос. Ответ — тело при «200», иначе null.
 */
internal fun httpGet(url: URL, deadline: Long, sockets: SocketFactory = SocketFactory.getDefault(), limit: Int = 64 * 1024): String? {
    fun left() = (deadline - System.currentTimeMillis()).toInt()
    if (left() <= 0 || url.protocol != "http") return null
    val port = if (url.port > 0) url.port else 80
    sockets.createSocket().use { socket ->
        socket.connect(InetSocketAddress(url.host, port), left().coerceAtLeast(1))
        socket.getOutputStream().write("GET ${url.file.ifEmpty { "/" }} HTTP/1.0\r\nHost: ${url.host}:$port\r\nConnection: close\r\n\r\n".toByteArray())
        val input = socket.getInputStream()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (out.size() < limit) {
            val remaining = left()
            if (remaining <= 0) return null
            socket.soTimeout = remaining
            val n = try { input.read(chunk, 0, minOf(chunk.size, limit - out.size())) } catch (_: SocketTimeoutException) { return null }
            if (n < 0) break
            out.write(chunk, 0, n)
        }
        val text = String(out.toByteArray(), Charsets.UTF_8)
        val status = text.substringBefore("\r\n").split(' ')
        if (!status[0].startsWith("HTTP/1.") || status.getOrNull(1) != "200" || "\r\n\r\n" !in text) return null
        return text.substringAfter("\r\n\r\n")
    }
}

// MARK: - NetBIOS

/** Запрос состояния узла NetBIOS (NBSTAT) с именем «*»: компьютер Windows (или Samba) отвечает списком своих имён. */
internal fun netbiosStatusQuery(id: Int): ByteArray {
    val out = ByteArrayOutputStream()
    fun u16(v: Int) { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
    u16(id); u16(0); u16(1); u16(0); u16(0); u16(0)
    out.write(32)
    out.write(("CK" + "A".repeat(30)).toByteArray())   // «*» и 15 нулей в кодировке NetBIOS
    out.write(0)
    u16(0x21); u16(1)
    return out.toByteArray()
}

/** Имя компьютера и рабочая группа из ответа NBSTAT; null — пакет не тот или пустой. */
internal fun parseNetbiosStatus(buf: ByteArray, length: Int = buf.size): NetworkIdentity? = runCatching {
    fun u8(p: Int) = buf[p].toInt() and 0xFF
    if (length < 57 || u8(2) and 0x80 == 0) return null   // не ответ
    var p = 12
    while (u8(p) != 0) p += 1 + u8(p)
    p += 1 + 2 + 2 + 4 + 2   // конец имени, тип, класс, TTL, длина данных
    val count = u8(p)
    p += 1
    var computer = ""
    var group = ""
    repeat(count) {
        if (p + 18 > length) return@repeat
        val name = String(buf, p, 15, Charsets.ISO_8859_1).trim()
        val suffix = u8(p + 15)
        val isGroup = u8(p + 16) and 0x80 != 0
        if (suffix == 0 && !isGroup && computer.isEmpty()) computer = name
        if (suffix == 0 && isGroup && group.isEmpty()) group = name
        p += 18
    }
    if (computer.isEmpty()) null else NetworkIdentity(hostName = computer, workgroup = group)
}.getOrNull()

// MARK: - Запуск

/** Объединяет ответы разных протоколов по IP. */
internal fun mergeIdentities(vararg parts: Map<String, NetworkIdentity>): Map<String, NetworkIdentity> {
    val out = mutableMapOf<String, NetworkIdentity>()
    for (part in parts) for ((ip, id) in part) out[ip] = (out[ip] ?: NetworkIdentity()) + id
    return out.mapValues { it.value.bounded() }.filterValues { !it.isEmpty }
}

/** Настоящий опрос сети, привязанный к Wi-Fi или Ethernet (при мобильном интернете пакеты ушли бы не туда). */
class NetworkDiscovery(context: Context) : LocalDiscovery {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private fun localNetwork(): Network? {
        fun local(n: Network?) = n != null && connectivity.getNetworkCapabilities(n)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } == true
        connectivity.activeNetwork.takeIf(::local)?.let { return it }
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.firstOrNull(::local)
    }

    override suspend fun discover(ips: List<String>): DiscoveryResult {
        val network = localNetwork() ?: return DiscoveryResult(emptyMap(), onLan = false)
        return DiscoveryEngine(network::bindSocket, network.socketFactory).discover(ips)
    }
}

/**
 * Сокеты опроса без зависимостей от Android: [bind] привязывает UDP-сокет к нужной сети, [sockets] создаёт TCP-сокеты
 * в ней же (для описаний UPnP). Три протокола идут параллельно, у каждого свой общий срок: весь опрос — до пяти секунд.
 */
class DiscoveryEngine(
    private val bind: (DatagramSocket) -> Unit = {},
    private val sockets: SocketFactory = SocketFactory.getDefault(),
) : LocalDiscovery {
    override suspend fun discover(ips: List<String>): DiscoveryResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val parts = listOf(
                async { runCatching { mdns() }.getOrDefault(emptyMap()) },
                async { runCatching { ssdp() }.getOrDefault(emptyMap()) },
                async { runCatching { netbios(ips.filter(RouterClient::isPrivateIPv4)) }.getOrDefault(emptyMap()) },
            ).awaitAll()
            DiscoveryResult(mergeIdentities(*parts.toTypedArray()))
        }
    }

    /** Принимает пакеты до [deadline]; [handle] получает данные и адрес отправителя (только частные IPv4). */
    private fun receive(socket: DatagramSocket, deadline: Long, handle: (ByteArray, Int, String) -> Unit) {
        val buf = ByteArray(9000)
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return
            socket.soTimeout = left.toInt()
            val packet = DatagramPacket(buf, buf.size)
            try { socket.receive(packet) } catch (_: SocketTimeoutException) { return }
            val sender = packet.address?.hostAddress ?: continue
            if (RouterClient.isPrivateIPv4(sender)) handle(packet.data, packet.length, sender)
        }
    }

    internal fun mdns(): Map<String, NetworkIdentity> = MulticastSocket(0).use { socket ->
        bind(socket)
        socket.timeToLive = 255
        val group = InetSocketAddress(InetAddress.getByName("224.0.0.251"), 5353)
        val found = mutableMapOf<String, NetworkIdentity>()
        val types = mutableSetOf<String>()
        fun ask(list: List<String>) {
            val query = mdnsQuery(list)
            repeat(2) { socket.send(DatagramPacket(query, query.size, group)) }   // два раза: UDP теряется
        }
        val handle = { data: ByteArray, length: Int, sender: String ->
            val (ids, more) = identitiesFromMdns(parseDns(data, length), sender)
            ids.forEach { (ip, id) -> found[ip] = (found[ip] ?: NetworkIdentity()) + id }
            types += more
        }
        val start = System.currentTimeMillis()
        ask(MDNS_TYPES)
        receive(socket, start + 1_500, handle)
        // Службы, о которых мы не спросили, но устройства назвали в общем перечне.
        val asked = MDNS_TYPES.map { it.substringBefore('.') }.toSet()
        // Имена типов пришли от устройств: берём только правдоподобные, чтобы не слать в сеть испорченные запросы.
        val extra = (types - asked).filter { SERVICE_LABEL.matches(it) }.take(24).map { "$it._tcp" }
        if (extra.isNotEmpty()) {
            ask(extra)
            receive(socket, start + 2_800, handle)
        }
        found
    }

    internal suspend fun ssdp(): Map<String, NetworkIdentity> {
        val locations = mutableMapOf<String, String>()
        MulticastSocket(0).use { socket ->
            bind(socket)
            socket.timeToLive = 2
            val group = InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900)
            for (target in listOf("upnp:rootdevice", "ssdp:all")) {
                val search = ssdpSearch(target)
                repeat(2) { socket.send(DatagramPacket(search, search.size, group)) }
            }
            receive(socket, System.currentTimeMillis() + 2_000) { data, length, sender ->
                val headers = parseSsdpResponse(String(data, 0, length, Charsets.UTF_8)) ?: return@receive
                val location = headers["location"] ?: return@receive
                // Описание корневого устройства — самое полное; остальные ответы того же IP его не заменяют.
                if (sender !in locations || headers["st"].orEmpty().contains("rootdevice")) locations[sender] = location
            }
        }
        // Описания запрашиваем параллельно с одним сроком на всех; сбой одного устройства не теряет остальные.
        val deadline = System.currentTimeMillis() + 2_000
        return coroutineScope {
            locations.entries.take(32).map { (ip, location) ->
                async(Dispatchers.IO) {
                    val url = descriptionUrl(location, ip) ?: return@async null
                    runCatching { httpGet(url, deadline, sockets)?.let { ip to parseUpnpDescription(it) } }.getOrNull()
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    internal fun netbios(ips: List<String>): Map<String, NetworkIdentity> {
        if (ips.isEmpty()) return emptyMap()
        val found = mutableMapOf<String, NetworkIdentity>()
        DatagramSocket().use { socket ->
            bind(socket)
            ips.take(254).forEachIndexed { i, ip ->
                val query = netbiosStatusQuery(0x4E00 + i)
                runCatching { socket.send(DatagramPacket(query, query.size, InetAddress.getByName(ip), 137)) }
            }
            receive(socket, System.currentTimeMillis() + 1_500) { data, length, sender ->
                parseNetbiosStatus(data, length)?.let { found[sender] = it }
            }
        }
        return found
    }
}

/** Демо-режим: ответы, как от настоящей сети, без сети. */
class DemoDiscovery : LocalDiscovery {
    override suspend fun discover(ips: List<String>): DiscoveryResult {
        kotlinx.coroutines.delay(1_200)
        return DiscoveryResult(DEMO_IDENTITIES.filterKeys { it in ips })
    }

    companion object {
        val DEMO_IDENTITIES = mapOf(
            "192.168.1.52" to NetworkIdentity(hostName = "LAPTOP-7Q2K", workgroup = "WORKGROUP", services = setOf(NetService.FILES)),
            "192.168.1.70" to NetworkIdentity(
                name = "Living room TV", manufacturer = "LG Electronics", model = "webOS TV OLED55C3",
                services = setOf(NetService.MEDIA_PLAYER, NetService.AIRPLAY),
            ),
            "192.168.1.80" to NetworkIdentity(
                name = "Office printer", hostName = "BRW105BAD", manufacturer = "Brother", model = "HL-L2350DW",
                services = setOf(NetService.PRINTER, NetService.WEB),
            ),
            "192.168.1.90" to NetworkIdentity(name = "Anna's iPhone", hostName = "Annas-iPhone", manufacturer = "Apple", model = "iPhone", services = setOf(NetService.APPLE)),
        )
    }
}
